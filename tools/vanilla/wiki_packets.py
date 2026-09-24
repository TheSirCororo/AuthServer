#!/usr/bin/env python3
"""Extract packet IDs from archived wiki.vg protocol pages (hosted on minecraft.wiki).

Used for releases without official Mojang mappings (1.8 - 1.14.3) and as a cross-check for later ones.
Output: {protocol: {state: {direction: {wiki packet name: id}}}} as JSON.
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

# Revisions of the full "Protocol" page for each release (see Protocol_version_numbers on minecraft.wiki).
REVISIONS = {
    47: 2772100, 107: 2772183, 109: 2772214, 110: 2772223, 210: 2772260, 315: 2772287, 316: 2772298,
    335: 2772327, 338: 2772349, 340: 2772385, 401: 2772415, 404: 2772458, 498: 2772514, 578: 2772549,
    753: 2772553, 754: 2772586, 755: 2772685, 756: 2772702, 757: 2772764, 758: 2772783, 759: 2772902,
    760: 2772944, 761: 2773015, 762: 2773029, 763: 2773082, 764: 2773142, 765: 2773281, 766: 2773283,
}
STATES = {"handshaking": "handshake", "handshake": "handshake", "status": "status", "login": "login",
          "configuration": "configuration", "play": "play"}
HEADING = re.compile(r"^(={2,4})\s*(.*?)\s*\1\s*$")
PACKET_ID = re.compile(r"^\s*\|\s*(?:rowspan=\"?\d+\"?\s*\|)?\s*(0x[0-9A-Fa-f]{1,2})\b")


def parse(text: str) -> dict:
    result: dict = {}
    state = direction = packet = None
    for line in text.splitlines():
        heading = HEADING.match(line)
        if heading:
            level, title = len(heading.group(1)), heading.group(2).strip()
            if level == 2:
                state = STATES.get(title.lower())
                direction = packet = None
            elif level == 3:
                lowered = title.lower()
                direction = "clientbound" if lowered.startswith("clientbound") else \
                    "serverbound" if lowered.startswith("serverbound") else None
                packet = None
            else:
                packet = title
            continue
        if state and direction and packet:
            match = PACKET_ID.match(line)
            if match:
                result.setdefault(state, {}).setdefault(direction, {})[packet] = int(match.group(1), 16)
                packet = None
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cache", type=Path, help="Directory for downloaded page revisions")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    pages = {}
    for protocol, revision in REVISIONS.items():
        path = args.cache / f"{protocol}.txt"
        if not path.exists():
            url = f"https://minecraft.wiki/index.php?oldid={revision}&action=raw"
            with urllib.request.urlopen(url, timeout=60) as response:
                path.write_bytes(response.read())
            time.sleep(0.3)
        pages[protocol] = parse(path.read_text(encoding="utf-8"))
        play = pages[protocol].get("play", {})
        print(protocol, {d: len(p) for d, p in play.items()}, file=sys.stderr)
    args.output.write_text(json.dumps(pages, indent=1, sort_keys=True))


if __name__ == "__main__":
    main()
