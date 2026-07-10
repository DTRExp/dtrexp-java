package io.onury.dtrexp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.List;

/**
 * Coverage evaluation (spec section 9): one calendar-field extraction from
 * the instant in the evaluation zone, then per-component integer tests.
 * Calendar-period cadence windows and bounds are naive local wall-clock
 * intervals; only H/m-period cadence anchors resolve to an instant.
 */
final class Evaluator {

    private Evaluator() {
    }

    /** Calendar fields of one instant in one zone, computed once. */
    private static final class Fields {
        final LocalDateTime ldt;
        final int year;
        final int month;
        final int dayOfMonth;
        final int lengthOfMonth;
        final int quarter;
        final int dayOfQuarter;
        final int lengthOfQuarter;
        final int dayOfYear;
        final int lengthOfYear;
        final int weekday;
        final int week;
        final int weekYear;
        final int weeksInWeekYear;
        final int hour;
        final int minute;
        final int second;
        final long msOfDay;

        Fields(ZonedDateTime zdt) {
            this.ldt = zdt.toLocalDateTime();
            LocalDate date = ldt.toLocalDate();
            this.year = date.getYear();
            this.month = date.getMonthValue();
            this.dayOfMonth = date.getDayOfMonth();
            this.lengthOfMonth = date.lengthOfMonth();
            this.quarter = date.get(IsoFields.QUARTER_OF_YEAR);
            this.dayOfQuarter = date.get(IsoFields.DAY_OF_QUARTER);
            this.lengthOfQuarter = (int) IsoFields.DAY_OF_QUARTER.rangeRefinedBy(date).getMaximum();
            this.dayOfYear = date.getDayOfYear();
            this.lengthOfYear = date.lengthOfYear();
            this.weekday = date.getDayOfWeek().getValue();
            this.week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            this.weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
            this.weeksInWeekYear = (int) IsoFields.WEEK_OF_WEEK_BASED_YEAR.rangeRefinedBy(date).getMaximum();
            this.hour = ldt.getHour();
            this.minute = ldt.getMinute();
            this.second = ldt.getSecond();
            this.msOfDay = ldt.toLocalTime().toNanoOfDay() / 1_000_000L;
        }
    }

    static boolean covers(List<Ast.Expr> exprs, Instant instant, ZoneId zone) {
        Fields f = new Fields(instant.atZone(zone));
        for (Ast.Expr e : exprs) {
            if (coversExpr(e, f, instant, zone)) {
                return true;
            }
        }
        return false;
    }

    private static boolean coversExpr(Ast.Expr e, Fields f, Instant instant, ZoneId zone) {
        if (e.bounds != null && !inBounds(e.bounds, f.ldt)) {
            return false;
        }
        for (Ast.Selector s : e.selectors) {
            if (!matches(s, e, f)) {
                return false;
            }
        }
        return e.cadence == null || cadenceCovers(e.cadence, f.ldt, instant, zone);
    }

    // -------------------------------------------------------------- bounds

    private static boolean inBounds(Ast.Bounds b, LocalDateTime ldt) {
        return (b.start == null || !ldt.isBefore(b.start))
                && (b.endExcl == null || ldt.isBefore(b.endExcl));
    }

    // ----------------------------------------------------------- selectors

    private static boolean matches(Ast.Selector s, Ast.Expr e, Fields f) {
        if (s.des == 'T') {
            return timeMatches(s, f.msOfDay);
        }
        int value;
        int max; // the parent instance's actual largest value
        switch (s.des) {
            case 'Y' -> {
                // with W present, Y is the ISO week-year for this expression
                value = e.has('W') ? f.weekYear : f.year;
                max = 9999;
            }
            case 'Q' -> {
                value = f.quarter;
                max = 4;
            }
            case 'M' -> {
                value = f.month;
                max = 12;
            }
            case 'W' -> {
                value = f.week;
                max = f.weeksInWeekYear;
            }
            case 'D' -> {
                switch (e.scopeOfDay()) {
                    case 'Q' -> {
                        value = f.dayOfQuarter;
                        max = f.lengthOfQuarter;
                    }
                    case 'Y' -> {
                        // day-of-year stays calendar even when W makes Y the week-year
                        value = f.dayOfYear;
                        max = f.lengthOfYear;
                    }
                    default -> {
                        value = f.dayOfMonth;
                        max = f.lengthOfMonth;
                    }
                }
            }
            case 'E' -> {
                value = f.weekday;
                max = 7;
            }
            case 'H' -> {
                value = f.hour;
                max = 23;
            }
            case 'm' -> {
                value = f.minute;
                max = 59;
            }
            default -> { // 's'
                value = f.second;
                max = 59;
            }
        }
        int min = Parser.domainMin(s.des);
        if (s.star) {
            return true;
        }
        if (s.ordinal) {
            return ordinalMatches(s, e, f, value, max);
        }
        if (s.stride) {
            long start = s.strideStart;
            long end = resolveEnd(s.strideEnd, max);
            return value >= start && value <= end && (value - start) % s.interval < s.duration;
        }
        boolean in = false;
        for (Ast.Item it : s.items) {
            if (itemMatches(it, value, max, min)) {
                in = true;
                break;
            }
        }
        return s.exclusion != in;
    }

