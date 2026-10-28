#!/usr/bin/env python3
"""Start a legacy (1.8.8) server jar, wait for startup, ping it, stop it.

Usage: legacy_fork_smoke.py <java> <jar> <workdir> <port> [timeout_seconds]

The working directory must already contain eula.txt, server.properties and
spigot.yml. Console output is mirrored to stdout and to <workdir>/console.log.
Exit code 0 when the server reached "Done (", answered the status ping with
protocol 47 and shut down cleanly.
"""

import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from legacy_status_ping import PROTOCOL_1_8, ping  # noqa: E402


def main() -> int:
    if len(sys.argv) < 5:
        print(__doc__, file=sys.stderr)
        return 1
    java, jar, workdir, port = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    timeout = int(sys.argv[5]) if len(sys.argv) > 5 else 300

    proc = subprocess.Popen(
        [java, "-Xms512M", "-Xmx1G", "-jar", jar, "nogui"],
        cwd=workdir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    done = threading.Event()
    log_path = os.path.join(workdir, "console.log")

    def pump() -> None:
        with open(log_path, "w", encoding="utf-8") as log:
            for line in proc.stdout:
                sys.stdout.write(line)
                log.write(line)
                if "Done (" in line:
                    done.set()

    reader = threading.Thread(target=pump, daemon=True)
    reader.start()

    started = time.monotonic()
    while not done.wait(1):
        if proc.poll() is not None:
            print(f"server exited early with code {proc.returncode}", file=sys.stderr)
            return 1
        if time.monotonic() - started > timeout:
            print(f"timeout: no 'Done (' after {timeout}s", file=sys.stderr)
            proc.kill()
            return 1
    print(f"startup finished after {time.monotonic() - started:.1f}s")

    rc = 0
    try:
        status = ping("127.0.0.1", port, PROTOCOL_1_8)
        version = status.get("version", {})
        print(f"status ping: {version.get('name')} protocol {version.get('protocol')}")
        if version.get("protocol") != PROTOCOL_1_8:
            print("protocol mismatch", file=sys.stderr)
            rc = 1
    except (OSError, ValueError) as exc:
        print(f"status ping failed: {exc}", file=sys.stderr)
        rc = 1

    proc.stdin.write("stop\n")
    proc.stdin.flush()
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        print("server did not stop within 60s", file=sys.stderr)
        proc.kill()
        return 1
    reader.join(timeout=5)
    print(f"server exited with code {proc.returncode}")
    if proc.returncode != 0:
        rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
