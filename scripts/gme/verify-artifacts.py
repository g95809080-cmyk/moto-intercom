"""Check known local legacy credentials without ever printing their values."""
import argparse
import json
from pathlib import Path
import re
import zipfile


def legacy_credentials(path):
    result = []
    if not path.exists():
        return result
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.lstrip().startswith(("#", "!")):
            continue
        name, value = line.split("=", 1)
        if name.strip() not in {"gme.client.token", "gme.app.key"}:
            continue
        value = re.sub(r"\\u([0-9a-fA-F]{4})|\\(.)", lambda m: chr(int(m[1], 16)) if m[1] else
                       {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(m[2], m[2]), value.strip())
        if len(value) >= 16:
            result.append((name.strip(), value.encode("utf-8")))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", action="append", help="APK or ZIP; defaults to the Debug APK and Lambda ZIP")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    credentials = legacy_credentials(root / "local.properties")
    paths = [Path(p).resolve() for p in args.artifact] if args.artifact else [
        root / "app/build/outputs/apk/debug/app-debug.apk",
        root / "backend/gme-control-plane/.build/control-plane.zip",
    ]
    findings = []
    checked_sources = 0
    for folder in ["app/src/main", "backend/gme-control-plane/src"]:
        for source in (root / folder).rglob("*"):
            if not source.is_file():
                continue
            checked_sources += 1
            data = source.read_bytes()
            for name, value in credentials:
                if value in data:
                    findings.append({"file": str(source.relative_to(root)), "setting": name})
    for path in paths:
        if not path.is_file():
            findings.append({"artifact": str(path), "error": "MISSING_ARTIFACT"})
            continue
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                data = archive.read(entry)
                for name, value in credentials:
                    if value in data:
                        findings.append({"artifact": str(path), "entry": entry.filename, "setting": name})
    print(json.dumps({"status": "FAIL" if findings else "PASS", "scope": "known local legacy credentials",
                      "checkedArtifacts": len(paths), "checkedSourceFiles": checked_sources,
                      "findings": findings}, ensure_ascii=False))
    raise SystemExit(1 if findings else 0)


if __name__ == "__main__":
    main()
