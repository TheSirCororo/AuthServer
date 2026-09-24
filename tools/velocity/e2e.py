#!/usr/bin/env python3
"""End-to-end check with a real Velocity proxy.

Starts two AuthServer instances ("auth" and a stand-in "lobby"), a Velocity proxy with the AuthServer plugin in
front of them, and connects the probe client through the proxy as an offline player. Passing means: Velocity
forced offline mode after asking the HTTP API, the auth server accepted modern forwarding, the player registered,
the plugin verified the signed bridge message and moved the player to the lobby.

Requires: ./gradlew :server:shadowJar :velocity:shadowJar :probe:installDist
"""
import argparse
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import papermc  # noqa: E402

SECRET = "e2e-forwarding-secret-0123456789"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class Process:
    def __init__(self, name: str, command: list, cwd: Path, ready: str):
        self.name, self.log, self.ready = name, [], threading.Event()
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=self._pump, args=(ready,), daemon=True).start()

    def _pump(self, ready: str):
        for line in self.process.stdout:
            self.log.append(line)
            if ready in line:
                self.ready.set()

    def wait_ready(self, seconds=60):
        if not self.ready.wait(seconds):
            raise SystemExit(f"{self.name} did not start:\n" + "".join(self.log[-30:]))

    def stop(self):
        try:
            self.process.stdin.write("end\n" if self.name == "velocity" else "stop\n")
            self.process.stdin.flush()
            self.process.wait(20)
        except Exception:
            self.process.kill()


def auth_config(directory: Path, port: int, api_port: int | None):
    directory.mkdir(parents=True)
    api = f"api:\n  enabled: true\n  host: 127.0.0.1\n  port: {api_port}\n" if api_port else ""
    (directory / "config.yml").write_text(
        f"network:\n  host: 127.0.0.1\n  port: {port}\n"
        f"proxy:\n  forwarding: VELOCITY\n  secret: \"{SECRET}\"\n"
        "authentication:\n  premium-policy: AUTO\n  hashing: { memory-kib: 4096, iterations: 1 }\n" + api)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--velocity", default="4.2.0")
    parser.add_argument("--protocol", default="763", help="Client protocol (default 1.20.1)")
    parser.add_argument("--java", default="/usr/lib/jvm/java-25/bin/java")
    args = parser.parse_args()
    newest = lambda pattern: max(pattern, key=lambda path: path.stat().st_mtime)
    server_jar = newest((ROOT / "server/build/libs").glob("server-*-all.jar"))
    plugin_jar = newest((ROOT / "velocity/build/libs").glob("velocity-*.jar"))
    ports = {name: free_port() for name in ("auth", "lobby", "api", "proxy")}
    work = Path(tempfile.mkdtemp(prefix="authserver-e2e-"))
    processes = []
    try:
        auth_config(work / "auth", ports["auth"], ports["api"])
        auth_config(work / "lobby", ports["lobby"], None)
        proxy = work / "velocity"
        (proxy / "plugins/authserver").mkdir(parents=True)
        shutil.copy(plugin_jar, proxy / "plugins")
        (proxy / "forwarding.secret").write_text(SECRET)
        (proxy / "velocity.toml").write_text(f"""config-version = "2.7"
bind = "127.0.0.1:{ports['proxy']}"
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
auth = "127.0.0.1:{ports['auth']}"
lobby = "127.0.0.1:{ports['lobby']}"
try = ["auth"]
[forced-hosts]
[advanced]
[query]
""")
        (proxy / "plugins/authserver/config.toml").write_text(
            f'auth-server = "auth"\napi-url = "http://127.0.0.1:{ports["api"]}"\nsecret = "{SECRET}"\n'
            '[routing]\nstrategy = "FIRST"\nonline = ["lobby"]\noffline = ["lobby"]\n')
        for name in ("auth", "lobby"):
            processes.append(Process(name, [args.java, "-jar", str(server_jar)], work / name, "is listening on"))
        processes.append(Process("velocity", [args.java, "-jar", str(papermc.jar("velocity", args.velocity))], proxy, "Done ("))
        for process in processes:
            process.wait_ready()
        probe = ROOT / "tools/probe/build/install/probe/bin/probe"
        env = {**os.environ, "JAVA_HOME": str(Path(args.java).resolve().parents[1])}
        result = subprocess.run([str(probe), "record", args.protocol, "127.0.0.1", str(ports["proxy"]), str(work / "recording"),
                                 "30", "E2eSteve", ";".join([
                                     "register e2e-pass e2e-pass", "wait 4",
                                     "changepassword e2e-pass e2e-new-pass", "wait 1", "changepassword confirm", "wait 3",
                                     "logout", "wait 4", "login e2e-new-pass"])],
                                capture_output=True, text=True, env=env)
        print(result.stdout.strip())
        velocity_log = "".join(processes[2].log)
        auth_log = "".join(processes[0].log)
        chat = result.stdout
        checks = {
            "auth server authenticated the player": "E2eSteve authenticated (OFFLINE, REGISTER)" in auth_log,
            "proxy moved the player to the lobby": "lobby" in velocity_log and "has connected" in velocity_log,
            "/changepassword asked for confirmation": "Change your password?" in chat,
            "/changepassword changed the password": "Your password was changed." in chat,
            "/logout returned the player to the auth server": "You logged out" in chat and "Log in with /login" in chat,
            "the new password works after /logout": "E2eSteve authenticated (OFFLINE, LOGIN)" in auth_log,
        }
        for check, passed in checks.items():
            print(("PASS " if passed else "FAIL ") + check)
        if not all(checks.values()):
            print("--- velocity\n" + velocity_log[-4000:] + "\n--- auth\n" + auth_log[-4000:] + "\n--- probe\n" + result.stderr[-2000:])
            raise SystemExit(1)
    finally:
        for process in processes:
            process.stop()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
