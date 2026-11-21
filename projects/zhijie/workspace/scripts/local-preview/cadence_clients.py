import json
import subprocess
import time
from contextlib import ExitStack


def participant_env(env, index):
    prefix = "ZHIJIE_TEST_VOICE_TOKEN_"
    isolated = {key: value for key, value in env.items() if not key.upper().startswith(prefix)}
    isolated[prefix + str(index)] = env[prefix + str(index)]
    isolated["ZHIJIE_CADENCE_PARTICIPANT"] = str(index)
    return isolated


def run_clients(command, workspace, runtime, server, env, sampler_type, windows):
    processes, paths, resources = [], [], []
    with ExitStack() as stack:
        try:
            for index in range(12):
                path = runtime / f"client-{index}.log"
                paths.append(path)
                output = stack.enter_context(path.open("xb"))
                child = subprocess.Popen(command, cwd=workspace, env=participant_env(env, index), stdin=subprocess.PIPE,
                                         stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
                processes.append(child)
                stack.callback(child.stdin.close)
            sampler = sampler_type({"sfu": server, **{f"client{i}": child for i, child in enumerate(processes)}})

            def wait_for(label, deadline):
                pending = set(range(12))
                positions = [0] * 12
                while pending:
                    resources.append(sampler.sample())
                    for index in list(pending):
                        status = processes[index].poll()
                        if status is not None and (label or status != 0):
                            raise RuntimeError(f"participant {index} exited {status} before {label or 'completion'}")
                        if not label:
                            if status == 0:
                                pending.remove(index)
                        else:
                            with paths[index].open("rb") as log:
                                log.seek(positions[index])
                                chunk = log.read()
                            complete = chunk.rfind(b"\n") + 1
                            positions[index] += complete
                            if f"{label} participant={index}".encode() in chunk[:complete].splitlines():
                                pending.remove(index)
                    if pending:
                        if time.monotonic() >= deadline:
                            raise TimeoutError(f"participants {sorted(pending)} exceeded {label or 'exit'} deadline")
                        time.sleep(0.5)

            def broadcast(message):
                for child in processes:
                    child.stdin.write((message + "\n").encode())
                    child.stdin.flush()

            wait_for("CADENCE_READY", time.monotonic() + 45)
            start_ms = time.time_ns() // 1_000_000 + 1000
            broadcast(str(start_ms))
            wait_for("CADENCE_MEASURED", time.monotonic() + windows * 15 + 10)
            broadcast("close")
            wait_for(None, time.monotonic() + 15)
        finally:
            for child in processes:
                if child.poll() is None:
                    child.kill()
            for child in processes:
                child.wait(timeout=5)
            (runtime / "synthetic-resources.json").write_text(json.dumps(resources) + "\n", encoding="utf-8")
    return combined_log(paths, start_ms, windows)


def combined_log(paths, start_ms, windows):
    header = f"CADENCE_STARTED mode=synthetic unix_ms={start_ms} observation_ms={windows * 15000}"
    combined = [header]
    for index, path in enumerate(paths):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        if [line for line in lines if line.startswith("CADENCE_STARTED ")] != [header]:
            raise ValueError(f"participant {index} did not use the common measurement boundary")
        combined.extend(line for line in lines if line != header)
    return "\n".join(combined)
