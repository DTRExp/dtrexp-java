package io.onury.dtrexp;

// JUnit 5 entry point for PIT mutation testing — a thin shim over the two
// plain-main() harnesses, which stay the canonical way to run the suite. Only
// `./run.sh mutate` compiles this file (with the JUnit jars from _tools/pit/);
// the default build excludes it so the tree needs nothing beyond javac.

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PitSuite {

    @Test
    void conformanceVectors() throws Exception {
        assertEquals(0, ConformanceRunner.run(new String[] {"test/resources/vectors.json"}),
                "conformance vector failures");
    }

    @Test
    void unitSuite() {
        assertEquals(0, UnitTests.run(), "unit test failures");
    }
}
