#!/usr/bin/env python3
"""Run pinned Trivy and reject missing coverage, malformed reports and severe findings."""
import json
import os
from pathlib import Path
import subprocess
import sys

SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}


def assess(report, kind):
    if not isinstance(report, dict) or report.get("SchemaVersion") != 2:
        raise ValueError("Unsupported or missing report schema")
    results = report.get("Results")
    if not isinstance(results, list) or not results:
        raise ValueError("Scanner produced no package results")
    classes = set()
    findings = []
    packages = 0
    for result in results:
        if not isinstance(result, dict):
            raise ValueError("Invalid result")
        entries = result.get("Packages", [])
        if not isinstance(entries, list):
            raise ValueError("Invalid package inventory")
        if entries:
            classes.add(result.get("Class"))
            packages += len(entries)
        vulnerabilities = result.get("Vulnerabilities")
        if vulnerabilities is None:
            vulnerabilities = []
        if not isinstance(vulnerabilities, list):
            raise ValueError("Invalid vulnerability inventory")
        for item in vulnerabilities:
            if not isinstance(item, dict) or item.get("Severity") not in SEVERITIES:
                raise ValueError("Invalid vulnerability severity")
            for key in ("VulnerabilityID", "PkgName", "InstalledVersion"):
                if not isinstance(item.get(key), str) or not item[key]:
                    raise ValueError("Incomplete vulnerability identity")
            findings.append(item)
    required = {"lang-pkgs"} if kind == "dependencies" else {"lang-pkgs", "os-pkgs"}
    if not required <= classes:
        raise ValueError("Missing expected Java or OS package coverage")
    blocked = [item for item in findings if item["Severity"] in {"HIGH", "CRITICAL"}]
    print(f"{kind}: {packages} packages; {len(findings)} findings; {len(blocked)} blocking")
    for item in findings:
        # Emit only package identifiers, versions and vulnerability IDs, not raw payloads.
        print(json.dumps({key: item.get(key, "") for key in (
            "Severity", "VulnerabilityID", "PkgName", "InstalledVersion", "FixedVersion")}, sort_keys=True))
    return bool(blocked)


def scan(binary, kind, target, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    # Do not inherit configurable suppression, DB-skip or scanner selection from the environment.
    environment = {key: value for key, value in os.environ.items() if not key.startswith("TRIVY_")}
    # rootfs enables archive/JAR analysis; filesystem mode intentionally excludes JARs.
    command = [binary, "rootfs" if kind == "dependencies" else "image",
               "--config", "", "--ignorefile", "", "--scanners", "vuln",
               "--pkg-types", "library" if kind == "dependencies" else "os,library",
               "--list-all-pkgs", "--severity", ",".join(sorted(SEVERITIES)),
               "--format", "json", "--output", str(output), "--exit-code", "0",
               "--timeout", "10m", "--no-progress", "--disable-telemetry"]
    if kind == "image":
        command += ["--image-src", "docker"]
    command.append(target)
    # Exit 0 requests a full report; policy enforcement happens below, never ignoring tool errors.
    subprocess.run(command, check=True, env=environment, timeout=660)
    return assess(json.loads(output.read_text(encoding="utf-8")), kind)


if __name__ == "__main__":
    if len(sys.argv) != 5 or sys.argv[2] not in {"dependencies", "image"}:
        raise SystemExit("Usage: security-scan.py TRIVY dependencies|image TARGET REPORT")
    try:
        blocked = scan(*sys.argv[1:])
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Security scan failed: {error}", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(1 if blocked else 0)
