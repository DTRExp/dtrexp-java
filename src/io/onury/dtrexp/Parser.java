package io.onury.dtrexp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for DTRExp draft 2.8. Tokens match greedily and
 * never backtrack on semantic failure (spec section 8). Static validity rules
 * (domains, stride limits, one-designator-once, cadence/bounds arity) are
 * enforced here; static satisfiability warnings live in {@link StaticChecks}.
 */
final class Parser {

    private static final String DESIGNATORS = "YQMWDETHms";
    private static final String CADENCE_UNITS = "YMWDHm";
    private static final long DAY_MS = 86_400_000L;

    private final String src;
    private int i;
    private final List<Ast.Expr> exprs = new ArrayList<>();
    private final List<DtrExpWarning> warnings = new ArrayList<>();

    private Parser(String src) {
        this.src = src;
    }

    record Parsed(List<Ast.Expr> exprs, List<DtrExpWarning> warnings) {
    }

    static Parsed parse(String src) {
        Parser p = new Parser(src);
        p.parseAll();
        return new Parsed(p.exprs, p.warnings);
    }

    // ------------------------------------------------------------------ top

    private void parseAll() {
        if (src.isBlank()) {
            throw err(0, "empty expression");
        }
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
                        throw err(pos, "more than one cadence per expression");
                    }
                    cadence = dc.cadence;
                } else {
                    if (bounds != null) {
                        throw err(pos, "more than one bounds component per expression");
                    }
                    bounds = dc.bounds;
                }
            } else if (c == '*') {
                if (bounds != null) {
                    throw err(pos, "more than one bounds component per expression");
                }
                bounds = parseOpenStartBounds();
            } else if (DESIGNATORS.indexOf(c) >= 0) {
                Ast.Selector s = c == 'T' ? parseTimeSelector() : parseSelector();
                for (Ast.Selector prev : sels) {
                    if (prev.des == s.des) {
                        throw err(pos, "duplicate designator '" + s.des + "' in one expression");
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
        validateDayScope(expr);
        StaticChecks.check(expr, warnings);
        exprs.add(expr);
    }

    // -------------------------------------------------------- int selectors

    private Ast.Selector parseSelector() {
        int pos = i;
        char des = src.charAt(i++);
        if (i >= src.length() || " ,|".indexOf(src.charAt(i)) >= 0) {
            throw err(pos, "designator '" + des + "' without value");
        }
        char c = src.charAt(i);
        if (c == '!') {
            i++;
            return Ast.Selector.not(des, pos, parseIntList(des));
        }
        if (c == '*' && !(i + 1 < src.length() && src.charAt(i + 1) == ':')) {
            i++;
            if (peek() == '/') {
                throw err(i, "anchorless stride — explicit start required");
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
            throw err(i, "backwards year range — years are absolute and non-cyclic, no wrap");
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
        int d0 = i;
        while (i < src.length() && isDigit(src.charAt(i))) {
            i++;
        }
        if (i == d0) {
            throw err(pos, "expected a value for '" + des + "'");
        }
        if (i - d0 > 7) {
            throw err(pos, "value out of domain");
        }
        long v = Long.parseLong(src, d0, i, 10);
        if (des == 'Y') {
            if (neg) {
                throw err(pos, "negative value on Y — no edge to count back from");
            }
            if (v < 1 || v > 9999) {
                throw err(pos, "year out of domain — Y takes 4-digit ISO years (1-9999)");
            }
            return v;
        }
        int min = domainMin(des);
        if (v == 0 && min == 1) {
            throw err(pos, "zero value on a 1-based domain");
        }
        if (des == 'D') {
            // domain depends on scope; checked in validateDayScope after the
            // whole expression is read (components may appear in any order)
            return neg ? -v : v;
        }
        if (neg) {
            if (v == 0 || v > domainSize(des)) {
                throw err(pos, "negative value out of domain (-" + domainSize(des) + "..-1)");
            }
            return -v;
        }
        if (v > domainMax(des)) {
            throw err(pos, "value out of domain (" + min + "-" + domainMax(des) + ")");
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
        boolean neg = peek() == '-';
        if (neg) {
            i++;
        }
        int d0 = i;
        while (i < src.length() && isDigit(src.charAt(i))) {
            i++;
        }
        if (i == d0) {
            throw err(hashPos, "expected an ordinal after '#'");
        }
        long n = Long.parseLong(src, d0, i, 10);
        if (n == 0) {
            throw err(hashPos, "ordinal zero");
        }
        if (n > 5) {
            throw err(hashPos, "ordinal out of range (-5..-1, 1..5)");
        }
        if (peek() == ',') {
            throw err(i, "ordinal is single — union more via '|'");
        }
        return Ast.Selector.nth(des, pos, first.start, (int) (neg ? -n : n));
    }

    private Ast.Selector parseStride(char des, int pos, Ast.Item first) {
        int slashPos = i;
        if (first.wrap) {
            throw err(slashPos, "wrap ranges take no stride");
        }
        long start = first.start;
        if (start == Ast.STAR) {
            throw err(slashPos, "anchorless stride — explicit start required");
        }
        if (start < 0) {
            throw err(slashPos, "stride start must be non-negative (end-relative anchors shift per parent instance)");
        }
        i++; // '/'
        int interval = parseUint("stride interval");
        int duration = 1;
        if (peek() == '/') {
            i++;
            duration = parseUint("stride duration");
        }
        if (interval < 2) {
            throw err(slashPos, "stride interval must be >= 2");
        }
        if (des != 'Y' && des != 'D' && interval > domainSize(des)) {
            throw err(slashPos, "stride interval exceeds parent domain — use a cadence");
        }
        if (duration < 1 || duration >= interval) {
            throw err(slashPos, "stride duration must be 1 <= duration < interval");
        }
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
                    throw err(endPos, "T range with equal endpoints — half-open, would cover nothing");
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

    /** Returns {msOfDay, unitMs}. Greedy: takes 6 digits if available, else 4, else 2. */
    private long[] parseTimeval(boolean endPosition) {
        int pos = i;
        int run = digitRun(i);
        int take;
        if (run >= 6) {
            take = 6;
        } else if (run >= 4) {
            take = 4;
        } else if (run >= 2) {
            take = 2;
        } else {
            throw err(pos, "expected a time value");
        }
        int hh = twoDigits(pos);
        int mm = take >= 4 ? twoDigits(pos + 2) : 0;
        int ss = take >= 6 ? twoDigits(pos + 4) : 0;
        i = pos + take;
        long ms = 0;
        long unit;
        boolean hasMillis = take == 6 && peek() == '.';
        if (hasMillis) {
            i++;
            if (digitRun(i) < 3) {
                throw err(i, "milliseconds are exactly 3 digits");
            }
            ms = Long.parseLong(src, i, i + 3, 10);
            if (digitRun(i) > 3) {
                throw err(i, "milliseconds are exactly 3 digits");
            }
            i += 3;
            unit = 1;
        } else {
            unit = take == 2 ? 3_600_000L : take == 4 ? 60_000L : 1_000L;
        }
        if (hh == 24) {
            // take == 4 already implies no millis (millis needs take == 6)
            if (endPosition && take == 4 && mm == 0) {
                return new long[] {DAY_MS, unit};
            }
            throw err(pos, "hour 24 is the exact 4-digit token '2400', in range-end position only");
        }
        if (hh > 23) {
            throw err(pos, "hour out of range in time value");
        }
        if (mm > 59) {
            throw err(pos, "minute out of range in time value");
        }
        if (ss > 59) {
            throw err(pos, "second out of range in time value (leap seconds are not representable)");
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
                throw err(endPos, "backwards bounds range — the window is empty");
            }
            return new DateComponent(null, new Ast.Bounds(pos, lit.begin(), end.endExcl()));
        }
        return new DateComponent(null, new Ast.Bounds(pos, lit.begin(), lit.endExcl()));
    }

    private Ast.Bounds parseOpenStartBounds() {
        int pos = i;
        i++; // '*'
        if (peek() != ':') {
            throw err(pos, "unexpected character '*'");
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
        if (digitRun(i) != 8) {
            throw err(pos, "expected an 8-digit date literal (YYYYMMDD)");
        }
        int year = Integer.parseInt(src, pos, pos + 4, 10);
        int month = twoDigits(pos + 4);
        int day = twoDigits(pos + 6);
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw err(pos, "not a real calendar date: " + src.substring(pos, pos + 8));
        }
        if (year < 1) {
            throw err(pos, "year out of domain (1-9999)");
        }
        i = pos + 8;
        if (peek() != 'T') {
            LocalDateTime begin = date.atStartOfDay();
            return new DateLiteral(begin, begin.plusDays(1));
        }
        i++; // 'T'
        int tPos = i;
        int run = digitRun(i);
        int take = run >= 6 ? 6 : run >= 4 ? 4 : -1;
        if (take < 0) {
            throw err(tPos, "malformed time-part in date literal (Thhmm[ss])");
        }
        int hh = twoDigits(tPos);
        int mm = twoDigits(tPos + 2);
        int ss = take == 6 ? twoDigits(tPos + 4) : 0;
        if (hh > 23) {
            throw err(tPos, "hour out of range in date literal");
        }
        if (mm > 59) {
            throw err(tPos, "minute out of range in date literal");
        }
        if (ss > 59) {
            throw err(tPos, "second out of range in date literal");
        }
        i = tPos + take;
        LocalDateTime begin = date.atTime(hh, mm, ss);
        return new DateLiteral(begin, take == 4 ? begin.plusMinutes(1) : begin.plusSeconds(1));
    }

    private Ast.Cadence parseCadence(int pos, LocalDateTime anchor) {
        i++; // '/'
        int periodN = parseUint("cadence period");
        char periodUnit = parseCadenceUnit();
        int durN;
        char durUnit;
        if (peek() == '/') {
            i++;
            durN = parseUint("cadence duration");
            durUnit = parseCadenceUnit();
        } else {
            durN = 1;
            durUnit = periodUnit;
        }
        if (periodN == 0) {
            throw err(pos, "zero cadence period");
        }
        if (durN == 0) {
            throw err(pos, "zero cadence duration");
        }
        boolean durMonthly = durUnit == 'M' || durUnit == 'Y';
        boolean periodMonthly = periodUnit == 'M' || periodUnit == 'Y';
        if (durMonthly && !periodMonthly) {
            throw err(pos, "month/year duration unit requires a month/year period");
        }
        // conservative fixed-length check (spec section 5.2), in minutes
        long durMax = (long) durN * maxMinutes(durUnit);
        long perMin = (long) periodN * minMinutes(periodUnit);
        if (durMax >= perMin) {
            throw err(pos, "cadence duration must be conservatively smaller than the period");
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
        if (d.stride) {
            checkDayValue(d.pos, d.strideStart, max);
            if (d.strideEnd != Ast.STAR) {
                checkDayValue(d.pos, d.strideEnd, max);
            }
            if (d.interval > max) {
                throw err(d.pos, "stride interval exceeds parent domain — use a cadence");
            }
            return;
        }
        if (d.items != null) {
            for (Ast.Item it : d.items) {
                if (it.start != Ast.STAR) {
                    checkDayValue(d.pos, it.start, max);
                }
                if (it.range && it.end != Ast.STAR) {
                    checkDayValue(d.pos, it.end, max);
                }
            }
        }
    }

    private void checkDayValue(int pos, long v, int max) {
        if (v > max) {
            throw err(pos, "day out of domain (1-" + max + ")");
        }
        if (v < 0 && -v > max) {
            throw err(pos, "negative value out of domain — symmetric parse-time check (-" + max + "..-1)");
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
        int pos = i;
        int run = digitRun(i);
        if (run == 0) {
            throw err(pos, "expected " + what);
        }
        if (run > 7) {
            throw err(pos, what + " out of range");
        }
        int v = Integer.parseInt(src, pos, pos + run, 10);
        i = pos + run;
        return v;
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

    private DtrExpParseException err(int pos, String message) {
        return new DtrExpParseException(pos, message);
    }
}
