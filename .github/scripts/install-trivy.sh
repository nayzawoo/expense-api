#!/usr/bin/env bash
set -euo pipefail

# Release archive digest from the upstream v0.76.0 release metadata.
version=0.76.0
digest=e24b1c51293c1b15e4ba8d45bbc7891ba053f90278ec2698f3562c993e145fe7
destination="${1:?destination directory required}"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --connect-timeout 15 --max-time 180 \
  "https://github.com/aquasecurity/trivy/releases/download/v${version}/trivy_${version}_Linux-64bit.tar.gz" \
  --output "$temporary/trivy.tar.gz"
printf '%s  %s\n' "$digest" "$temporary/trivy.tar.gz" | sha256sum --check --strict
mkdir -p "$destination"
tar -xzf "$temporary/trivy.tar.gz" -C "$destination" trivy
"$destination/trivy" --version
"$destination/trivy" --version | head -n 1 | grep -Fx "Version: $version"
