#!/usr/bin/env python3
"""Exercise the gate with synthetic reports and a failing scanner process."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("security_scan", Path(__file__).with_name("security-scan.py"))
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)


def report(severity=None):
    vulnerability = {"Severity": severity, "VulnerabilityID": "CVE-2000-0000",
                     "PkgName": "example:library", "InstalledVersion": "1.0"}
    return {"SchemaVersion": 2, "Results": [
        {"Class": "lang-pkgs", "Packages": [{"Name": "example:library"}],
         "Vulnerabilities": [vulnerability] if severity else []},
        {"Class": "os-pkgs", "Packages": [{"Name": "example-os"}]}]}


class PolicyTests(unittest.TestCase):
    def test_threshold_and_unfixed_findings(self):
        for severity in (None, "LOW", "MEDIUM", "UNKNOWN", "HIGH", "CRITICAL"):
            with self.subTest(severity=severity), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(SCAN.assess(report(severity), "image"), severity in {"HIGH", "CRITICAL"})

    def test_missing_or_malformed_coverage_blocks(self):
        for value in ({}, {"SchemaVersion": 2}, {"SchemaVersion": 2, "Results": []},
                      {"SchemaVersion": 2, "Results": [{"Class": "lang-pkgs", "Packages": []}]},
                      report("INVALID")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                SCAN.assess(value, "image")
        value = report()
        value["Results"].pop()
        with self.assertRaises(ValueError):
            SCAN.assess(value, "image")

    def test_scanner_error_and_missing_output_block(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "report.json"
            output.write_text(json.dumps(report()))
            # A stale clean report must not mask a failed scan.
            with self.assertRaises(SCAN.subprocess.CalledProcessError):
                SCAN.scan("/bin/false", "dependencies", temporary, str(output))
            self.assertFalse(output.exists())
            with self.assertRaises(FileNotFoundError):
                SCAN.scan("/bin/true", "dependencies", temporary, str(output))


if __name__ == "__main__":
    unittest.main()
