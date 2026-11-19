use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::Path;

use livekit::webrtc::native::apm::AudioProcessingModule;
use zhijie_voice as _;

const SAMPLES: usize = 480;
const BYTES: usize = SAMPLES * 2;

fn compare(input: &Path, output: &Path) -> io::Result<()> {
    let source = File::open(input)?;
    let metadata = source.metadata()?;
    let length = metadata.len();
    if !metadata.is_file() || length == 0 || length % BYTES as u64 != 0 {
        return Err(io::Error::other(
            "expected nonempty whole 10 ms frames of mono 48 kHz s16le PCM",
        ));
    }
    fs::create_dir(output)?;
    let mut source = BufReader::new(source);
    let mut off = BufWriter::new(File::create_new(output.join("ns-off.s16le"))?);
    let mut on = BufWriter::new(File::create_new(output.join("ns-on.s16le"))?);
    let mut bypass = AudioProcessingModule::new(false, false, false, false);
    let mut suppress = AudioProcessingModule::new(false, false, false, true);
    let mut bytes = [0_u8; BYTES];
    for _ in 0..length / BYTES as u64 {
        source.read_exact(&mut bytes)?;
        let input: [i16; SAMPLES] = std::array::from_fn(|index| {
            i16::from_le_bytes([bytes[index * 2], bytes[index * 2 + 1]])
        });
        for (processor, writer) in [(&mut bypass, &mut off), (&mut suppress, &mut on)] {
            let mut frame = input;
            processor
                .process_stream(&mut frame, 48_000, 1)
                .map_err(io::Error::other)?;
            for (dest, sample) in bytes.chunks_exact_mut(2).zip(frame) {
                dest.copy_from_slice(&sample.to_le_bytes());
            }
            writer.write_all(&bytes)?;
        }
    }
    if source.read(&mut bytes)? != 0 {
        return Err(io::Error::other("input length changed during processing"));
    }
    off.flush()?;
    on.flush()?;
    println!(
        "frames={} rate=48000 channels=1 format=s16le scope=offline-software-apm",
        length / BYTES as u64
    );
    Ok(())
}

fn main() -> io::Result<()> {
    let mut args = std::env::args_os().skip(1);
    let (Some(input), Some(output), None) = (args.next(), args.next(), args.next()) else {
        return Err(io::Error::other(
            "usage: compare_noise_suppression <input.s16le> <new-output-directory>",
        ));
    };
    compare(Path::new(&input), Path::new(&output))
}
