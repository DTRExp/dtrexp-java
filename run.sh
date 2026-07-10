#!/usr/bin/env bash
# DTRExp Java — compile everything and run the full conformance suite.
# Exits nonzero on any compile warning/error or vector failure.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
# shellcheck disable=SC2046
javac -Xlint:all -Werror -d out $(find src test -name '*.java')
java -cp out io.onury.dtrexp.ConformanceRunner test/resources/vectors.json
