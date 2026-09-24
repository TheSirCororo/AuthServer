"""Helpers for running code against a cached official server JAR."""
import shutil
import subprocess
import zipfile
from pathlib import Path

from fetch import CACHE


def classpath(release: str) -> list:
    """Return the server classpath, unpacking the 1.18+ bundler format on first use."""
    jar = CACHE / release / "server.jar"
    with zipfile.ZipFile(jar) as bundle:
        names = bundle.namelist()
        if "META-INF/versions.list" not in names:
            return [str(jar)]
        target = CACHE / release / "unpacked"
        if not target.exists():
            temporary = target.with_name("unpacked.part")
            if temporary.exists():
                shutil.rmtree(temporary)
            for name in names:
                if name.endswith(".jar") and (name.startswith("META-INF/versions/") or name.startswith("META-INF/libraries/")):
                    output = temporary / name
                    if not output.resolve().is_relative_to(temporary.resolve()):
                        raise ValueError(f"Unsafe bundle path {name}")
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(bundle.read(name))
            temporary.rename(target)
        return sorted(str(path) for path in target.rglob("*.jar"))


def run_java(java: str, release: str, source: Path, *args, cwd: Path = None, extra_classpath=()) -> str:
    command = [java, "-cp", ":".join([*classpath(release), *extra_classpath]), str(Path(source).resolve()), *map(str, args)]
    result = subprocess.run(command, capture_output=True, text=True, cwd=cwd)
    if result.returncode != 0:
        raise RuntimeError(f"{Path(source).name} failed on {release}:\n{result.stderr[-4000:]}")
    return result.stdout
