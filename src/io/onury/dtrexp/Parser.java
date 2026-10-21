package io.onury.dtrexp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for DTRExp draft 3. Tokens match greedily and
 * never backtrack on semantic failure (spec section 8). Static validity rules
 * (domains, stride limits, one-designator-once, cadence/bounds arity) are
 * enforced here; static satisfiability warnings live in {@link StaticChecks}.
 *
 * <p>Every error is positioned at the first character of the token at fault
 * (spec section 8, Error positions). The grammar is ASCII and parsing stops at
 * the first character outside it, so every character before an error is one
 * UTF-16 unit and the index is also the code-point offset.</p>
 */
final class Parser {

    private static final String DESIGNATORS = "YQMWDETHms";
    private static final String CADENCE_UNITS = "YMWDHm";
    private static final long DAY_MS = 86_400_000L;

    private final String src;
    private int i;
    private final List<Ast.Expr> exprs = new ArrayList<>();
    private final List<DTRExpWarning> warnings = new ArrayList<>();
    // the branch's D values as {position, value} and its stride interval's
    // position: D's domain depends on a scope known only once the branch is read
    private final List<long[]> dayValues = new ArrayList<>();
    private int dayIntervalPos;
    // the earliest rule error the branch has found: a rule error does not
    // stop the branch, which reads on and ends with its earliest error, a
    // later grammar error included (spec section 8)
    private DTRExpParseException pending;

    private Parser(String src) {
        this.src = src;
    }

    record Parsed(List<Ast.Expr> exprs, List<DTRExpWarning> warnings) {
    }

    static Parsed parse(String src) {
        Parser p = new Parser(src);
        p.parseAll();
        return new Parsed(p.exprs, p.warnings);
    }

    // ------------------------------------------------------------------ top

    private void parseAll() {
        while (true) {
            parseExpression(!exprs.isEmpty());
            skipSpaces();
            if (i >= src.length()) {
                return;
            }
            i++; // '|' — parseExpression only stops at '|' or end of input
        }
    }

    private void parseExpression(boolean unionBranch) {
        dayValues.clear();
        skipSpaces();
        int exprStart = i;
        List<Ast.Selector> sels = new ArrayList<>();
        Ast.Cadence cadence = null;
        Ast.Bounds bounds = null;
        while (true) {
            skipSpaces();
            if (i >= src.length() || src.charAt(i) == '|') {
                break;
            }
            int pos = i;
            char c = src.charAt(i);
            if (isDigit(c)) {
                DateComponent dc = parseDateComponent();
                if (dc.cadence != null) {
                    if (cadence != null) {
                        rule(pos, "more than one cadence per expression");
                    }
                    cadence = dc.cadence;
                } else {
                    if (bounds != null) {
                        rule(pos, "more than one bounds component per expression");
                    }
                    bounds = dc.bounds;
                }
            } else if (c == '*') {
                if (bounds != null) {
                    rule(pos, "more than one bounds component per expression");
                }
                bounds = parseOpenStartBounds();
            } else if (DESIGNATORS.indexOf(c) >= 0) {
                Ast.Selector s = c == 'T' ? parseTimeSelector() : parseSelector();
                for (Ast.Selector prev : sels) {
                    if (prev.des == s.des) {
                        rule(pos, "duplicate designator '" + s.des + "' in one expression");
                    }
                }
                sels.add(s);
            } else {
                throw err(pos, "unexpected character '" + c + "'");
            }
        }
        if (sels.isEmpty() && cadence == null && bounds == null) {
            throw err(exprStart, unionBranch ? "empty union branch" : "empty expression");
        }
        Ast.Expr expr = new Ast.Expr(sels, cadence, bounds);
        // the branch is complete: D's domain is known now, and the earliest error wins
        validateDayScope(expr);
        if (pending != null) {
            throw pending;
        }
        StaticChecks.check(expr, warnings);
        exprs.add(expr);
    }

