import json
import os
from pathlib import Path
import re
import subprocess
import sys

from cadence_clients import coordinate_clients


def validate(paths, speakers, start_ms, windows):
    if len(paths) != 12 or len(speakers) != 12 or len(set(speakers)) != 12:
        raise ValueError("twelve unique business participants required")
    output = []
    for receiver, path in enumerate(paths):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        starts = [line for line in lines if line.startswith("BUSINESS_STARTED ")]
        if starts != [f"BUSINESS_STARTED participant={receiver} unix_ms={start_ms}"]:
            raise ValueError("client used an unexpected measurement boundary")
        seen = set()
        tails = set()
        for line in lines:
            if line.startswith("PCM_AFTER_NETWORK "):
                match = re.fullmatch(r"PCM_AFTER_NETWORK receiver=(\d+) speaker=(\S+) frames=(\d+) last_ms=(\d+)", line)
                if (windows != 7 or not match or int(match[1]) != receiver or match[2] in tails
                        or int(match[3]) < 800 or int(match[4]) < 10000):
                    raise ValueError("invalid post-network PCM evidence")
                tails.add(match[2])
                output.append(line)
                continue
            if not line.startswith("PCM_DIRECTION "):
                continue
            match = re.fullmatch(r"PCM_DIRECTION receiver=(\d+) speaker=(\S+) frequency_hz=(\d+) non_silent_frames=(\[[\d, ]+\])", line)
            if match is None or int(match[1]) != receiver or match[2] not in speakers or match[2] in seen:
                raise ValueError("invalid or duplicate business audio direction")
            frames = json.loads(match[4])
            if int(match[3]) != 300 + 100 * speakers.index(match[2]) or len(frames) != windows or min(frames) < 1200:
                raise ValueError("invalid business audio window")
            seen.add(match[2])
            output.append(line)
        if seen != set(speakers) - {speakers[receiver]}:
            raise ValueError("missing or unexpected business audio peer")
        if windows == 7 and tails != seen:
            raise ValueError("missing or unexpected post-network PCM direction")
    return output


def main(directory):
    workspace = Path(__file__).resolve().parents[2]
    build = subprocess.run(["cargo", "test", "--offline", "--locked", "-p", "zhijie-voice", "--test", "livekit_media",
                            "--no-run", "--message-format=json"], cwd=workspace, capture_output=True, check=True, timeout=90)
    artifacts = [json.loads(line) for line in build.stdout.splitlines()]
    executable, = [row["executable"] for row in artifacts if row.get("target", {}).get("name") == "livekit_media" and row.get("executable")]
    speakers = [os.environ[f"ZHIJIE_TEST_SPEAKER_{index}"] for index in range(12)]
    combined = os.environ.get("ZHIJIE_TEST_COMBINED_CHANNELS") == "true"
    windows = 7 if combined else 5
    env = {key: value for key, value in os.environ.items() if not key.upper().startswith(("ZHIJIE_", "LIVEKIT_"))}
    for name in ["ZHIJIE_TEST_VOICE_URL", *(f"ZHIJIE_TEST_{prefix}_{index}" for prefix in ("SPEAKER", "VOICE_TOKEN") for index in range(12))]:
        env[name] = os.environ[name]
    env["ZHIJIE_TEST_COMBINED_CHANNELS"] = str(combined).lower()
    if combined:
        env["ZHIJIE_TEST_NETWORK_STOPPED"] = os.environ["ZHIJIE_TEST_NETWORK_STOPPED"]
    ready = Path(os.environ["ZHIJIE_TEST_FULL_ROOM_READY"]) if combined else None
    paths, start = coordinate_clients([executable, "--ignored", "--exact", "full_room_all_to_all_audio", "--nocapture"],
                                      workspace, directory, env, windows, on_start=ready.touch if ready else None)
    for line in validate(paths, speakers, start, windows):
        print(line)
    print(f"BUSINESS_PROCESSES_OK clients=12 common_start_ms={start}")
    print(f"FULL_ROOM_OK participants=12 directions=132 observation_seconds={windows * 15} windows={windows}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