    private static boolean itemMatches(Ast.Item it, int value, int max, int min) {
        if (!it.range) {
            return value == resolve(it.start, max);
        }
        if (it.wrap) {
            return value >= it.start || value <= it.end;
        }
        long rs = it.start == Ast.STAR ? min : resolve(it.start, max);
        long re = resolveEnd(it.end, max);
        return rs <= value && value <= re;
    }

    private static long resolve(long v, int max) {
        return v < 0 ? max + 1 + v : v;
    }

    private static long resolveEnd(long v, int max) {
        return v == Ast.STAR ? max : resolve(v, max);
    }

    private static boolean ordinalMatches(Ast.Selector s, Ast.Expr e, Fields f, int weekday, int max) {
        if (weekday != resolve(s.ordValue, max)) {
            return false;
        }
        int dayOfScope;
        int lengthOfScope;
        switch (e.scopeOfDay()) { // E# scope: nearest of M/Q/Y present, else M
            case 'Q' -> {
                dayOfScope = f.dayOfQuarter;
                lengthOfScope = f.lengthOfQuarter;
            }
            case 'Y' -> {
                dayOfScope = f.dayOfYear;
                lengthOfScope = f.lengthOfYear;
            }
            default -> {
                dayOfScope = f.dayOfMonth;
                lengthOfScope = f.lengthOfMonth;
            }
        }
        if (s.ordN > 0) {
            return (dayOfScope - 1) / 7 + 1 == s.ordN;
        }
        return (lengthOfScope - dayOfScope) / 7 + 1 == -s.ordN;
    }

    private static boolean timeMatches(Ast.Selector s, long ms) {
        for (Ast.Item it : s.items) {
            if (!it.range) {
                if (ms >= it.start && ms < it.start + it.unitMs) {
                    return true;
                }
            } else if (it.wrap) {
                if (ms >= it.start || ms < it.end) {
                    return true;
                }
            } else if (ms >= it.start && ms < it.end) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- cadence

    private static boolean cadenceCovers(Ast.Cadence c, LocalDateTime ldt, Instant instant, ZoneId zone) {
        if (c.periodUnit == 'H' || c.periodUnit == 'm') {
            return absoluteCadenceCovers(c, instant, zone);
        }
        // calendar-period (D/W/M/Y): naive local-calendar arithmetic; the
        // occurrence window is a local wall-clock interval and the anchor is
        // never resolved to an instant (spec section 9.3)
        if (ldt.isBefore(c.anchor)) {
            return false;
        }
        ChronoUnit unit = switch (c.periodUnit) {
            case 'Y' -> ChronoUnit.YEARS;
            case 'M' -> ChronoUnit.MONTHS;
            case 'W' -> ChronoUnit.WEEKS;
            default -> ChronoUnit.DAYS;
        };
        long k = unit.between(c.anchor, ldt) / c.periodN;
        for (long kk = Math.max(0, k - 1); kk <= k + 1; kk++) {
            // start_k = constrain(anchor + k*period), always from the ORIGINAL
            // anchor — java.time's plusMonths/plusYears clamp to the last valid
            // day, which is exactly the spec's (and Temporal's) constrain
            LocalDateTime start = plus(c.anchor, kk * c.periodN, c.periodUnit);
            if (ldt.isBefore(start)) {
                break;
            }
            // end_k measured from the constrained start
            LocalDateTime end = plus(start, c.durN, c.durUnit);
            if (ldt.isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    /**
     * H/m periods are absolute elapsed time from a resolved anchor instant.
     * {@link ZonedDateTime#of} implements exactly the spec's required
     * disambiguation (Temporal's {@code compatible}): in a fall-back overlap
     * it keeps the EARLIER offset; in a spring-forward gap it shifts the
     * local time FORWARD by the gap's length.
     */
    private static boolean absoluteCadenceCovers(Ast.Cadence c, Instant instant, ZoneId zone) {
        Instant anchor = ZonedDateTime.of(c.anchor, zone).toInstant();
        long periodMs = c.periodN * (c.periodUnit == 'H' ? 3_600_000L : 60_000L);
        long durMs = c.durN * switch (c.durUnit) {
            case 'W' -> 604_800_000L;
            case 'D' -> 86_400_000L;
            case 'H' -> 3_600_000L;
            default -> 60_000L; // 'm'
        };
        long elapsed = Duration.between(anchor, instant).toMillis();
        if (elapsed < 0) {
            return false;
        }
        return elapsed % periodMs < durMs;
    }

    private static LocalDateTime plus(LocalDateTime base, long n, char unit) {
        return switch (unit) {
            case 'Y' -> base.plusYears(n);
            case 'M' -> base.plusMonths(n);
            case 'W' -> base.plusWeeks(n);
            case 'D' -> base.plusDays(n);
            case 'H' -> base.plusHours(n);
            default -> base.plusMinutes(n); // 'm'
        };
    }
}
