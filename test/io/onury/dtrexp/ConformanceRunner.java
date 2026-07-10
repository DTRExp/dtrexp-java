package io.onury.dtrexp;

// Conformance runner for the DTRExp draft-2.8 vectors (spec section 12).
// Compile everything and run the full suite with ONE command (from the repo root):
//
//   javac -Xlint:all -Werror -d out $(find src test -name '*.java') && java -cp out io.onury.dtrexp.ConformanceRunner test/resources/vectors.json
//
// Exits nonzero on any failure. See also ./run.sh.

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConformanceRunner {

    private int passed;
    private int failed;

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "test/resources/vectors.json");
        Map<?, ?> root = (Map<?, ?>) Json.parse(Files.readString(file));
        ConformanceRunner r = new ConformanceRunner();

        int[] coverage = r.runCoverage((List<?>) root.get("coverage"));
        int[] invalid = r.runInvalid((List<?>) root.get("invalid"));
        int[] warnings = r.runWarnings((List<?>) root.get("warnings"));
        int[] quiet = r.runQuiet((List<?>) root.get("quiet"));

        System.out.println();
        System.out.printf("coverage: %d/%d cases (%d groups)%n", coverage[0], coverage[0] + coverage[1], coverage[2]);
        System.out.printf("invalid:  %d/%d%n", invalid[0], invalid[0] + invalid[1]);
        System.out.printf("warnings: %d/%d%n", warnings[0], warnings[0] + warnings[1]);
        System.out.printf("quiet:    %d/%d%n", quiet[0], quiet[0] + quiet[1]);
        System.out.printf("TOTAL:    %d passed, %d failed%n", r.passed, r.failed);
        if (r.failed > 0) {
            System.exit(1);
        }
    }

    private int[] runCoverage(List<?> groups) {
        int ok = 0;
        int bad = 0;
        for (Object g : groups) {
            Map<?, ?> group = (Map<?, ?>) g;
            String id = (String) group.get("id");
            String expression = (String) group.get("expression");
            String tz = (String) group.get("tz");
            Map<?, ?> cases = (Map<?, ?>) group.get("cases");
            DtrExp exp;
            try {
                exp = DtrExp.parse(expression);
            } catch (DtrExpParseException e) {
                bad += cases.size();
                fail("coverage/" + id, expression + " failed to parse: " + e.getMessage());
                continue;
            }
            for (Map.Entry<?, ?> c : cases.entrySet()) {
                Instant instant = Instant.parse((String) c.getKey());
                boolean expected = (Boolean) c.getValue();
                boolean actual;
                try {
                    actual = exp.covers(instant, tz);
                } catch (RuntimeException e) {
                    bad++;
                    fail("coverage/" + id, expression + " @ " + c.getKey() + " threw " + e);
                    continue;
                }
                if (actual == expected) {
                    ok++;
                } else {
                    bad++;
                    fail("coverage/" + id, expression + " @ " + c.getKey() + " [" + tz + "] expected "
                            + expected + ", got " + actual);
                }
            }
        }
        passed += ok;
        failed += bad;
        return new int[] {ok, bad, groups.size()};
    }

    private int[] runInvalid(List<?> entries) {
        int ok = 0;
        int bad = 0;
        for (Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String expression = (String) entry.get("expression");
            ValidationResult v = DtrExp.validate(expression);
            if (!v.valid()) {
                ok++;
            } else {
                bad++;
                fail("invalid", "\"" + expression + "\" accepted — must be rejected ("
                        + entry.get("reason") + ")");
            }
        }
        passed += ok;
        failed += bad;
        return new int[] {ok, bad};
    }

    private int[] runWarnings(List<?> entries) {
        int ok = 0;
        int bad = 0;
        for (Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String expression = (String) entry.get("expression");
            ValidationResult v = DtrExp.validate(expression);
            if (!v.valid()) {
                bad++;
                fail("warnings", "\"" + expression + "\" rejected — must be accepted with a warning: "
                        + v.errors().get(0).getMessage());
            } else if (v.warnings().isEmpty()) {
                bad++;
                fail("warnings", "\"" + expression + "\" produced no warning (" + entry.get("warning") + ")");
            } else {
                ok++;
            }
        }
        passed += ok;
        failed += bad;
        return new int[] {ok, bad};
    }

    private int[] runQuiet(List<?> entries) {
        int ok = 0;
        int bad = 0;
        for (Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String expression = (String) entry.get("expression");
            ValidationResult v = DtrExp.validate(expression);
            if (!v.valid()) {
                bad++;
                fail("quiet", "\"" + expression + "\" rejected — must parse cleanly: " + v.errors().get(0).getMessage());
            } else if (!v.warnings().isEmpty()) {
                bad++;
                fail("quiet", "\"" + expression + "\" warned but must be quiet: " + v.warnings());
            } else {
                ok++;
            }
        }
        passed += ok;
        failed += bad;
        return new int[] {ok, bad};
    }

    private void fail(String section, String message) {
        System.out.println("FAIL [" + section + "] " + message);
    }
}
