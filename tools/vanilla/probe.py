#!/usr/bin/env python3
"""Record vanilla server traffic with tools/probe and verify our codecs against it.

For each release a throw-away vanilla server is started in offline mode on a flat world, the probe logs in as an
operator, triggers chat/titles/boss bars and records every clientbound frame. Running a vanilla server requires
accepting the Minecraft EULA (https://aka.ms/MinecraftEULA), so --accept-eula must be passed explicitly.

Recordings go to <cache>/<release>/recording and are consumed by gamedata generation and `probe verify`.
"""
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from hashlib import md5
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import CACHE  # noqa: E402
from versions import RELEASES  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "tools/probe/build/install/probe/bin/probe"
USERNAME = "Probe"


def offline_uuid(name: str) -> str:
    digest = bytearray(md5(f"OfflinePlayer:{name}".encode()).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def prepare(release: str, protocol: int, port: int) -> Path:
    directory = CACHE / release / "server-run"
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True)
    (directory / "eula.txt").write_text("eula=true\n")
    properties = {
        "online-mode": "false", "server-port": port, "server-ip": "127.0.0.1", "level-type": "FLAT" if protocol < 735 else "flat",
        "spawn-protection": 0, "max-players": 4, "view-distance": 3, "simulation-distance": 3,
        "network-compression-threshold": -1, "spawn-npcs": "false", "spawn-animals": "false",
        "spawn-monsters": "false", "generate-structures": "false", "enforce-secure-profile": "false",
        "difficulty": "peaceful", "allow-nether": "false", "max-tick-time": -1, "sync-chunk-writes": "false",
        "motd": "probe", "enable-status": "true", "snooper-enabled": "false", "use-native-transport": "false",
    }
    (directory / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    ops = [{"uuid": offline_uuid(USERNAME), "name": USERNAME, "level": 4, "bypassesPlayerLimit": False}]
    (directory / "ops.json").write_text(json.dumps(ops))
    return directory


def java_for(release: str, java21: str, java25: str) -> str:
    return java25 if release.startswith("26.") else java21


def record(release: str, protocol: int, java21: str, java25: str, seconds: int) -> str:
    probe_env = {**os.environ, "JAVA_HOME": str(Path(java25).resolve().parents[1])}
    port = free_port()
    directory = prepare(release, protocol, port)
    output = CACHE / release / "recording"
    shutil.rmtree(output, ignore_errors=True)
    command = [java_for(release, java21, java25), "-Xmx1G", "-jar", str(CACHE / release / "server.jar"), "nogui"]
    server = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True)
    log = []
    ready = threading.Event()

    def pump():
        for line in server.stdout:
            log.append(line)
            if "Done (" in line:
                ready.set()

    threading.Thread(target=pump, daemon=True).start()
    try:
        if not ready.wait(300):
            return f"{release}: server did not start\n" + "".join(log[-20:])
        result = subprocess.run([str(PROBE), "record", str(protocol), "127.0.0.1", str(port), str(output), str(seconds)],
                                capture_output=True, text=True, timeout=seconds + 120, env=probe_env)
        if result.returncode != 0:
            return f"{release}: probe failed\n{result.stderr[-3000:]}\n" + "".join(log[-15:])
        return f"{release}: {result.stdout.strip()}"
    finally:
        try:
            server.stdin.write("stop\n")
            server.stdin.flush()
            server.wait(60)
        except Exception:
            server.kill()
        (CACHE / release / "server-log.txt").write_text("".join(log))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("releases", nargs="*")
    parser.add_argument("--accept-eula", action="store_true", help="Accept the Minecraft EULA for the throw-away servers")
    parser.add_argument("--java21", default="/usr/lib/jvm/java-25/bin/java", help="Java for releases before 26.1")
    parser.add_argument("--java25", default="/usr/lib/jvm/java-25/bin/java")
    parser.add_argument("--seconds", type=int, default=8)
    parser.add_argument("--parallel", type=int, default=4)
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("running vanilla servers requires --accept-eula (https://aka.ms/MinecraftEULA)")
    if not PROBE.exists():
        parser.error("build the probe first: ./gradlew :probe:installDist")
    selected = [(protocol, release) for protocol, release, _ in RELEASES if not args.releases or release in args.releases]
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        for line in pool.map(lambda item: record(item[1], item[0], args.java21, args.java25, args.seconds), selected):
            print(line, flush=True)


if __name__ == "__main__":
    main()
