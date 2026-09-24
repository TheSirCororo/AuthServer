#!/usr/bin/env python3
"""Run Mojang's built-in data generator (--reports) for cached releases (1.13+).

Reports land in <cache>/<release>/reports: blocks.json, registries.json and, since 1.21, packets.json.
"""
import argparse
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import CACHE  # noqa: E402
from versions import RELEASES  # noqa: E402

FIRST_WITH_GENERATOR = 393  # 1.13


def generate(java: str, release: str) -> Path:
    directory = CACHE / release
    reports = directory / "reports"
    if (reports / "blocks.json").exists():
        return reports
    work = directory / "datagen"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir()
    jar = str(directory / "server.jar")
    bundler = subprocess.run(["unzip", "-l", jar, "META-INF/versions.list"], capture_output=True).returncode == 0
    command = [java, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", jar] if bundler \
        else [java, "-cp", jar, "net.minecraft.data.Main"]
    subprocess.run([*command, "--reports", "--output", str(work / "generated")], check=True, cwd=work,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.move(str(work / "generated" / "reports"), reports)
    shutil.rmtree(work)
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("releases", nargs="*")
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    wanted = args.releases or [release for protocol, release, _ in RELEASES if protocol >= FIRST_WITH_GENERATOR]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for reports in pool.map(lambda release: generate(args.java, release), wanted):
            print(reports)


if __name__ == "__main__":
    main()