    // -------------------------------------------------------- int selectors

    private Ast.Selector parseSelector() {
        int pos = i;
        char des = src.charAt(i++);
        if (i >= src.length() || " ,|".indexOf(src.charAt(i)) >= 0) {
            throw err(i, "designator '" + des + "' without value");
        }
        char c = src.charAt(i);
        if (c == '!') {
            i++;
            return Ast.Selector.not(des, pos, parseIntList(des));
        }
        if (c == '*' && !(i + 1 < src.length() && src.charAt(i + 1) == ':')) {
            i++;
            if (peek() == '/') {
                return parseStride(des, pos, Ast.Item.single(Ast.STAR, 0));
            }
            if (peek() == ',') {
                throw err(i, "bare '*' in a list — the list is already the whole domain");
            }
            return Ast.Selector.all(des, pos);
        }
        Ast.Item first = parseIntItem(des);
        char n = peek();
        if (n == '#') {
            return parseOrdinal(des, pos, first);
        }
        if (n == '/') {
            return parseStride(des, pos, first);
        }
        List<Ast.Item> items = new ArrayList<>();
        items.add(first);
        while (peek() == ',') {
            i++;
            items.add(parseIntItem(des));
        }
        if (peek() == '/') {
            throw err(i, "stride not allowed on a list");
        }
        if (peek() == '#') {
            throw err(i, "ordinal takes a single value, never a list or range");
        }
        return Ast.Selector.list(des, pos, items);
    }

    private List<Ast.Item> parseIntList(char des) {
        List<Ast.Item> items = new ArrayList<>();
        items.add(parseIntItem(des));
        while (peek() == ',') {
            i++;
            items.add(parseIntItem(des));
        }
        if (peek() == '/') {
            throw err(i, "a component is either an exclusion or carries a stride — never both");
        }
        return items;
    }

    private Ast.Item parseIntItem(char des) {
        long start;
        boolean starStart = false;
        if (peek() == '*') {
            i++;
            if (peek() != ':') {
                throw err(i - 1, "bare '*' in a list — the list is already the whole domain");
            }
            start = Ast.STAR;
            starStart = true;
        } else {
            start = parseIntValue(des);
        }
        if (peek() != ':') {
            return Ast.Item.single(start, 0);
        }
        i++;
        int endPos = i;
        long end;
        boolean starEnd = false;
        if (peek() == '*') {
            i++;
            end = Ast.STAR;
            starEnd = true;
        } else {
            end = parseIntValue(des);
        }
        boolean literalBoth = !starStart && !starEnd && start >= 0 && end >= 0;
        boolean wrap = literalBoth && start > end;
        if (des == 'Y' && wrap) {
            rule(endPos, "backwards year range — years are absolute and non-cyclic, no wrap");
        }
        return Ast.Item.of(start, end, wrap);
    }

    /** Parses [-]integer and applies the symmetric parse-time domain check (D deferred). */
    private long parseIntValue(char des) {
        int pos = i;
        boolean neg = peek() == '-';
        if (neg) {
            i++;
        }
        if (digitRun(i) == 0) {
            // the integer is required past the sign, where it is missing
            throw err(i, "expected a value for '" + des + "'");
        }
        long v = readInteger(pos);
        if (des == 'Y') {
            if (neg) {
                rule(pos, "negative value on Y — no edge to count back from");
            } else if (v < 1 || v > 9999) {
                rule(pos, "year out of domain — Y takes 4-digit ISO years (1-9999)");
            }
            return v;
        }
        if (des == 'D') {
            // domain depends on scope; checked in validateDayScope after the
            // whole expression is read (components may appear in any order),
            // but '-0' is no value in any domain
            if (neg && v == 0) {
                rule(pos, "zero value on a 1-based domain");
            }
            long day = neg ? -v : v;
            dayValues.add(new long[] {pos, day});
            return day;
        }
        int min = domainMin(des);
        if (v == 0 && min == 1) {
            rule(pos, "zero value on a 1-based domain");
        }
        if (neg) {
            if (v == 0 || v > domainSize(des)) {
                rule(pos, "negative value out of domain (-" + domainSize(des) + "..-1)");
            }
            return -v;
        }
        if (v > domainMax(des)) {
            rule(pos, "value out of domain (" + min + "-" + domainMax(des) + ")");
        }
        return v;
    }

