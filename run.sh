#!/usr/bin/env bash
# DTRExp Java — compile everything and run the full test suite.
# Zero runtime dependencies; javac only. Coverage tooling (JaCoCo) is optional
# and lives in the gitignored _tools/ directory.
#
#   ./run.sh          compile, run the conformance vectors + unit tests
#   ./run.sh cover    the same under JaCoCo; writes _tools/cov.csv and
#                     _tools/cov-html/, and fails unless coverage is 100%
#
# Exits nonzero on any compile warning/error or test failure.
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-test}"

rm -rf out
mkdir -p out
# shellcheck disable=SC2046
javac --release 17 -Xlint:all -Werror -d out $(find src test -name '*.java')

if [ "$MODE" != "cover" ]; then
    java -cp out io.onury.dtrexp.ConformanceRunner test/resources/vectors.json
    java -cp out io.onury.dtrexp.UnitTests
    exit 0
fi

# ---------------------------------------------------------------- coverage
TOOLS=_tools
AGENT="$TOOLS/jacocoagent.jar"
CLI="$TOOLS/jacococli.jar"
if [ ! -f "$AGENT" ] || [ ! -f "$CLI" ]; then
    echo "coverage tools missing — expected $AGENT and $CLI" >&2
    echo "fetch org.jacoco 0.8.13 (agent runtime + cli nodeps) into $TOOLS/, e.g.:" >&2
    echo "  curl -sSL -o $AGENT https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.13/org.jacoco.agent-0.8.13-runtime.jar" >&2
    echo "  curl -sSL -o $CLI   https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/0.8.13/org.jacoco.cli-0.8.13-nodeps.jar" >&2
    exit 1
fi

# Measure the shipped classes only — exclude the test harness (same package).
rm -rf "$TOOLS/prod-classes"
mkdir -p "$TOOLS/prod-classes/io/onury/dtrexp"
cp out/io/onury/dtrexp/*.class "$TOOLS/prod-classes/io/onury/dtrexp/"
rm -f "$TOOLS"/prod-classes/io/onury/dtrexp/ConformanceRunner.class \
      "$TOOLS"/prod-classes/io/onury/dtrexp/Json.class \
      "$TOOLS"/prod-classes/io/onury/dtrexp/UnitTests.class

# JaCoCo cannot instrument the JDK-26 platform classes it force-loads while
# formatting output; exclude them so the run stays clean (our classes are
# --release 17 bytecode and instrument fine).
EXCL='excludes=sun.*:jdk.*:com.sun.*'
java "-javaagent:$AGENT=destfile=$TOOLS/jacoco-conf.exec,$EXCL" \
    -cp out io.onury.dtrexp.ConformanceRunner test/resources/vectors.json
java "-javaagent:$AGENT=destfile=$TOOLS/jacoco-unit.exec,$EXCL" \
    -cp out io.onury.dtrexp.UnitTests

java -jar "$CLI" report "$TOOLS/jacoco-conf.exec" "$TOOLS/jacoco-unit.exec" \
    --classfiles "$TOOLS/prod-classes" --sourcefiles src \
    --csv "$TOOLS/cov.csv" --html "$TOOLS/cov-html" >/dev/null

echo
awk -F, 'NR>1 {bm+=$6; bc+=$7; lm+=$8; lc+=$9}
    END {
        printf "coverage: lines %d/%d, branches %d/%d\n", lc, lc + lm, bc, bc + bm;
        if (lm > 0 || bm > 0) { print "FAIL: coverage is not 100%"; exit 1 }
        print "OK: 100% line + branch coverage"
    }' "$TOOLS/cov.csv"
