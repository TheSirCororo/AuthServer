"""Downloads of PaperMC projects (Paper, Velocity) from the fill API, cached and checksum-verified.

Shared by tools/velocity/e2e.py and tools/stand/stand.py.
"""
import hashlib
import json
import os
import re
import urllib.request
from pathlib import Path

CACHE = Path(os.environ.get("AUTHSERVER_VANILLA_CACHE", Path.home() / ".cache/authserver")) / "papermc"
API = "https://fill.papermc.io/v3/projects"


def fetch(url: str):
    # PaperMC refuses requests without a descriptive user agent.
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AuthServer-tools (github.com/cororo)"}))


def latest_version(project: str) -> str:
    """Newest release version (no pre-releases or release candidates)."""
    with fetch(f"{API}/{project}") as response:
        groups = json.load(response)["versions"]
    for versions in groups.values():  # newest group first, newest version first
        for version in versions:
            if re.fullmatch(r"[0-9.]+", version):
                return version
    raise SystemExit(f"No {project} release found")


def jar(project: str, version: str) -> Path:
    """The latest build of project@version, downloaded once into the cache."""
    with fetch(f"{API}/{project}/versions/{version}/builds/latest") as response:
        download = json.load(response)["downloads"]["server:default"]
    target = CACHE / download["name"]
    if not target.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {download['name']}")
        with fetch(download["url"]) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != download["checksums"]["sha256"]:
            raise SystemExit(f"{download['name']} checksum mismatch")
        target.write_bytes(data)
    return target
