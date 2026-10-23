#!/usr/bin/env python3
"""Read-only device qualification and offline inspection of the pinned OEM OTA."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROFILE = ROOT / "profiles/rg-glasses-glass15-android12.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest_file(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def key_values(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def read_small(archive, name, limit=65536):
    require(archive.getinfo(name).file_size <= limit, "Oversized metadata entry")
    return archive.read(name)


def payload_offset(path, info):
    require(info.compress_type == zipfile.ZIP_STORED, "payload.bin must be stored, not compressed")
    require(not info.flag_bits & 1, "Encrypted payload is unsupported")
    with Path(path).open("rb") as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
        require(len(header) == 30 and header[:4] == b"PK\x03\x04", "Invalid local ZIP header")
        name_size, extra_size = struct.unpack_from("<HH", header, 26)
        name = stream.read(name_size)
        require(name == b"payload.bin", "Local payload filename mismatch")
        offset = info.header_offset + 30 + name_size + extra_size
        require(offset + info.file_size <= Path(path).stat().st_size, "Payload exceeds archive")
        return offset


def verify_wholefile(path, certificate, expected_certificate_sha256):
    """Use ONLY the externally supplied, pinned device certificate as signer."""
    pem = Path(certificate).read_bytes()
    require(pem.count(b"-----BEGIN CERTIFICATE-----") == 1, "Supply one device certificate")
    der = subprocess.run(["openssl", "x509", "-in", str(certificate), "-outform", "DER"],
                         capture_output=True, check=True).stdout
    require(hashlib.sha256(der).hexdigest() == expected_certificate_sha256,
            "Device OTA certificate does not match tested trust anchor")
    size = Path(path).stat().st_size
    with Path(path).open("rb") as stream:
        stream.seek(-6, 2)
        signature_start, marker, comment_size = struct.unpack("<H2sH", stream.read(6))
        require(marker == b"\xff\xff" and 6 < signature_start <= comment_size,
                "Missing Android OTA signature footer")
        require(comment_size + 22 <= size, "Invalid ZIP signature comment")
        stream.seek(-(comment_size + 22), 2)
        eocd = stream.read(comment_size + 22)
        require(eocd[:4] == b"PK\x05\x06", "Invalid end-of-central-directory")
        require(struct.unpack_from("<H", eocd, 20)[0] == comment_size, "ZIP comment mismatch")
        require(b"PK\x05\x06" not in eocd[4:], "Ambiguous end-of-central-directory")
        stream.seek(-signature_start, 2)
        signature = stream.read(signature_start - 6)
    signed_length = size - comment_size - 2
    with tempfile.TemporaryDirectory() as temporary:
        signature_path = Path(temporary) / "signature.der"
        signature_path.write_bytes(signature)
        with tempfile.TemporaryFile() as diagnostic:
            process = subprocess.Popen([
                "openssl", "cms", "-verify", "-binary", "-inform", "DER",
                "-in", str(signature_path), "-content", "/dev/stdin",
                "-certfile", str(certificate), "-nointern", "-noverify", "-out", os.devnull,
            ], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=diagnostic)
            try:
                with Path(path).open("rb") as stream:
                    remaining = signed_length
                    while remaining:
                        block = stream.read(min(1024 * 1024, remaining))
                        require(bool(block), "Truncated signed archive")
                        process.stdin.write(block)
                        remaining -= len(block)
            except BrokenPipeError:
                pass
            finally:
                process.stdin.close()
            require(process.wait() == 0, "Whole-file CMS signature rejected")
    return True


def inspect_ota(path, profile, certificate=None, output=None):
    path = Path(path)
    pinned = profile["ota"]
    require(path.stat().st_size == pinned["bytes"], "OTA byte count differs from tested artifact")
    require(digest_file(path) == pinned["sha256"], "OTA SHA-256 differs from tested artifact")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP members")
        metadata = key_values(read_small(archive, "META-INF/com/android/metadata").decode("utf-8"))
        for key, value in [("post-build", pinned["post_build"]),
                           ("pre-device", pinned["pre_device"]), ("ota-type", pinned["ota_type"])]:
            require(metadata.get(key) == value, "OTA metadata mismatch: " + key)
        properties_raw = read_small(archive, "payload_properties.txt")
        properties = key_values(properties_raw.decode("ascii"))
        info = archive.getinfo("payload.bin")
        offset = payload_offset(path, info)
        require(int(properties["FILE_SIZE"]) == info.file_size, "Payload size mismatch")
        with archive.open(info) as stream:
            header = stream.read(24)
            require(len(header) == 24 and header[:4] == b"CrAU", "Invalid payload header")
            version, manifest_size, signature_size = struct.unpack(">QQI", header[4:])
            require(version == 2, "Only payload version 2 is supported")
            manifest_end = 24 + manifest_size
            metadata_size = manifest_end + signature_size
            require(manifest_end == int(properties["METADATA_SIZE"]), "Metadata size mismatch")
            require(metadata_size <= min(info.file_size, 16 * 1024 * 1024), "Invalid metadata range")
            payload_metadata = header + stream.read(metadata_size - 24)
            require(len(payload_metadata) == metadata_size, "Truncated payload metadata")
            metadata_hash = base64.b64encode(hashlib.sha256(payload_metadata[:manifest_end]).digest()).decode()
            require(metadata_hash == properties["METADATA_HASH"], "Metadata SHA-256 mismatch")
            hasher = hashlib.sha256(payload_metadata)
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(block)
            require(base64.b64encode(hasher.digest()).decode() == properties["FILE_HASH"],
                    "Payload SHA-256 mismatch")
    signature_valid = False
    if certificate:
        signature_valid = verify_wholefile(path, certificate, pinned["trusted_ota_certificate_der_sha256"])
    report = {
        "schema": 1, "profile": profile["id"], "ota_sha256": pinned["sha256"],
        "bytes": path.stat().st_size, "payload_offset": offset,
        "payload_size": info.file_size, "payload_metadata_bytes": metadata_size,
        "post_build": metadata["post-build"], "hash_checks_passed": True,
        "wholefile_signature_verified_with_pinned_device_certificate": signature_valid,
        "native_device_applicability_verified": False, "device_updated": False,
    }
    if output:
        output = Path(output)
        output.mkdir(parents=True, exist_ok=True)
        (output / "metadata.bin").write_bytes(payload_metadata)
        (output / "payload_properties.txt").write_bytes(properties_raw)
        (output / "ota-inspection.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def qualify(properties, profile):
    matches = {key: properties.get(key) == value for key, value in profile["device"].items()}
    fingerprint = properties.get("ro.build.fingerprint", "")
    return {
        "device_fields_match": all(matches.values()), "field_checks": matches,
        "tested_source_fingerprint": fingerprint in profile["tested_source_fingerprints"],
        "tested_engineering_fingerprint": fingerprint == profile["ota"]["post_build"],
        "tested_boot_state": all(properties.get(k) == v for k, v in
                                 profile["apply_prerequisites"].items() if k.startswith("ro.")),
        "installation_authorized_or_guaranteed": False,
    }


def doctor(arguments, profile):
    require(bool(arguments.serial) and not arguments.serial.startswith("-"), "Explicit --serial required")
    base = [arguments.adb]
    if arguments.adb_server_port:
        base += ["-P", str(arguments.adb_server_port)]
    base += ["-s", arguments.serial]
    # Strict allowlist: do not collect serials, network addresses, SSIDs or full getprop/dumpsys.
    keys = list(profile["device"]) + ["ro.build.fingerprint", "ro.build.type",
        "ro.boot.verifiedbootstate", "ro.boot.vbmeta.device_state", "ro.boot.slot_suffix"]
    properties = {}
    for key in keys:
        result = subprocess.run(base + ["shell", "getprop", key], capture_output=True,
                                text=True, timeout=15)
        require(result.returncode == 0, "ADB read failed; check the explicit target locally")
        properties[key] = result.stdout.strip()
    result = subprocess.run(base + ["shell", "id", "-u"], capture_output=True,
                            text=True, timeout=15)
    require(result.returncode == 0, "Cannot read current ADB UID")
    require(result.stdout.strip().isdigit(), "Unexpected UID response")
    return {"schema": 1, "properties": properties, "current_adb_uid": int(result.stdout),
            "qualification": qualify(properties, profile), "read_only": True,
            "target_identifiers_included": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, default=DEFAULT_PROFILE)
    commands = parser.add_subparsers(dest="command", required=True)
    inspection = commands.add_parser("inspect-ota", help="Offline checks; never contacts glasses")
    inspection.add_argument("archive", type=Path)
    inspection.add_argument("--device-certificate", type=Path)
    inspection.add_argument("--output-dir", type=Path)
    diagnostic = commands.add_parser("doctor", help="Explicit-target, read-only qualification")
    diagnostic.add_argument("--serial", required=True)
    diagnostic.add_argument("--adb", default="adb")
    diagnostic.add_argument("--adb-server-port", type=int)
    arguments = parser.parse_args()
    profile = json.loads(arguments.profile.read_text())
    try:
        if arguments.command == "doctor":
            report = doctor(arguments, profile)
        else:
            report = inspect_ota(arguments.archive, profile, arguments.device_certificate, arguments.output_dir)
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        # Avoid dumping ADB command arguments, which may contain the user's address.
        print("Check failed: " + (str(error) if isinstance(error, (ValueError, KeyError))
                                 else type(error).__name__), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
