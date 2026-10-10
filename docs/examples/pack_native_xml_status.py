"""Package the adjacent, directly importable config without changing its resources.

Usage: python docs/examples/pack_native_xml_status.py /path/to/example.bundle.zip
Only Python's standard library is required. This does not validate velune semantics.
"""
import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source = Path(__file__).with_name("native-xml-status.config.json")
    config = json.loads(source.read_text(encoding="utf-8"))
    manifest = {
        "format": "companion.bundle.v1",
        "label": config["label"],
        "files": [{"path": "status.config.json", "kind": "config"}],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Refuse to overwrite an existing package accidentally.
    with ZipFile(args.output, "x", compression=ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        archive.writestr("status.config.json", json.dumps(config, ensure_ascii=False, indent=2))
    print(args.output.resolve())


if __name__ == "__main__":
    main()
