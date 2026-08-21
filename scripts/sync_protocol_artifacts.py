#!/usr/bin/env python3
"""Synchronize canonical runtime/test artifacts from superqr-protocol."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import sys


MAPPINGS = (
    ("contracts/v7_production_qr_contract.json", "app/src/main/assets/v7_production_qr_contract.json"),
    ("contracts/v7_production_qr_contract.json", "app/src/test/resources/v7_production_qr_contract.json"),
    ("test-vectors/v7-production-qr/vectors.json", "app/src/test/resources/v7_production_qr_vectors.json"),
    ("test-vectors/v7-colorgrid8-lab/colorgrid8_manifest.json", "app/src/main/assets/colorgrid8_manifest.json"),
)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: sync_protocol_artifacts.py <superqr-protocol checkout>")
        return 2
    protocol_root = Path(sys.argv[1]).resolve()
    repository_root = Path(__file__).resolve().parents[1]
    for source_name, destination_name in MAPPINGS:
        source = protocol_root / source_name
        destination = repository_root / destination_name
        if not source.is_file():
            raise FileNotFoundError(source)
        json.loads(source.read_text(encoding="utf-8"))
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        print(f"synced {source_name} -> {destination_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
