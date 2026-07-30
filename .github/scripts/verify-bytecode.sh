#!/usr/bin/env sh
#
# Verifies that every class the plugin ships is Java 21 bytecode or older.
#
# Only maven.compiler.release holds this in place. Raise it and the jar still builds and tests
# clean, then fails to load with UnsupportedClassVersionError on a 1.21.4 server.

set -eu

JAR="${1:?usage: verify-bytecode.sh <jar> [max-major]}"
MAX_MAJOR="${2:-65}" # 65 is Java 21

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

unzip -q "$JAR" -d "$WORK" -x 'META-INF/*'

failures=0
checked=0

for class in $(find "$WORK" -name '*.class'); do
    # Major version is a big-endian short at offset 6, after the magic number and minor.
    major=$(od -An -tu1 -j6 -N2 "$class" | awk '{print $1 * 256 + $2}')
    checked=$((checked + 1))
    if [ "$major" -gt "$MAX_MAJOR" ]; then
        echo "  $(echo "$class" | sed "s#$WORK/##") is major $major"
        failures=$((failures + 1))
    fi
done

if [ "$checked" -eq 0 ]; then
    echo "no classes found in $JAR; the jar is not what it should be"
    exit 1
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures of $checked classes are newer than major $MAX_MAJOR."
    echo "The plugin would fail to load on the oldest supported server."
    exit 1
fi

echo "$checked classes, all major $MAX_MAJOR or older"
