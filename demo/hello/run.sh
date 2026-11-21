#!/bin/sh
# Assembles hello.s and runs it; builds the jar first if it's missing. Extra args go to `6502 run` (e.g. --monitor).
set -e
dir=$(dirname "$(readlink -f "$0")")
root="$dir/../.."
[ -f "$root/target/6502.jar" ] || mvn -q -B -f "$root/pom.xml" package -DskipTests
"$root/bin/6502" asm "$dir/hello.s"
exec "$root/bin/6502" run "$@" "$dir/hello.bin"