    private Ast.Selector parseOrdinal(char des, int pos, Ast.Item first) {
        int hashPos = i;
        i++; // '#'
        if (des != 'E') {
            throw err(hashPos, "ordinal '#' is only valid on E");
        }
        if (first.range) {
            throw err(hashPos, "ordinal takes a single value, never a range");
        }
        int countPos = i; // the count, its sign included
        boolean neg = peek() == '-';
        if (neg) {
            i++;
        }
        if (digitRun(i) == 0) {
            throw err(i, "expected an ordinal after '#'");
        }
        long n = readInteger(countPos);
        if (n == 0) {
            rule(countPos, "ordinal zero");
        }
        if (n > 5) {
            rule(countPos, "ordinal out of range (-5..-1, 1..5)");
        }
        if (peek() == ',') {
            throw err(i, "ordinal is single — union more via '|'");
        }
        return Ast.Selector.nth(des, pos, first.start, (int) (neg ? -n : n));
    }

    private Ast.Selector parseStride(char des, int pos, Ast.Item first) {
        int slashPos = i;
        long start = first.start;
        // a stride its range or start does not allow is an error at the '/'
        if (first.wrap) {
            rule(slashPos, "wrap ranges take no stride");
        } else if (start == Ast.STAR) {
            rule(slashPos, "anchorless stride — explicit start required");
        } else if (start < 0) {
            rule(slashPos, "stride start must be non-negative (end-relative anchors shift per parent instance)");
        }
        i++; // '/'
        int intervalPos = i;
        int interval = parseUint("stride interval");
        int duration = 1;
        int durationPos = i + 1;
        if (peek() == '/') {
            i++;
            duration = parseUint("stride duration");
        }
        if (interval < 2) {
            rule(intervalPos, "stride interval must be >= 2");
        }
        if (des != 'Y' && des != 'D' && interval > domainSize(des)) {
            rule(intervalPos, "stride interval exceeds parent domain — use a cadence");
        }
        if (duration < 1 || duration >= interval) {
            rule(durationPos, "stride duration must be 1 <= duration < interval");
        }
        dayIntervalPos = intervalPos;
        long end = first.range ? first.end : Ast.STAR; // default: domain edge
        return Ast.Selector.strided(des, pos, start, end, interval, duration);
    }

    // ---------------------------------------------------------- T selector

    private Ast.Selector parseTimeSelector() {
        int pos = i;
        i++; // 'T'
        if (peek() == '!') {
            throw err(i, "T takes no exclusion — write the complement explicitly");
        }
        if (peek() == '*') {
            throw err(i, "T takes no '*'");
        }
        List<Ast.Item> items = new ArrayList<>();
        while (true) {
            long[] a = parseTimeval(false);
            if (peek() == ':') {
                i++;
                if (peek() == '*') {
                    throw err(i, "T takes no '*'");
                }
                int endPos = i;
                long[] b = parseTimeval(true);
                if (a[0] == b[0]) {
                    rule(endPos, "T range with equal endpoints — half-open, would cover nothing");
                }
                items.add(Ast.Item.of(a[0], b[0], a[0] > b[0]));
            } else {
                items.add(Ast.Item.single(a[0], a[1]));
            }
            if (peek() != ',') {
                break;
            }
            i++;
        }
        if (peek() == '/') {
            throw err(i, "T takes no stride");
        }
        if (peek() == '#') {
            throw err(i, "T takes no ordinal");
        }
        return Ast.Selector.list('T', pos, items);
    }

