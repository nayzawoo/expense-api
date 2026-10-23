#!/bin/sh
set -eu

# Keep source filenames relative to the caller, even when invoked outside this repository.
if [ "$#" -gt 0 ] && [ "$1" != "--help" ]; then
    case "$1" in
        /*) ;;
        *) import_file="$PWD/$1"; shift; set -- "$import_file" "$@" ;;
    esac
fi

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
./mvnw --batch-mode --no-transfer-progress -q -DskipTests compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.includeScope=runtime -Dmdep.outputFile=target/import-classpath.txt
exec java -cp "target/classes:$(cat target/import-classpath.txt)" \
    com.nayzawoo.expense.legacy.LegacyImportCommand "$@"
