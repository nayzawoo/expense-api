#!/bin/sh
# Compile current sources and run selected PostgreSQL integration tests, without packaging.
set -eu

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: sh scripts/test-focused.sh 'ProfileIT'" >&2
    echo "Selectors also support 'ProfileIT#readsAndUpdatesDisplayName' or 'ProfileIT,AuthSessionIT'." >&2
    exit 2
fi

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
exec ./mvnw --batch-mode --no-transfer-progress \
    "-Dit.test=$1" -DfailIfNoTests=true -Dfailsafe.failIfNoSpecifiedTests=true \
    test-compile failsafe:integration-test failsafe:verify
