import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


rokid = module("rokid")
public_tree = module("check_public_tree")


class OfflineOtaTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def fixture(self, bad_metadata=False, compression=zipfile.ZIP_STORED):
        manifest = b"fixture-manifest"
        header = b"CrAU" + struct.pack(">QQI", 2, len(manifest), 4)
        metadata = header + manifest
        payload = metadata + b"sign" + b"fixture-data"
        properties = {
            "FILE_SIZE": str(len(payload)),
            "FILE_HASH": base64.b64encode(hashlib.sha256(payload).digest()).decode(),
            "METADATA_SIZE": str(len(metadata)),
            "METADATA_HASH": base64.b64encode(hashlib.sha256(b"bad" if bad_metadata else metadata).digest()).decode(),
        }
        archive = self.root / "fixture.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("META-INF/com/android/metadata", "post-build=fixture\npre-device=glasses\nota-type=AB\n")
            output.writestr("payload_properties.txt", "".join(f"{k}={v}\n" for k, v in properties.items()))
            # Extra bytes prove the offset is derived, not a production constant.
            info = zipfile.ZipInfo("payload.bin")
            info.extra = struct.pack("<HH", 0xCAFE, 5) + b"extra"
            info.compress_type = compression
            output.writestr(info, payload)
        profile = {"id": "fixture", "ota": {
            "bytes": archive.stat().st_size, "sha256": rokid.digest_file(archive),
            "post_build": "fixture", "pre_device": "glasses", "ota_type": "AB",
        }}
        return archive, profile, payload

    def test_verified_ranges_extraction_and_no_false_signature_claim(self):
        archive, profile, payload = self.fixture()
        result = rokid.inspect_ota(archive, profile, output=self.root / "out")
        with archive.open("rb") as stream:
            stream.seek(result["payload_offset"])
            self.assertEqual(stream.read(result["payload_size"]), payload)
        self.assertEqual((self.root / "out/metadata.bin").read_bytes(), payload[:result["payload_metadata_bytes"]])
        self.assertTrue(result["hash_checks_passed"])
        self.assertFalse(result["wholefile_signature_verified_with_pinned_device_certificate"])
        self.assertFalse(result["native_device_applicability_verified"])
        self.assertFalse(result["device_updated"])

    def test_changed_archive_rejected_before_output(self):
        archive, profile, _ = self.fixture()
        data = bytearray(archive.read_bytes())
        data[40] ^= 1
        archive.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            rokid.inspect_ota(archive, profile, output=self.root / "out")
        self.assertFalse((self.root / "out").exists())

    def test_internally_invalid_metadata_rejected_even_if_outer_hash_matches(self):
        archive, profile, _ = self.fixture(bad_metadata=True)
        with self.assertRaisesRegex(ValueError, "Metadata SHA-256"):
            rokid.inspect_ota(archive, profile)

    def test_compressed_payload_rejected(self):
        archive, profile, _ = self.fixture(compression=zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(ValueError, "stored"):
            rokid.inspect_ota(archive, profile)

    def test_unknown_target_metadata_rejected(self):
        archive, profile, _ = self.fixture()
        profile["ota"]["pre_device"] = "different-hardware"
        with self.assertRaisesRegex(ValueError, "pre-device"):
            rokid.inspect_ota(archive, profile)

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL unavailable")
    def test_wholefile_signature_accepts_only_pinned_external_certificate(self):
        certificate = self.root / "cert.pem"
        key = self.root / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=Disposable fixture", "-keyout", str(key), "-out", str(certificate)],
                       check=True, capture_output=True)
        der = subprocess.run(["openssl", "x509", "-in", str(certificate), "-outform", "DER"],
                             check=True, capture_output=True).stdout
        fingerprint = hashlib.sha256(der).hexdigest()
        archive, _, _ = self.fixture()
        signed_bytes = archive.read_bytes()[:-2]
        signature = subprocess.run(["openssl", "cms", "-sign", "-binary", "-in", "/dev/stdin",
                                    "-signer", str(certificate), "-inkey", str(key), "-outform", "DER"],
                                   input=signed_bytes, capture_output=True, check=True).stdout
        comment_size = len(signature) + 6
        footer = struct.pack("<H2sH", comment_size, b"\xff\xff", comment_size)
        archive.write_bytes(signed_bytes + struct.pack("<H", comment_size) + signature + footer)
        self.assertTrue(rokid.verify_wholefile(archive, certificate, fingerprint))
        with self.assertRaisesRegex(ValueError, "trust anchor"):
            rokid.verify_wholefile(archive, certificate, "0" * 64)
        changed = bytearray(archive.read_bytes())
        changed[40] ^= 1
        archive.write_bytes(changed)
        with self.assertRaisesRegex(ValueError, "signature rejected"):
            rokid.verify_wholefile(archive, certificate, fingerprint)


class QualificationTests(unittest.TestCase):
    def test_unknown_firmware_does_not_get_tested_source_status(self):
        profile = json.loads(rokid.DEFAULT_PROFILE.read_text())
        properties = dict(profile["device"])
        properties.update({k: v for k, v in profile["apply_prerequisites"].items() if k.startswith("ro.")})
        properties["ro.build.fingerprint"] = "unknown"
        result = rokid.qualify(properties, profile)
        self.assertTrue(result["device_fields_match"])
        self.assertTrue(result["tested_boot_state"])
        self.assertFalse(result["tested_source_fingerprint"])
        self.assertFalse(result["installation_authorized_or_guaranteed"])

    def test_doctor_requires_explicit_target_without_invoking_adb(self):
        result = subprocess.run([sys.executable, str(ROOT / "tools/rokid.py"), "doctor"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"--serial", result.stderr)

    def test_doctor_only_runs_allowlisted_reads_and_omits_target(self):
        profile = json.loads(rokid.DEFAULT_PROFILE.read_text())
        args = type("Args", (), {"serial": "private-target", "adb": "adb", "adb_server_port": None})()
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            value = "2000\n" if command[-2:] == ["id", "-u"] else profile["device"].get(command[-1], "")
            return subprocess.CompletedProcess(command, 0, value, "")

        with mock.patch.object(rokid.subprocess, "run", side_effect=run):
            report = rokid.doctor(args, profile)
        self.assertNotIn("private-target", json.dumps(report))
        self.assertNotIn("ro.serialno", json.dumps(calls))
        for command in calls:
            self.assertEqual(command[:4], ["adb", "-s", "private-target", "shell"])
            self.assertIn(command[4], ["getprop", "id"])

    def test_privacy_patterns_identify_sensitive_values(self):
        address = ".".join(["192", "168", "8", "9"])
        home_path = "/".join(["", "Users", "fixture-person", "work"])
        self.assertIn("private LAN address", public_tree.inspect_text(address))
        self.assertIn("personal macOS path", public_tree.inspect_text(home_path))
        self.assertFalse(public_tree.inspect_text("127.0.0.1 GLASSES_IP:5555"))


if __name__ == "__main__":
    unittest.main()