    /** Returns {msOfDay, unitMs}. The value is the whole digit run: 2, 4 or 6 digits. */
    private long[] parseTimeval(boolean endPosition) {
        int pos = i;
        int take = digitRun(i);
        if (take == 0) {
            throw err(pos, "expected a time value");
        }
        if (take != 2 && take != 4 && take != 6) {
            rule(pos, "a time value is hh, hhmm or hhmmss");
            i = pos + take;
            return new long[] {0, 1_000L};
        }
        int hh = twoDigits(pos);
        int mm = take >= 4 ? twoDigits(pos + 2) : 0;
        int ss = take >= 6 ? twoDigits(pos + 4) : 0;
        i = pos + take;
        long ms = 0;
        long unit;
        boolean hasMillis = take == 6 && peek() == '.';
        if (hasMillis) {
            // the fraction is its own token, from its '.'
            int fracPos = i;
            i++;
            int fraction = digitRun(i);
            if (fraction == 3) {
                ms = Long.parseLong(src, i, i + 3, 10);
            } else {
                rule(fracPos, "milliseconds are exactly 3 digits");
            }
            i += fraction;
            unit = 1;
        } else {
            unit = take == 2 ? 3_600_000L : take == 4 ? 60_000L : 1_000L;
        }
        if (hh == 24) {
            // take == 4 already implies no millis (millis needs take == 6)
            if (endPosition && take == 4 && mm == 0) {
                return new long[] {DAY_MS, unit};
            }
            rule(pos, "hour 24 is the exact 4-digit token '2400', in range-end position only");
        } else if (hh > 23) {
            rule(pos, "hour out of range in time value");
        }
        if (mm > 59) {
            rule(pos, "minute out of range in time value");
        }
        if (ss > 59) {
            rule(pos, "second out of range in time value (leap seconds are not representable)");
        }
        return new long[] {hh * 3_600_000L + mm * 60_000L + ss * 1_000L + ms, unit};
    }

    // ------------------------------------------------- date literals & tails

    private record DateComponent(Ast.Cadence cadence, Ast.Bounds bounds) {
    }

    private record DateLiteral(LocalDateTime begin, LocalDateTime endExcl) {
    }

    private DateComponent parseDateComponent() {
        int pos = i;
        DateLiteral lit = parseDateLiteral();
        if (peek() == '/') {
            return new DateComponent(parseCadence(pos, lit.begin()), null);
        }
        if (peek() == ':') {
            i++;
            if (peek() == '*') {
                i++;
                return new DateComponent(null, new Ast.Bounds(pos, lit.begin(), null));
            }
            if (!isDigit(peek())) {
                throw err(i, "expected a date literal or '*' as bounds end");
            }
            int endPos = i;
            DateLiteral end = parseDateLiteral();
            if (!lit.begin().isBefore(end.endExcl())) {
                rule(endPos, "backwards bounds range — the window is empty");
            }
            return new DateComponent(null, new Ast.Bounds(pos, lit.begin(), end.endExcl()));
        }
        return new DateComponent(null, new Ast.Bounds(pos, lit.begin(), lit.endExcl()));
    }

    private Ast.Bounds parseOpenStartBounds() {
        int pos = i;
        i++; // '*'
        if (peek() != ':') {
            throw err(i, "expected ':' after a bounds '*'");
        }
        i++;
        if (peek() == '*') {
            throw err(i, "bounds require at least one date-literal endpoint");
        }
        if (!isDigit(peek())) {
            throw err(i, "expected a date literal as bounds end");
        }
        DateLiteral end = parseDateLiteral();
        return new Ast.Bounds(pos, null, end.endExcl());
    }

