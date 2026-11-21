use std::time::Duration;

use livekit::prelude::*;
use livekit::webrtc::stats::RtcStats;
use serde::Serialize;
use tokio::time::Instant;

pub enum ObservedTrack {
    Outbound(LocalAudioTrack),
    Inbound(RemoteAudioTrack),
}

#[derive(Debug, Serialize)]
struct Observation {
    mode: String,
    receiver: usize,
    speaker: String,
    sample: usize,
    elapsed_ms: u128,
    request_us: u128,
    rtp_id: String,
    ssrc: u32,
    rtc_timestamp_us: i64,
    packets: u64,
    bytes: u64,
    #[serde(flatten)]
    audio: AudioCounters,
}

#[derive(Debug, Serialize)]
#[serde(tag = "direction", rename_all = "lowercase")]
enum AudioCounters {
    Outbound,
    Inbound {
        packets_lost: i64,
        packets_discarded: u64,
        jitter_seconds: f64,
        jitter_buffer_delay: f64,
        jitter_buffer_emitted: u64,
        total_samples: u64,
        concealed: u64,
        silent_concealed: u64,
        samples_duration: f64,
    },
}

pub async fn observe(
    mode: String,
    receiver: usize,
    speaker: String,
    track: ObservedTrack,
    start: Instant,
    windows: usize,
) {
    for sample in 0..=windows {
        tokio::time::sleep_until(start + Duration::from_secs((sample * 15) as u64)).await;
        let requested = Instant::now();
        let report = tokio::time::timeout(Duration::from_secs(3), async {
            match &track {
                ObservedTrack::Outbound(track) => track.get_stats().await,
                ObservedTrack::Inbound(track) => track.get_stats().await,
            }
        })
        .await
        .expect("RTP diagnostic statistics deadline")
        .expect("read selected RTP audio statistics");
        let mut observations = Vec::new();
        for stats in report {
            let (rtp_id, ssrc, rtc_timestamp_us, packets, bytes, audio) = match stats {
                RtcStats::OutboundRtp(stats)
                    if matches!(&track, ObservedTrack::Outbound(_))
                        && stats.stream.kind == "audio" =>
                {
                    (
                        stats.rtc.id,
                        stats.stream.ssrc,
                        stats.rtc.timestamp,
                        stats.sent.packets_sent,
                        stats.sent.bytes_sent,
                        AudioCounters::Outbound,
                    )
                }
                RtcStats::InboundRtp(stats)
                    if matches!(&track, ObservedTrack::Inbound(_))
                        && stats.stream.kind == "audio" =>
                {
                    (
                        stats.rtc.id,
                        stats.stream.ssrc,
                        stats.rtc.timestamp,
                        stats.received.packets_received,
                        stats.inbound.bytes_received,
                        AudioCounters::Inbound {
                            packets_lost: stats.received.packets_lost,
                            packets_discarded: stats.inbound.packets_discarded,
                            jitter_seconds: stats.received.jitter,
                            jitter_buffer_delay: stats.inbound.jitter_buffer_delay,
                            jitter_buffer_emitted: stats.inbound.jitter_buffer_emitted_count,
                            total_samples: stats.inbound.total_samples_received,
                            concealed: stats.inbound.concealed_samples,
                            silent_concealed: stats.inbound.silent_concealed_samples,
                            samples_duration: stats.inbound.total_samples_duration,
                        },
                    )
                }
                _ => continue,
            };
            observations.push(Observation {
                mode: mode.clone(),
                receiver,
                speaker: speaker.clone(),
                sample,
                elapsed_ms: Instant::now().duration_since(start).as_millis(),
                request_us: requested.elapsed().as_micros(),
                rtp_id,
                ssrc,
                rtc_timestamp_us,
                packets,
                bytes,
                audio,
            });
        }
        assert_eq!(
            observations.len(),
            1,
            "one audio RTP stream per selected track"
        );
        let observation = observations.pop().expect("selected audio RTP stream");
        println!(
            "RTP_CADENCE {}",
            serde_json::to_string(&observation).expect("serialize numeric RTP observation")
        );
    }
}
