package io.onury.dtrexp;

// White-box + behavioral unit tests for everything the conformance vectors do
// not reach: the public accessor surface, every parse-error branch, and the
// calendar/cadence/static-analysis arms the vectors leave uncovered. No JUnit —
// same shape as ConformanceRunner (main + counters + System.exit).
//
//   java -cp out io.onury.dtrexp.UnitTests
//
// Exits nonzero on any failure. Wired into ./run.sh cover.

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class UnitTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        publicSurface();
        boundsEvaluation();
        cadenceEvaluation();
        ordinalScopes();
        parseErrors();
        timeParseErrors();
        dateLiteralErrors();
        cadenceParseErrors();
        strideAndDayDomain();
        staticWarnings();
        staticQuiet();
        staticCoverageCorners();
        mutationKills();
        whiteBox();

        System.out.printf("UNIT: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------- public surface

    private static void publicSurface() {
        DtrExp e = DtrExp.parse("T0900:1800 E1:5");
        check(e.source().equals("T0900:1800 E1:5"), "source() returns verbatim input");
        check(e.toString().equals("T0900:1800 E1:5"), "toString() returns the source");
        check(e.warnings().isEmpty(), "a clean expression exposes no warnings");

        DtrExp warned = DtrExp.parse("M2 D30");
        check(!warned.warnings().isEmpty(), "warnings() exposes the static finding");
        DtrExpWarning w = warned.warnings().get(0);
        check(w.toString().equals(w.message() + " (at " + w.position() + ")"),
                "DtrExpWarning.toString() renders message + position");

        // covers(Instant) — the UTC default overload
        DtrExp allDay = DtrExp.parse("T0000:2400");
        Instant noon = Instant.parse("2020-06-15T12:00:00Z");
        check(allDay.covers(noon), "covers(Instant) defaults to UTC and matches whole-day range");
        check(allDay.covers(noon, ZoneOffset.UTC) == allDay.covers(noon),
                "covers(Instant) agrees with covers(Instant, UTC)");
        // covers(Instant, ZoneId) overload agrees with the String overload;
        // June => Berlin is UTC+2, so 07:30Z is 09:30 local (hour 9).
        DtrExp morning = DtrExp.parse("H9");
        Instant t = Instant.parse("2020-06-15T07:30:00Z");
        check(morning.covers(t, ZoneId.of("Europe/Berlin")) == morning.covers(t, "Europe/Berlin"),
                "covers(Instant, ZoneId) agrees with covers(Instant, String)");
        check(morning.covers(t, ZoneId.of("Europe/Berlin")),
                "H9 covers 07:30Z in Berlin (09:30 local)");
        check(!morning.covers(t, ZoneOffset.UTC),
                "H9 does not cover 07:30Z in UTC (hour 7)");

        // validate() success path exposes warnings, failure path exposes the error
        ValidationResult ok = DtrExp.validate("M1");
        check(ok.valid() && ok.errors().isEmpty(), "validate() of a good expr is valid with no errors");
        ValidationResult bad = DtrExp.validate("Zx");
        check(!bad.valid() && bad.errors().size() == 1, "validate() of a bad expr carries exactly one error");
        check(bad.errors().get(0).position() >= 0, "the parse error exposes a character position");
    }

    // --------------------------------------------------------------- bounds

    private static void boundsEvaluation() {
        // open-start bounds: (null start, exclusive end)
        covers("*:20200201", "2020-01-15T00:00:00Z", "UTC", true);
        covers("*:20200201", "2020-02-15T00:00:00Z", "UTC", false);
        // open-end bounds: (inclusive start, null end)
        covers("20200201:*", "2020-03-01T00:00:00Z", "UTC", true);
        covers("20200201:*", "2020-01-01T00:00:00Z", "UTC", false);
        // closed bounds: before start / inside / after end
        covers("20200201:20200301", "2020-01-15T00:00:00Z", "UTC", false);
        covers("20200201:20200301", "2020-02-15T00:00:00Z", "UTC", true);
        covers("20200201:20200301", "2020-04-15T00:00:00Z", "UTC", false);
        // a single bare date literal is a one-day window
        covers("20200201", "2020-02-01T12:00:00Z", "UTC", true);
        covers("20200201", "2020-02-02T00:00:00Z", "UTC", false);
    }

    // -------------------------------------------------------------- cadence

    private static void cadenceEvaluation() {
        // calendar W-period cadence: every 2 weeks from Mon 2020-01-06, 1w window
        covers("20200106/2W", "2020-01-06T00:00:00Z", "UTC", true);
        covers("20200106/2W", "2020-01-15T00:00:00Z", "UTC", false); // in the skipped week
        covers("20200106/2W", "2020-01-20T00:00:00Z", "UTC", true);
        covers("20200106/2W", "2020-02-03T00:00:00Z", "UTC", true);  // k-1 window branch

        // explicit W period + W duration
        covers("20200106/3W/1W", "2020-01-06T00:00:00Z", "UTC", true);
        covers("20200106/3W/1W", "2020-01-13T00:00:00Z", "UTC", false);
        covers("20200106/3W/1W", "2020-01-27T00:00:00Z", "UTC", true);

        // calendar day period with a minute-sized duration (plus() 'm' arm)
        covers("20200101/2D/30m", "2020-01-01T00:15:00Z", "UTC", true);
        covers("20200101/2D/30m", "2020-01-01T01:00:00Z", "UTC", false);
        covers("20200101/2D/30m", "2020-01-03T00:15:00Z", "UTC", true);

        // absolute H-period cadence with a week-long duration (durUnit 'W' arm)
        covers("20200101/169H/1W", "2020-01-01T00:00:00Z", "UTC", true);
        covers("20200101/169H/1W", "2020-01-08T00:30:00Z", "UTC", false); // past the 168h window

        // year cadence: every 3 years from 2020, 1y window
        covers("20200101/3Y/1Y", "2020-06-01T00:00:00Z", "UTC", true);
        covers("20200101/3Y/1Y", "2021-06-01T00:00:00Z", "UTC", false);
        covers("20200101/3Y/1Y", "2023-06-01T00:00:00Z", "UTC", true);

        // before the anchor is never covered
        covers("20200106/2W", "2019-12-01T00:00:00Z", "UTC", false);

        // monthly 1-hour window off a day-29 anchor: the Feb clamp makes the
        // occurrence-index search run its full k+1 span (loop exhausts).
        covers("20200129/1M/1H", "2020-01-29T00:30:00Z", "UTC", true);
        covers("20200129/1M/1H", "2021-02-28T00:30:00Z", "UTC", true);  // clamped 29th
        covers("20200129/1M/1H", "2021-02-28T05:00:00Z", "UTC", false); // past the hour
    }

    // ------------------------------------------------------- ordinal scopes

    private static void ordinalScopes() {
        // E#n in quarter scope: first Monday of Q1 = first Monday of January
        covers("E1#1 Q1", "2020-01-06T12:00:00Z", "UTC", true);
        covers("E1#1 Q1", "2020-01-13T12:00:00Z", "UTC", false);
        // E#n in year scope: first Monday of the year
        covers("E1#1 Y2020", "2020-01-06T12:00:00Z", "UTC", true);
        covers("E1#1 Y2020", "2020-02-03T12:00:00Z", "UTC", false); // 5th Monday of the year
        // negative ordinal: last Monday of the month
        covers("E1#-1", "2020-06-29T12:00:00Z", "UTC", true);
        covers("E1#-1", "2020-06-22T12:00:00Z", "UTC", false);
    }

    // ---------------------------------------------------------- parse errors

    private static void parseErrors() {
        rejectsAt("M", 0);                 // designator without value (end of input)
        rejects("Y M1");                   // designator without value (space)
        rejects("Y,M1");                   // designator without value (comma)
        rejects("Y|M1");                   // designator without value (pipe)
        rejects("Zx");                     // unexpected character
        rejects("|M1");                    // empty leading branch
        rejects("M1|");                    // empty union branch
        rejects("");                       // empty expression
        rejects("   ");                    // blank expression
        rejects("M*,1");                   // bare '*' in a list
        rejects("M*/2");                   // anchorless stride via '*'
        rejects("E1,2#1");                 // ordinal after a list
        rejects("M1,2/3");                 // stride on a list
        rejects("M!1/2");                  // exclusion carrying a stride
        rejects("Y2025:2020");             // backwards year range
        rejects("M-");                     // missing value after '-'
        rejects("M12345678");              // integer too long (>7 digits)
        rejects("H-0");                    // negative zero out of domain
        rejects("E-8");                    // negative magnitude beyond the domain size
        rejects("E1:2#1");                 // ordinal on a range
        rejects("E1#");                    // missing ordinal after '#'
        rejects("Q1#1");                   // ordinal '#' only valid on E
        rejects("E1#6");                   // ordinal out of range
        rejects("E1#0");                   // ordinal zero
        rejects("E1#1,2");                 // ordinal cannot be a list
        rejects("M11:2/3");                // wrap range takes no stride
        rejects("M*:5/2");                 // anchorless stride (star endpoint)
        rejects("M-1/2");                  // end-relative stride start
        rejects("M1/1");                   // stride interval < 2
        rejects("M1/40");                  // stride interval exceeds domain
        rejects("M1/4/0");                 // stride duration < 1
        rejects("M1/4/4");                 // stride duration >= interval
        rejects("M1/");                    // missing stride interval
        rejects("M1/12345678");            // stride interval out of range
    }

    private static void timeParseErrors() {
        rejects("T!1");                    // T takes no exclusion
        rejects("T*");                     // T takes no '*'
        rejects("T0900:*");                // T range end takes no '*'
        rejects("T9");                     // 1-digit time value
        rejects("T0900#1");                // T takes no ordinal
        rejects("T0900/2");                // T takes no stride
        rejects("T090000.12");             // milliseconds fewer than 3 digits
        rejects("T090000.1234");           // milliseconds more than 3 digits
        rejects("T2400");                  // hour 24 only valid as a range end
        rejects("T0000:240000");           // hour 24 must be the 4-digit token
        rejects("T0000:2401");             // hour 24 must have mm == 00
        rejects("T2500");                  // hour out of range
        rejects("T0960");                  // minute out of range
        rejects("T120060");                // second out of range
        rejects("T0900:0900");             // equal-endpoint range covers nothing
        // valid boundary: 2400 as a range end is the end-of-day token
        covers("T0000:2400", "2020-01-01T23:59:00Z", "UTC", true);
        covers("T1800:2400", "2020-01-01T12:00:00Z", "UTC", false);
        // millisecond-precision single value
        covers("T090000.500", "2020-01-01T09:00:00.500Z", "UTC", true);
        covers("T090000.500", "2020-01-01T09:00:01.000Z", "UTC", false);
    }

    private static void dateLiteralErrors() {
        rejects("2020");                   // not an 8-digit date literal
        rejects("20200101:2020");          // bounds end not 8 digits
        rejects("20200101:");              // missing bounds end
        rejects("20200101:X");             // non-literal bounds end
        rejects("20201301");               // not a real calendar date (month 13)
        rejects("00000101");               // year out of domain (< 1)
        rejects("*2020");                  // stray '*'
        rejects("*:");                     // open-start bounds missing end
        rejects("*:*");                    // open-start bounds needs a literal
        rejects("*:X");                    // open-start bounds non-literal end
        rejects("20200101T25");            // malformed time part (too short after T)
        rejects("20200101T2500");          // hour out of range in date literal
        rejects("20200101T0060");          // minute out of range in date literal
        rejects("20200101T000060");        // second out of range in date literal
        rejects("20200301:20200201");      // backwards bounds range
        rejects("20200101 *:20200301");    // a second (open-start) bounds component
        // valid: date literal with a time part is a one-minute / one-second window
        covers("20200101T0900", "2020-01-01T09:00:30Z", "UTC", true);
        covers("20200101T0900", "2020-01-01T09:01:00Z", "UTC", false);
    }

    private static void cadenceParseErrors() {
        rejects("20200101/0D");            // zero cadence period
        rejects("20200101/2D/0H");         // zero cadence duration
        rejects("20200101/2D/1M");         // month duration needs month/year period
        rejects("20200101/2");             // unknown cadence unit at end of input
        rejects("20200101/2X");            // unknown cadence unit
        rejects("20200101//2D");           // missing cadence period
        rejects("20200101/1D/1D");         // duration not conservatively smaller
        // valid multi-part cadences that exercise unit tables
        accepts("20200101/3Y/1Y");
        accepts("20200106/3W/1W");
        accepts("20200101/2M/1D");
    }

    // ------------------------------------------- stride + deferred D domain

    private static void strideAndDayDomain() {
        rejects("D1/40 M1");               // D stride interval exceeds month scope
        rejects("D1:40 M1");               // D range end exceeds month scope
        rejects("D-40 M1");                // negative D magnitude exceeds domain
        // D scope widens with Q / Y present
        accepts("D40 Q1");                 // 40 <= 92 under quarter scope
        accepts("D92 Q1");
        accepts("D366 Y2020");
        accepts("D1:20/3 M1");             // ranged stride: explicit end validated
        accepts("D5:* M1");                // star end in a range
        accepts("D* M1");                  // D star: no per-item domain check
    }

    // ------------------------------------------------------- static warnings

    private static void staticWarnings() {
        warns("M2 D30");                   // Feb has at most 29 days
        warns("Y2021 W53");                // 2021 has 52 ISO weeks
        warns("Y2021 D366");               // 2021 has 365 days
        warns("Y2021 D366:*");             // same, as an open range
        warns("D-1:5 M1");                 // statically empty non-wrap range
        warns("M-2:2");                    // statically empty (Nov..Feb backwards, non-wrap)
        warns("M!1:12");                   // full-domain exclusion
        warns("E!1:7");                    // full-week exclusion
        warns("M1 Q3");                    // M and Q disjoint (Jan is in Q1)
        warns("M2 D31/3");                 // stride whose start exceeds every Feb size
        warns("M!1:12 Q1");                // empty M => M warns; Q check short-circuits
        warns("M1/6 Q2");                  // stride {1,7} disjoint from Q2 {4,5,6}
        warns("Q!1,2,3,4 D90");            // full-quarter exclusion warns; D falls back to {90,91,92}
        warns("M!1,2,3,4,5,6,7,8,9,10,11,12 D30"); // full-month exclusion warns; D falls back to {28..31}
        warns("M1 Q!1,2,3,4");             // empty Q => M∩Q check short-circuits, Q warns
        warns("M2 D30:31/2");              // stride start 30 exceeds every Feb size (re>=start)
        warns("M2 D30:31");                // plain range 30..31 exceeds Feb size
    }

    // ------------------------------------------------------- quiet (no warn)

    private static void staticQuiet() {
        quiet("M1 Q1");                    // Jan overlaps Q1 — not disjoint
        quiet("D30");                      // no month => 28..31 possible, 30 fits
        quiet("E1#5");                     // ordinal existence is not a static warning
        quiet("T0900:1800");               // T has no static satisfiability check
        quiet("M2/3");                     // satisfiable stride
        quiet("D5:*");                     // star-terminated range
        quiet("D*:5");                     // star-started range
        quiet("M!11:2");                   // wrap exclusion leaves values free
        quiet("M*:5 Q1");                  // star-started range in staticSet, overlaps Q1
        quiet("M2 D!-29");                 // excluding a non-existent day marks nothing
        quiet("M2 D!31");                  // excluding day 31 in Feb marks nothing
        quiet("M2 D-29:-29");            // day -29 = day 1 in a leap Feb (satisfiable), empty in a 28-day Feb
        quiet("Y*:2020 W1");               // star-started year range => concreteYears null
    }

    // -------------------------------------------- static-analysis corners

    private static void staticCoverageCorners() {
        // daySizes: quarter scope, each quarter length + leap-Q1
        quiet("Q1 D90");
        quiet("Q2 D91");
        quiet("Q3 D92");
        quiet("Q* D90");                   // staticSet star path
        // daySizes: month scope, every 30-day month arm + a 31-day arm in one go
        quiet("M1,4,6,9,11 D30");
        // daySizes: year scope, concrete leap vs common
        quiet("D300 Y2020");
        quiet("D300 Y2021");
        // W with concrete week-year
        quiet("W1 Y2020");
        quiet("W52 Y2021");
        // concreteYears: the several "stay quiet" exits
        quiet("Y2000/3 W1");               // open stride
        quiet("Y2000:9999/3 W1");          // bounded stride, span > 1000
        quiet("Y2000:2010/2 W1");          // narrow bounded stride: years enumerated
        quiet("Y2000:* W1");               // open range
        quiet("Y2000:9999 W1");            // range span > 1000
        quiet("Y1000:1500,2000:2600 W1");  // union > 1001 years
        quiet("Y* W1");                    // star Y
        quiet("Y!2020 W1");                // excluded Y
        quiet("Y2015 W53");                // 2015 is a 53-week ISO year
        // staticSet stride path (M stride feeding the M∩Q check)
        quiet("M1/3 Q1");                  // {1,4,7,10} overlaps Q1
        quiet("M2/3 Q2");                  // {2,5,8,11}: 5 lies in Q2 => overlaps
        // satisfiable() ordinal short-circuit reached through possibleSizes
        quiet("E1#2 M1");
    }

    // -------------------------------------------- mutation-kill tests
    // Each pins a boundary/relational/logical decision that a mutant would flip.
    // Grouped by the source they defend.

    private static void mutationKills() {
        // Parser -----------------------------------------------------------
        // designator-without-value must fire on a trailing SPACE (indexOf >= 0)
        rejectsMsg("Y M1", "without value");
        // wrap flag needs a strict start > end (not >=) and both endpoints
        covers("H5:5", "2020-01-01T05:30:00Z", "UTC", true);   // 5:5 is just hour 5
        covers("H5:5", "2020-01-01T10:00:00Z", "UTC", false);
        covers("H5:0", "2020-01-01T06:00:00Z", "UTC", true);   // 5:0 wraps midnight
        covers("H5:0", "2020-01-01T03:00:00Z", "UTC", false);
        covers("H5:0", "2020-01-01T00:30:00Z", "UTC", true);
        // a 7-digit year is diagnosed as a year-domain error (digit guard is > 7)
        rejectsMsg("Y1234567", "year out of domain");
        accepts("Y1");                     // year 1 is the inclusive minimum
        accepts("E-7");                    // -7 == domain size, still in range
        accepts("M1/12");                  // interval == domain size is allowed
        // single time values carry the precision-sized window (unit table)
        covers("T09", "2020-01-01T09:30:00Z", "UTC", true);    // 2-digit => 1h window
        covers("T09", "2020-01-01T10:30:00Z", "UTC", false);
        covers("T0900", "2020-01-01T09:00:30Z", "UTC", true);  // 4-digit => 1m window
        covers("T0900", "2020-01-01T09:02:00Z", "UTC", false);
        accepts("T23");                    // hour 23 is valid (guard is > 23)
        accepts("T0959");                  // minute 59 valid
        accepts("T095959");                // second 59 valid
        accepts("00010101");               // date-literal year 1 valid (guard is < 1)
        accepts("20200101T0959");          // date-literal minute 59 valid
        accepts("20200101T095959");        // date-literal second 59 valid
        rejects("20200101/100D/1M");       // month duration with a day period is rejected
        rejectsMsg("20200101/2", "<end>"); // missing cadence unit reports <end>
        rejects("D1:40/3 M1");             // an explicit stride end is domain-checked
        accepts("D1/31 M1");               // interval 31 == month scope, allowed
        accepts("20200101/1234567D");      // 7-digit cadence period is in range

        // Evaluator --------------------------------------------------------
        // H-period window is half-open: [0, durMs) within each period
        covers("20200101/2H/1H", "2020-01-01T00:30:00Z", "UTC", true);
        covers("20200101/2H/1H", "2020-01-01T01:00:00Z", "UTC", false); // exact edge, excluded
        covers("20200101/2H/1H", "2020-01-01T01:30:00Z", "UTC", false);
        covers("20200101/2H/1H", "2020-01-01T02:00:00Z", "UTC", true);

        // StaticChecks -----------------------------------------------------
        // month-length table: the 30-day months warn on D31; a 31-day month does not
        warns("M4 D31");
        warns("M6 D31");
        warns("M9 D31");
        warns("M11 D31");
        quiet("M1 D31");
        // stride satisfiability boundaries (start <= re, start <= size)
        quiet("M5:5/2");                   // start == resolved end
        quiet("M12/2");                    // start == domain size
        quiet("E!1:6");                    // sole free value is the max (loop <= size)
        quiet("H0:5");                     // resolve of a 0 endpoint (v < 0, not <= 0)
        warns("M*:5 Q3");                  // star-started range vs Q, mark()'s STAR arm
        warnCount("M!1:12 Q1", 1);         // empty M must NOT also raise a disjoint warning
        quiet("M12 Q*");                   // star staticSet must include the max quarter
        quiet("M3/3 Q4");                  // stride staticSet must include its last hit (12)
        warns("M3/6 Q2");                  // stride staticSet must NOT include remainder==duration
        // concrete-year enumeration boundaries (D-in-year scope, no W)
        warns("Y2001:2003/2 D366");        // open/narrow stride: years enumerated, all common
        warns("Y1001:2001/1000 D366");     // stride span == 1000 still enumerates
        quiet("Y2001:2004/3 D366");        // stride must include its last year (2004, leap)
        warns("Y2003:2007/4 D366");        // stride selects remainder < duration only
        warns("Y2001:2003 D366");          // range enumerated, all common years => D366 warns
        quiet("Y2001:2004 D366");          // range must include its last year (2004, leap)
    }

    // --------------------------------------------------------- white-box

    private static void whiteBox() {
        // domainMax('Y') is unreachable through parse/covers (Y short-circuits
        // earlier) but is part of the helper's total contract.
        check(Parser.domainMax('Y') == 9999, "domainMax('Y') == 9999");
        check(Parser.domainSize('E') == 7, "domainSize('E') == 7");
        check(Parser.domainMin('H') == 0, "domainMin('H') == 0");
    }

    // ----------------------------------------------------------- harness

    private static void covers(String expr, String instant, String tz, boolean expected) {
        try {
            boolean actual = DtrExp.parse(expr).covers(Instant.parse(instant), tz);
            check(actual == expected, "\"" + expr + "\" @ " + instant + " [" + tz + "] expected " + expected);
        } catch (RuntimeException e) {
            check(false, "\"" + expr + "\" @ " + instant + " threw " + e);
        }
    }

    private static void rejects(String expr) {
        check(!DtrExp.validate(expr).valid(), "\"" + expr + "\" must be rejected");
    }

    private static void rejectsAt(String expr, int pos) {
        ValidationResult v = DtrExp.validate(expr);
        check(!v.valid() && v.errors().get(0).position() == pos,
                "\"" + expr + "\" must be rejected at position " + pos);
    }

    private static void rejectsMsg(String expr, String needle) {
        ValidationResult v = DtrExp.validate(expr);
        check(!v.valid() && v.errors().get(0).getMessage().contains(needle),
                "\"" + expr + "\" must be rejected with a message containing \"" + needle + "\""
                        + (v.valid() ? " (accepted)" : ": " + v.errors().get(0).getMessage()));
    }

    private static void warnCount(String expr, int n) {
        ValidationResult v = DtrExp.validate(expr);
        check(v.valid() && v.warnings().size() == n,
                "\"" + expr + "\" must produce exactly " + n + " warning(s)"
                        + (v.valid() ? " (got " + v.warnings().size() + ": " + v.warnings() + ")"
                                : " (rejected)"));
    }

    private static void accepts(String expr) {
        ValidationResult v = DtrExp.validate(expr);
        check(v.valid(), "\"" + expr + "\" must be accepted"
                + (v.valid() ? "" : ": " + v.errors().get(0).getMessage()));
    }

    private static void warns(String expr) {
        ValidationResult v = DtrExp.validate(expr);
        check(v.valid() && !v.warnings().isEmpty(), "\"" + expr + "\" must warn");
    }

    private static void quiet(String expr) {
        ValidationResult v = DtrExp.validate(expr);
        check(v.valid() && v.warnings().isEmpty(),
                "\"" + expr + "\" must be quiet" + (v.valid() ? " (warned: " + v.warnings() + ")"
                        : " (rejected: " + v.errors().get(0).getMessage() + ")"));
    }

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + msg);
        }
    }
}