    /**
     * {@code YYYYMMDD[Thhmm[ss]]}. The {@code T}-glue is unconditional: a
     * {@code T} directly after 8 digits belongs to the literal, and a
     * malformed time-part is a syntax error, never re-tokenized (spec sec. 8).
     */
    private DateLiteral parseDateLiteral() {
        int pos = i;
        int run = digitRun(i);
        i = pos + run;
        // a literal the rules do not allow is noted and read past, its digit
        // runs and T part whole, so the branch reads on; every error in the
        // literal, its time part included, is positioned at its first character
        LocalDate date = LocalDate.of(1, 1, 1);
        if (run != 8) {
            rule(pos, "expected an 8-digit date literal (YYYYMMDD)");
        } else {
            int year = Integer.parseInt(src, pos, pos + 4, 10);
            try {
                date = LocalDate.of(year, twoDigits(pos + 4), twoDigits(pos + 6));
            } catch (DateTimeException e) {
                rule(pos, "not a real calendar date: " + src.substring(pos, pos + 8));
            }
            if (year < 1) {
                rule(pos, "year out of domain (1-9999)");
            }
        }
        if (peek() != 'T') {
            LocalDateTime begin = date.atStartOfDay();
            return new DateLiteral(begin, begin.plusDays(1));
        }
        i++; // 'T'
        int tPos = i;
        int take = digitRun(i);
        i = tPos + take;
        // a further digit makes the time part malformed, never the start of
        // another literal
        if (take != 4 && take != 6) {
            rule(pos, "malformed time-part in date literal (Thhmm[ss])");
            LocalDateTime begin = date.atStartOfDay();
            return new DateLiteral(begin, begin.plusMinutes(1));
        }
        int hh = twoDigits(tPos);
        int mm = twoDigits(tPos + 2);
        int ss = take == 6 ? twoDigits(tPos + 4) : 0;
        if (hh > 23) {
            rule(pos, "hour out of range in date literal");
        }
        if (mm > 59) {
            rule(pos, "minute out of range in date literal");
        }
        if (ss > 59) {
            rule(pos, "second out of range in date literal");
        }
        LocalDateTime begin = date.atTime(Math.min(hh, 23), Math.min(mm, 59), Math.min(ss, 59));
        return new DateLiteral(begin, take == 4 ? begin.plusMinutes(1) : begin.plusSeconds(1));
    }

    private Ast.Cadence parseCadence(int pos, LocalDateTime anchor) {
        i++; // '/'
        int periodPos = i;
        int periodN = parseUint("cadence period");
        char periodUnit = parseCadenceUnit();
        int durN;
        char durUnit;
        // a duration rule names the duration where it is written and the
        // period where it is implied (spec section 8)
        int durPos = periodPos;
        if (peek() == '/') {
            i++;
            durPos = i;
            durN = parseUint("cadence duration");
            durUnit = parseCadenceUnit();
        } else {
            durN = 1;
            durUnit = periodUnit;
        }
        if (periodN == 0) {
            rule(periodPos, "zero cadence period");
        }
        if (durN == 0) {
            rule(durPos, "zero cadence duration");
        }
        boolean durMonthly = durUnit == 'M' || durUnit == 'Y';
        boolean periodMonthly = periodUnit == 'M' || periodUnit == 'Y';
        if (durMonthly && !periodMonthly) {
            rule(durPos, "month/year duration unit requires a month/year period");
        }
        // conservative fixed-length check (spec section 5.2), in minutes
        // Same-unit comparisons are exact (spec §5.2); across units, conservative.
        long durMax = durUnit == periodUnit ? durN : (long) durN * maxMinutes(durUnit);
        long perMin = durUnit == periodUnit ? periodN : (long) periodN * minMinutes(periodUnit);
        if (durMax >= perMin) {
            rule(durPos, "cadence duration must be conservatively smaller than the period");
        }
        return new Ast.Cadence(pos, anchor, periodN, periodUnit, durN, durUnit);
    }

    private char parseCadenceUnit() {
        char c = peek();
        if (CADENCE_UNITS.indexOf(c) < 0) {
            throw err(i, "unknown cadence unit '" + (c == '\0' ? "<end>" : String.valueOf(c)) + "'");
        }
        i++;
        return c;
    }

