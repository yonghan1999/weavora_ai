use livekit::prelude::LocalAudioTrack;
use livekit::webrtc::audio_stream::native::NativeAudioStream;
use tokio_stream::StreamExt;

use crate::MicrophoneTestResult;

#[derive(Default)]
struct Levels {
    frames: usize,
    samples: usize,
    peak: u16,
    squares: f64,
}

impl Levels {
    fn push(&mut self, samples: &[i16]) {
        if samples.is_empty() {
            return;
        }
        self.frames += 1;
        self.samples += samples.len();
        for &sample in samples {
            self.peak = self.peak.max(sample.unsigned_abs());
            self.squares += f64::from(sample).powi(2);
        }
    }

    fn finish(self) -> Result<MicrophoneTestResult, String> {
        if self.frames == 0 {
            return Err("AUDIO_TEST_NO_FRAMES".into());
        }
        // 三秒窗口至少收到两秒采样，避免偶然一帧被当作设备可用。
        if self.samples < 48000 * 2 {
            return Err("AUDIO_TEST_CAPTURE_INTERRUPTED".into());
        }
        Ok(MicrophoneTestResult {
            frames: self.frames,
            samples: self.samples,
            peak: f64::from(self.peak) / 32768.0,
            rms: (self.squares / self.samples as f64).sqrt() / 32768.0,
        })
    }
}

pub(crate) async fn measure(
    track: &LocalAudioTrack,
    duration: std::time::Duration,
) -> Result<MicrophoneTestResult, String> {
    let mut stream = NativeAudioStream::new(track.rtc_track(), 48000, 1);
    let deadline = tokio::time::Instant::now() + duration;
    let mut levels = Levels::default();
    while let Ok(frame) = tokio::time::timeout_at(deadline, stream.next()).await {
        let frame = frame.ok_or("AUDIO_TEST_CAPTURE_INTERRUPTED")?;
        levels.push(&frame.data);
    }
    levels.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn measured_levels_cover_full_scale_silence_and_interrupted_capture() {
        assert_eq!(
            Levels::default().finish().unwrap_err(),
            "AUDIO_TEST_NO_FRAMES"
        );
        let mut interrupted = Levels::default();
        interrupted.push(&[1; 480]);
        assert_eq!(
            interrupted.finish().unwrap_err(),
            "AUDIO_TEST_CAPTURE_INTERRUPTED"
        );
        let mut full_scale = Levels::default();
        for _ in 0..200 {
            full_scale.push(&[i16::MIN; 480]);
        }
        let result = full_scale.finish().unwrap();
        assert_eq!((result.frames, result.samples), (200, 96000));
        assert_eq!((result.peak, result.rms), (1.0, 1.0));
        let mut silence = Levels::default();
        silence.push(&[0; 96000]);
        let result = silence.finish().unwrap();
        assert_eq!((result.peak, result.rms), (0.0, 0.0));
    }

    #[tokio::test]
    async fn real_native_source_measures_pcm_and_rejects_short_capture() {
        use livekit::webrtc::{
            audio_frame::AudioFrame,
            audio_source::{native::NativeAudioSource, AudioSourceOptions, RtcAudioSource},
        };
        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
        let track = LocalAudioTrack::create_audio_track(
            "controlled-input-test",
            RtcAudioSource::Native(source.clone()),
        );
        let producer = tokio::spawn(async move {
            loop {
                let mut frame = AudioFrame::new(48000, 1, 480);
                frame.data.to_mut().fill(8192);
                source.capture_frame(&frame).await.unwrap();
            }
        });
        let result = measure(&track, std::time::Duration::from_secs(3))
            .await
            .unwrap();
        producer.abort();
        assert!(result.frames >= 200);
        assert!((result.peak - 0.25).abs() < 0.001);
        assert!((result.rms - 0.25).abs() < 0.001);
        let empty_source = NativeAudioSource::new(AudioSourceOptions::default(), 48000, 1, 100);
        let empty_track = LocalAudioTrack::create_audio_track(
            "empty-input-test",
            RtcAudioSource::Native(empty_source.clone()),
        );
        assert_eq!(
            measure(&empty_track, std::time::Duration::from_millis(100))
                .await
                .unwrap_err(),
            "AUDIO_TEST_CAPTURE_INTERRUPTED"
        );
    }
}
