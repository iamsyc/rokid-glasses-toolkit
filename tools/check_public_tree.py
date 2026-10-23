#!/usr/bin/env python3
"""Check public artifacts without printing matched private values."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP = {".git", "__pycache__"}
BINARY_ALLOWLIST = {
    "releases/RokidAdbHelper-v1.7.apk",
    "docs/images/helper.png", "docs/images/settings-before.png", "docs/images/settings-after.png",
}
FORBIDDEN_SUFFIXES = {".p12", ".jks", ".keystore", ".key", ".img", ".zip"}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "personal macOS path": re.compile(r"/Users/[A-Za-z0-9._ -]+/"),
    "private LAN address": re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b"),
    "signed cloud URL": re.compile(r"https?://[^\s]+[?&](?:Signature|OSSAccessKeyId|X-Amz-Signature|x-oss-signature)="),
    "device serial-shaped value": re.compile(r"(?<![a-fA-F0-9])\d{16}(?![a-fA-F0-9])"),
}


def inspect_text(text):
    return [label for label, pattern in PATTERNS.items() if pattern.search(text)]


def main():
    failures = []
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if any(part in SKIP for part in relative.parts):
            continue
        if relative.parts[0] in {"work", "local"}:
            continue
        if len(relative.parts) > 1 and relative.parts[:2] in (("app", "build"), ("app", "dist")):
            continue
        if path.is_symlink():
            failures.append((relative, "symlink outside review boundary"))
            continue
        if not path.is_file():
            continue
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name.startswith("adbkey"):
            failures.append((relative, "private or vendor artifact type"))
            continue
        if relative.as_posix() in BINARY_ALLOWLIST:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeError, OSError):
            failures.append((relative, "unreviewed binary"))
            continue
        failures.extend((relative, reason) for reason in inspect_text(text))
    for path, reason in failures:
        print(f"{path}: {reason}")
    print(f"Public tree check: {'FAIL' if failures else 'PASS'}")
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
