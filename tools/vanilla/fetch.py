#!/usr/bin/env python3
"""Download official Minecraft server JARs (and Mojang mappings when published) into a local cache.

Every file is verified against the SHA-1 published in Mojang's version manifest.
The cache defaults to ~/.cache/authserver/vanilla and can be moved with AUTHSERVER_VANILLA_CACHE.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from versions import RELEASES  # noqa: E402

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
CACHE = Path(os.environ.get("AUTHSERVER_VANILLA_CACHE", Path.home() / ".cache/authserver/vanilla"))


def download(url: str, target: Path, sha1: str) -> Path:
    if target.exists() and hashlib.sha1(target.read_bytes()).hexdigest() == sha1:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, open(temporary, "wb") as output:
        while chunk := response.read(1 << 20):
            output.write(chunk)
    actual = hashlib.sha1(temporary.read_bytes()).hexdigest()
    if actual != sha1:
        temporary.unlink()
        raise ValueError(f"SHA-1 mismatch for {url}: {actual} != {sha1}")
    temporary.replace(target)
    return target


def fetch_release(release: str, manifest: dict) -> Path:
    entry = next(v for v in manifest["versions"] if v["id"] == release)
    directory = CACHE / release
    meta_path = directory / "version.json"
    download(entry["url"], meta_path, entry["sha1"])
    meta = json.loads(meta_path.read_text())
    downloads = meta["downloads"]
    download(downloads["server"]["url"], directory / "server.jar", downloads["server"]["sha1"])
    if "server_mappings" in downloads:
        mappings = downloads["server_mappings"]
        download(mappings["url"], directory / "server_mappings.txt", mappings["sha1"])
    return directory


def jar_path(release: str) -> Path:
    return CACHE / release / "server.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("releases", nargs="*", help="Releases to fetch (default: every supported release)")
    args = parser.parse_args()
    wanted = args.releases or [release for _, release, _ in RELEASES]
    with urllib.request.urlopen(MANIFEST, timeout=60) as response:
        manifest = json.load(response)
    with ThreadPoolExecutor(max_workers=6) as pool:
        for directory in pool.map(lambda release: fetch_release(release, manifest), wanted):
            print(directory)


if __name__ == "__main__":
    main()