    private static long maxMinutes(char unit) {
        return switch (unit) {
            case 'Y' -> 366L * 1440;
            case 'M' -> 31L * 1440;
            case 'W' -> 7L * 1440;
            case 'D' -> 1440L;
            case 'H' -> 60L;
            default -> 1L; // 'm'
        };
    }

    private static long minMinutes(char unit) {
        return switch (unit) {
            case 'Y' -> 365L * 1440;
            case 'M' -> 28L * 1440;
            default -> maxMinutes(unit);
        };
    }

    // ------------------------------------------------- deferred D validation

    /** D's domain depends on scope, which needs the whole expression (any component order). */
    private void validateDayScope(Ast.Expr expr) {
        Ast.Selector d = expr.find('D');
        if (d == null) {
            return;
        }
        int max = switch (expr.scopeOfDay()) {
            case 'Q' -> 92;
            case 'Y' -> 366;
            default -> 31;
        };
        for (long[] value : dayValues) {
            checkDayValue((int) value[0], value[1], max);
        }
        if (d.stride && d.interval > max) {
            rule(dayIntervalPos, "stride interval exceeds parent domain — use a cadence");
        }
    }

    private void checkDayValue(int pos, long v, int max) {
        if (v == 0) {
            rule(pos, "zero value on a 1-based domain");
        }
        if (v > max) {
            rule(pos, "day out of domain (1-" + max + ")");
        }
        if (v < 0 && -v > max) {
            rule(pos, "negative value out of domain — symmetric parse-time check (-" + max + "..-1)");
        }
    }

    // -------------------------------------------------------------- helpers

    static int domainMin(char des) {
        return des == 'H' || des == 'm' || des == 's' ? 0 : 1;
    }

    static int domainMax(char des) {
        return switch (des) {
            case 'Y' -> 9999;
            case 'Q' -> 4;
            case 'M' -> 12;
            case 'W' -> 53;
            case 'E' -> 7;
            case 'H' -> 23;
            default -> 59; // m, s
        };
    }

    /** Domain SIZE (count of values) — the negative-value parse bound (spec section 3). */
    static int domainSize(char des) {
        return domainMax(des) - domainMin(des) + 1;
    }

    private int parseUint(String what) {
        if (digitRun(i) == 0) {
            throw err(i, "expected " + what);
        }
        return readInteger(i);
    }

    /**
     * Reads the run of ASCII digits at the cursor as an integer: leading
     * zeros are unlimited and the value is at most 2147483647 (spec section
     * 8); a larger one is an error at {@code pos}, the integer's position
     * (its sign included).
     */
    private int readInteger(int pos) {
        long v = 0;
        while (i < src.length() && isDigit(src.charAt(i))) {
            v = Math.min(v * 10 + (src.charAt(i) - '0'), Integer.MAX_VALUE + 1L);
            i++;
        }
        if (v > Integer.MAX_VALUE) {
            rule(pos, "integer above " + Integer.MAX_VALUE);
        }
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    private int twoDigits(int at) {
        return (src.charAt(at) - '0') * 10 + (src.charAt(at + 1) - '0');
    }

    private int digitRun(int from) {
        int j = from;
        while (j < src.length() && isDigit(src.charAt(j))) {
            j++;
        }
        return j - from;
    }

    private char peek() {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void skipSpaces() {
        while (i < src.length() && src.charAt(i) == ' ') {
            i++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A grammar error: the branch ends with it, or with an earlier rule error. */
    private DTRExpParseException err(int pos, String message) {
        rule(pos, message);
        return pending;
    }

    /** Notes a rule error; the branch reads on, and its earliest error wins. */
    private void rule(int pos, String message) {
        if (pending == null || pos < pending.position()) {
            pending = new DTRExpParseException(pos, message);
        }
    }
}
