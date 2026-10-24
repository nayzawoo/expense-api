#!/bin/sh
set -eu

cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
./mvnw --batch-mode --no-transfer-progress -q -DskipTests compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.includeScope=runtime -Dmdep.outputFile=target/reset-password-classpath.txt
exec java -cp "target/classes:$(cat target/reset-password-classpath.txt)" \
    com.nayzawoo.expense.identity.PasswordResetCommand "$@"
