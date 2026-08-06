#!/usr/bin/env python3
import sys
import shutil
import hashlib
import json
from pathlib import Path

EXPECTED_HASH = "4b3e90a0a24106795066eabfbbddf6bdafa9b14584709c2ebb0614a12d07d757"

def main():
    if len(sys.argv) < 2:
        print("Usage: python sync_contract.py <path_to_source_visual_contract.json>")
        sys.exit(1)
        
    src_path = Path(sys.argv[1])
    if not src_path.exists():
        print(f"Error: Source path {src_path} does not exist.")
        sys.exit(1)
        
    with open(src_path, "r", encoding="utf-8") as f:
        data = json.load(f)
        
    canon = json.dumps(data, sort_keys=True, separators=(',', ':'))
    actual_hash = hashlib.sha256(canon.encode('utf-8')).hexdigest()
    
    if actual_hash != EXPECTED_HASH:
        print(f"Error: Contract SHA-256 mismatch! Got: {actual_hash}, expected: {EXPECTED_HASH}")
        sys.exit(1)
        
    dest_vision = Path(__file__).parent.parent / "vision" / "src" / "main" / "assets" / "visual_contract.json"
    dest_vision.parent.mkdir(parents=True, exist_ok=True)
    
    shutil.copyfile(src_path, dest_vision)
    print(f"Successfully synced contract (Hash: {actual_hash}) to:")
    print(f"  - {dest_vision}")

if __name__ == "__main__":
    main()
