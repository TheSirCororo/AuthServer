#!/usr/bin/env python3
"""Local test stand: Velocity in front of AuthServer and a Paper backend.

    tools/stand/stand.py up --accept-eula     # build, prepare run/stand/ and start everything
    tools/stand/stand.py up --no-build        # restart with the jars built last time
    tools/stand/stand.py clean                # delete run/stand/ (accounts, worlds, configs)

Connect to 127.0.0.1:25565 (any client version the backend supports). New players land on AuthServer, register
or log in and are moved to Paper ("lobby"). The example plugin is installed into AuthServer.

The console forwards each line to one process: "auth <command>", "paper <command>" or "velocity <command>";
lines without a prefix go to AuthServer. "stop" (or Ctrl+C) shuts everything down.

Everything lives in run/stand/ and survives restarts: configs are written only when missing (edit them freely),
jars are replaced on every start. The forwarding secret is generated once.
"""
import argparse
import ctypes
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import papermc  # noqa: E402

COLORS = {"auth": "\033[36m", "paper": "\033[33m", "velocity": "\033[35m"}
RESET = "\033[0m"


def write_once(path: Path, text: str):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)


def newest(directory: Path, pattern: str) -> Path:
    jars = [jar for jar in directory.glob(pattern) if not jar.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise SystemExit(f"No {pattern} in {directory}; run without --no-build")
    return max(jars, key=lambda jar: jar.stat().st_mtime)


def port_holder(port: int) -> str:
    """Who listens on a port, as far as `ss` can tell (other users' processes are not shown)."""
    try:
        output = subprocess.run(["ss", "-Hltnp", f"sport = :{port}"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""
    holders = {f"{name} (pid {pid})" for name, pid in re.findall(r'\("([^"]+)",pid=(\d+)', output)}
    return ", ".join(sorted(holders))


def check_ports(ports: dict):
    """Fails before anything starts if a port is taken, e.g. by a server left over from an earlier run."""
    busy = []
    for name, port in ports.items():
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                holder = port_holder(port)
                busy.append(f"  {port} ({name}) is in use" + (f" by {holder}" if holder else ""))
    if busy:
        raise SystemExit("Cannot start the stand, ports are taken:\n" + "\n".join(busy) +
                         "\nStop those processes (kill <pid>) or pick other ports with --port/--auth-port/--paper-port/--api-port.")


def _load_prctl():
    try:
        return ctypes.CDLL(None).prctl
    except (OSError, AttributeError):
        return None


# Looked up here: the child runs die_with_parent between fork and exec, where it should do as little as possible.
_PRCTL = _load_prctl() if sys.platform == "linux" else None
_PR_SET_PDEATHSIG = 1


def die_with_parent():
    """Linux: the child gets SIGTERM when this script dies, however it dies, so no server outlives the stand."""
    _PRCTL(_PR_SET_PDEATHSIG, signal.SIGTERM)


class Process:
    """A server process whose output is printed with a colored prefix."""

    def __init__(self, name: str, command: list, cwd: Path, stop_command: str):
        self.name, self.stop_command = name, stop_command
        # Own process group: Ctrl+C reaches only this script, which then stops the servers gracefully.
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, start_new_session=True,
                                        preexec_fn=die_with_parent if _PRCTL else None)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        prefix = f"{COLORS[self.name]}[{self.name}]{RESET} " if sys.stdout.isatty() else f"[{self.name}] "
        for line in self.process.stdout:
            sys.stdout.write(prefix + line)
            sys.stdout.flush()
        sys.stdout.write(f"{prefix}exited with code {self.process.wait()}\n")

    def send(self, command: str):
        if self.process.poll() is None:
            self.process.stdin.write(command + "\n")
            self.process.stdin.flush()

    def stop(self, timeout=60):
        try:
            self.send(self.stop_command)
            self.process.wait(timeout)
        except (subprocess.TimeoutExpired, BrokenPipeError, OSError):
            self.kill()

    def kill(self):
        if self.process.poll() is None:
            self.process.kill()


def build(java_home: Path):
    command = [str(ROOT / "gradlew"), ":server:shadowJar", ":velocity:shadowJar", ":example-plugin:jar", "--console=plain", "-q"]
    subprocess.run(command, cwd=ROOT, check=True, env={**os.environ, "JAVA_HOME": str(java_home)})


def prepare(args, directory: Path) -> dict:
    """Writes missing configs, installs fresh jars and returns the paths of the servers' jars."""
    paper_dir, auth_dir, proxy_dir = directory / "paper", directory / "auth", directory / "velocity"
    secret_file = directory / "forwarding.secret"
    write_once(secret_file, secrets.token_urlsafe(24))
    secret = secret_file.read_text().strip()
    host = "127.0.0.1"

    eula = paper_dir / "eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text():
        if not args.accept_eula:
            raise SystemExit("Paper needs the Minecraft EULA (https://aka.ms/MinecraftEULA) accepted: pass --accept-eula")
        paper_dir.mkdir(parents=True, exist_ok=True)
        eula.write_text("eula=true\n")
    write_once(paper_dir / "server.properties",
               f"server-ip={host}\nserver-port={args.paper_port}\nonline-mode=false\nwhite-list=false\nenforce-secure-profile=false\n"
               "motd=AuthServer stand\nspawn-protection=0\nview-distance=6\n")
    write_once(paper_dir / "config/paper-global.yml",
               f"proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: '{secret}'\n")

    write_once(auth_dir / "config.yml",
               f"network:\n  host: {host}\n  port: {args.auth_port}\n"
               f"proxy:\n  forwarding: VELOCITY\n  secret: \"{secret}\"\n"
               f"api:\n  enabled: true\n  host: {host}\n  port: {args.api_port}\n")
    plugins = auth_dir / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    for old in plugins.glob("example-plugin-*.jar"):
        old.unlink()
    shutil.copy(newest(ROOT / "examples/example-plugin/build/libs", "example-plugin-*.jar"), plugins)

    write_once(proxy_dir / "forwarding.secret", secret)
    write_once(proxy_dir / "velocity.toml", f"""config-version = "2.7"
bind = "{host}:{args.port}"
motd = "<aqua>AuthServer stand"
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
auth = "{host}:{args.auth_port}"
lobby = "{host}:{args.paper_port}"
try = ["auth"]

[forced-hosts]

[advanced]
# Players who pick the licensed login in the auth server's menu are transferred back (1.20.5+ clients).
accepts-transfers = true

[query]
""")
    write_once(proxy_dir / "plugins/authserver/config.toml",
               f'auth-server = "auth"\napi-url = "http://{host}:{args.api_port}"\nsecret = "{secret}"\n'
               'licensed-transfer = true\n'
               '[routing]\nstrategy = "FIRST"\nonline = ["lobby"]\noffline = ["lobby"]\n')
    for old in (proxy_dir / "plugins").glob("velocity-*.jar"):
        old.unlink()
    shutil.copy(newest(ROOT / "velocity/build/libs", "velocity-*.jar"), proxy_dir / "plugins")

    return {
        "auth": newest(ROOT / "server/build/libs", "server-*-all.jar"),
        "paper": papermc.jar("paper", args.paper or papermc.latest_version("paper")),
        "velocity": papermc.jar("velocity", args.velocity or papermc.latest_version("velocity")),
    }


def up(args):
    directory = Path(args.dir).resolve()
    java = Path(args.java)
    check_ports({"velocity": args.port, "auth": args.auth_port, "paper": args.paper_port, "auth API": args.api_port})
    if not args.no_build:
        build(java.resolve().parents[1])
    jars = prepare(args, directory)
    processes = {
        "auth": Process("auth", [str(java), "-jar", str(jars["auth"])], directory / "auth", "stop"),
        "paper": Process("paper", [str(java), f"-Xmx{args.paper_memory}", "-jar", str(jars["paper"]), "--nogui"],
                         directory / "paper", "stop"),
        "velocity": Process("velocity", [str(java), "-jar", str(jars["velocity"])], directory / "velocity", "end"),
    }
    print(f"Stand is starting: connect to 127.0.0.1:{args.port}. Console: [auth|paper|velocity] <command>, 'stop' to quit.")

    stopping = threading.Event()

    def shutdown():
        if stopping.is_set():
            return
        stopping.set()
        print("Stopping the stand... (Ctrl+C again to kill it)")
        for name in ("velocity", "paper", "auth"):
            processes[name].stop()

    def on_signal(*_):
        # The first signal stops the servers gracefully in the background; a second one does not wait for them.
        if stopping.is_set():
            print("Killing the stand")
            for process in processes.values():
                process.kill()
        else:
            threading.Thread(target=shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    def console():
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            if line in ("stop", "exit", "quit"):
                break
            target, _, rest = line.partition(" ")
            if target in processes and rest:
                processes[target].send(rest)
            else:
                processes["auth"].send(line)
        shutdown()

    threading.Thread(target=console, daemon=True).start()
    failed = False
    while any(process.process.poll() is None for process in processes.values()):
        try:
            next(iter(processes.values())).process.wait(0.5)
        except subprocess.TimeoutExpired:
            pass
        if not stopping.is_set() and any(process.process.poll() is not None for process in processes.values()):
            crashed = [name for name, process in processes.items() if process.process.poll() is not None]
            print(f"{', '.join(crashed)} exited; stopping the rest")
            failed = True
            threading.Thread(target=shutdown, daemon=True).start()
    if failed:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=str(ROOT / "run/stand"), help="Stand directory (default run/stand)")
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("up", help="Build and start the stand")
    start.add_argument("--accept-eula", action="store_true", help="Accept the Minecraft EULA for the Paper server")
    start.add_argument("--no-build", action="store_true", help="Use the jars from the last build")
    start.add_argument("--paper", help="Paper version (default: newest release)")
    start.add_argument("--velocity", help="Velocity version (default: newest release)")
    start.add_argument("--paper-memory", default="2G")
    start.add_argument("--java", default=os.environ.get("STAND_JAVA", "/usr/lib/jvm/java-25/bin/java"))
    start.add_argument("--port", type=int, default=25565, help="Velocity port players connect to")
    start.add_argument("--auth-port", type=int, default=25566)
    start.add_argument("--paper-port", type=int, default=25567)
    start.add_argument("--api-port", type=int, default=25580, help="AuthServer HTTP API for the Velocity plugin")
    commands.add_parser("clean", help="Delete the stand directory")
    args = parser.parse_args()
    if args.command == "up":
        up(args)
    else:
        shutil.rmtree(args.dir, ignore_errors=True)
        print(f"Deleted {args.dir}")


if __name__ == "__main__":
    main()
