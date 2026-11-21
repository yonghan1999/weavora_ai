use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::time::Instant;

pub type StopSignal = Arc<OnceLock<Instant>>;

pub async fn watch_stop(path: PathBuf, signal: StopSignal) {
    loop {
        match std::fs::metadata(&path) {
            Ok(metadata) => {
                assert!(
                    metadata.is_file(),
                    "network completion marker must be a file"
                );
                signal.set(Instant::now()).unwrap();
                return;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(error) => panic!("cannot read network completion marker: {error}"),
        }
    }
}

#[derive(Default)]
pub struct Tail {
    frames: [usize; 10],
    last: Duration,
}

impl Tail {
    pub fn record(&mut self, received: Instant, stopped: &StopSignal) {
        if let Some(elapsed) = stopped
            .get()
            .and_then(|start| received.checked_duration_since(*start))
        {
            if elapsed.as_secs() < 10 {
                self.frames[elapsed.as_secs() as usize] += 1;
            }
            self.last = elapsed;
        }
    }

    pub fn evidence(&self) -> Option<(usize, u128)> {
        let frames = self.frames.iter().sum();
        (self.last >= Duration::from_secs(10)
            && frames >= 800
            && self.frames.iter().all(|count| *count > 0))
        .then_some((frames, self.last.as_millis()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    #[ignore = "explicit ten-second local PCM check; no network or audio device"]
    async fn native_pcm_exercises_tail_without_network() {
        use livekit::prelude::*;
        use livekit::webrtc::{
            audio_frame::AudioFrame,
            audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
            audio_stream::native::NativeAudioStream,
        };
        use tokio_stream::StreamExt;

        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
        let track = LocalAudioTrack::create_audio_track(
            "tail-check",
            RtcAudioSource::Native(source.clone()),
        );
        let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
        let mut input = AudioFrame::new(48000, 1, 480);
        for (index, sample) in input.data.to_mut().iter_mut().enumerate() {
            *sample = if index % 48 < 24 { 1000 } else { -1000 };
        }
        let producer = tokio::spawn(async move {
            loop {
                source.capture_frame(&input).await.unwrap();
            }
        });
        let signal = StopSignal::default();
        let start = Instant::now();
        signal.set(start).unwrap();
        let mut tail = Tail::default();
        tokio::time::timeout(Duration::from_secs(15), async {
            loop {
                let frame = stream.next().await.unwrap();
                if frame.data.iter().any(|sample| sample.unsigned_abs() > 100) {
                    tail.record(Instant::now(), &signal);
                }
                if start.elapsed() > Duration::from_millis(10200) {
                    break;
                }
            }
        })
        .await
        .unwrap();
        producer.abort();
        assert!(producer.await.unwrap_err().is_cancelled());
        let (frames, last_ms) = tail.evidence().unwrap_or_else(|| {
            panic!(
                "native PCM tail insufficient: {:?}, {:?}",
                tail.frames, tail.last
            )
        });
        println!("NATIVE_TAIL_OK frames={frames} last_ms={last_ms}");
    }

    #[tokio::test]
    async fn watcher_starts_only_after_marker_creation() {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path =
            std::env::temp_dir().join(format!("network-tail-{}-{nonce}", std::process::id()));
        let stopped = StopSignal::default();
        let watcher = tokio::spawn(watch_stop(path.clone(), stopped.clone()));
        tokio::time::sleep(Duration::from_millis(75)).await;
        assert!(stopped.get().is_none());
        let before_creation = Instant::now();
        drop(std::fs::File::create_new(&path).unwrap());
        let result = tokio::time::timeout(Duration::from_secs(2), watcher).await;
        std::fs::remove_file(path).unwrap();
        result.unwrap().unwrap();
        assert!(*stopped.get().unwrap() >= before_creation);
    }

    #[test]
    fn pre_stop_audio_or_waiting_for_exit_cannot_satisfy_tail() {
        let stopped = StopSignal::default();
        let start = Instant::now();
        let mut tail = Tail::default();
        tail.record(start, &stopped);
        stopped.set(start + Duration::from_secs(1)).unwrap();
        for offset in 0..1000 {
            tail.record(start + Duration::from_millis(offset), &stopped);
        }
        tail.record(start + Duration::from_secs(12), &stopped);
        assert_eq!(tail.evidence(), None);
    }

    #[test]
    fn tail_needs_audio_across_ten_seconds_and_after_boundary() {
        let start = Instant::now();
        let stopped = StopSignal::default();
        stopped.set(start).unwrap();
        for missing in [None, Some(4)] {
            let mut tail = Tail::default();
            for offset in 0..1000 {
                if Some(offset / 100) != missing {
                    tail.record(start + Duration::from_millis(offset * 10), &stopped);
                }
            }
            assert_eq!(tail.evidence(), None);
            tail.record(start + Duration::from_secs(10), &stopped);
            assert_eq!(tail.evidence(), missing.is_none().then_some((1000, 10000)));
        }
    }
}
