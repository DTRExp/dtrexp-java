package io.onury.dtrexp;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spec section 9.1 "required minimum" of static unsatisfiability
 * warnings, and nothing more (the {@code quiet} vectors punish a linter that
 * cries wolf):
 *
 * <ul>
 *   <li>per-selector satisfiability against the set of domain sizes implied
 *       by co-present selectors (catches {@code D30 M2}, {@code W53 Y2021},
 *       {@code D366 Y2021}, and statically-empty non-wrap ranges like
 *       {@code D-1:5}, {@code M-2:2}, {@code D366:* Y2021});</li>
 *   <li>full-domain exclusions ({@code M!1:12});</li>
 *   <li>{@code M}∩{@code Q} disjointness ({@code M-1 Q1}).</li>
 * </ul>
 *
 * Concrete-year checks run only over closed year spans no wider than 1,000
 * years; open or wider spans stay quiet. With {@code W} present, {@code Y} is
 * the week-year, so day-of-year × year checks stay quiet (cross-selector
 * territory) while {@code W} × week-year checks still run.
 */
final class StaticChecks {

    private StaticChecks() {
    }

    static void check(Ast.Expr expr, List<DtrExpWarning> out) {
        boolean hasW = expr.has('W');
        List<Integer> years = concreteYears(expr.find('Y'));
        for (Ast.Selector s : expr.selectors) {
            int[] sizes = possibleSizes(s.des, expr, years, hasW);
            if (sizes == null) {
                continue; // T and Y have no size-based satisfiability check
            }
            if (!satisfiable(s, sizes)) {
                out.add(new DtrExpWarning(s.pos, "statically unsatisfiable '" + s.des
                        + "' selector — it can never cover anything"));
            }
        }
        checkMonthQuarterDisjoint(expr, out);
    }

    // ------------------------------------------------------- possible sizes

    /**
     * The set of possible parent-instance sizes (largest values) for a
     * selector's domain, narrowed by co-present selectors. {@code null}
     * means "no check applies".
     */
    private static int[] possibleSizes(char des, Ast.Expr expr, List<Integer> years, boolean hasW) {
        switch (des) {
            case 'M':
                return new int[] {12};
            case 'Q':
                return new int[] {4};
            case 'E':
                return new int[] {7};
            case 'H':
                return new int[] {23};
            case 'm':
            case 's':
                return new int[] {59};
            case 'W': {
                if (years == null) {
                    return new int[] {52, 53};
                }
                Set<Integer> sizes = new LinkedHashSet<>();
                for (int y : years) {
                    sizes.add(weeksInWeekYear(y));
                }
                return toArray(sizes);
            }
            case 'D':
                return daySizes(expr, years, hasW);
            default:
                return null; // Y, T
        }
    }

    private static int[] daySizes(Ast.Expr expr, List<Integer> years, boolean hasW) {
        switch (expr.scopeOfDay()) {
            case 'Q': {
                Set<Integer> months = staticSet(expr.find('Q'), 4);
                Set<Integer> sizes = new LinkedHashSet<>();
                for (int q : months) {
                    sizes.add(q == 1 ? 90 : q == 2 ? 91 : 92);
                }
                if (months.contains(1)) {
                    sizes.add(91); // leap Q1
                }
                return sizes.isEmpty() ? new int[] {90, 91, 92} : toArray(sizes);
            }
            case 'Y': {
                if (hasW || years == null) {
                    // with W present, Y is the week-year while day-of-year
                    // stays calendar — MUST stay quiet (spec section 9.1)
                    return new int[] {365, 366};
                }
                Set<Integer> sizes = new LinkedHashSet<>();
                for (int y : years) {
                    sizes.add(Year.isLeap(y) ? 366 : 365);
                }
                return toArray(sizes);
            }
            default: { // month scope
                Ast.Selector m = expr.find('M');
                Set<Integer> months = m == null ? null : staticSet(m, 12);
                Set<Integer> sizes = new LinkedHashSet<>();
                if (months == null || months.isEmpty()) {
                    return new int[] {28, 29, 30, 31};
                }
                for (int mo : months) {
                    if (mo == 2) {
                        sizes.add(28);
                        sizes.add(29);
                    } else {
                        sizes.add(mo == 4 || mo == 6 || mo == 9 || mo == 11 ? 30 : 31);
                    }
                }
                return toArray(sizes);
            }
        }
    }

    // -------------------------------------------------------- satisfiability

    /** True iff the selector can match some value under SOME possible size. */
    private static boolean satisfiable(Ast.Selector s, int[] sizes) {
        if (s.star || s.ordinal) {
            return true; // ordinal nonexistence (E7#5) is the existence rule, not a static warning
        }
        int min = Parser.domainMin(s.des);
        for (int size : sizes) {
            if (s.stride) {
                long re = resolve(s.strideEnd, size, false);
                if (s.strideStart <= re && s.strideStart <= size) {
                    return true;
                }
                continue;
            }
            if (s.exclusion) {
                boolean[] excluded = mark(s.items, size, min);
                for (int v = min; v <= size; v++) {
                    if (!excluded[v]) {
                        return true;
                    }
                }
                continue;
            }
            for (Ast.Item it : s.items) {
                if (itemSatisfiable(it, size, min)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean itemSatisfiable(Ast.Item it, int size, int min) {
        if (!it.range) {
            long v = resolve(it.start, size, true);
            return v >= min && v <= size;
        }
        if (it.wrap) {
            return true; // a true wrap range is never statically empty
        }
        long rs = it.start == Ast.STAR ? min : resolve(it.start, size, true);
        long re = resolve(it.end, size, false);
        return rs <= re && rs <= size && re >= min;
    }

    private static long resolve(long v, int size, boolean isStart) {
        if (v == Ast.STAR) {
            return isStart ? Long.MIN_VALUE + 1 : size;
        }
        return v < 0 ? size + 1 + v : v;
    }

    /** Marks the values covered by an item list within [min..size]. */
    private static boolean[] mark(List<Ast.Item> items, int size, int min) {
        boolean[] set = new boolean[size + 1];
        for (Ast.Item it : items) {
            if (!it.range) {
                long v = resolve(it.start, size, true);
                if (v >= min && v <= size) {
                    set[(int) v] = true;
                }
                continue;
            }
            if (it.wrap) {
                fill(set, it.start, size, min, size);
                fill(set, min, it.end, min, size);
                continue;
            }
            long rs = it.start == Ast.STAR ? min : resolve(it.start, size, true);
            long re = resolve(it.end, size, false);
            fill(set, rs, re, min, size);
        }
        return set;
    }

    private static void fill(boolean[] set, long from, long to, int min, int size) {
        for (long v = Math.max(from, min); v <= Math.min(to, size); v++) {
            set[(int) v] = true;
        }
    }

    // -------------------------------------------------- M ∩ Q disjointness

    private static void checkMonthQuarterDisjoint(Ast.Expr expr, List<DtrExpWarning> out) {
        Ast.Selector m = expr.find('M');
        Ast.Selector q = expr.find('Q');
        if (m == null || q == null) {
            return;
        }
        Set<Integer> months = staticSet(m, 12);
        Set<Integer> quarters = staticSet(q, 4);
        if (months.isEmpty() || quarters.isEmpty()) {
            return; // the emptiness warning already fired for that selector
        }
        for (int mo : months) {
            if (quarters.contains((mo - 1) / 3 + 1)) {
                return;
            }
        }
        out.add(new DtrExpWarning(m.pos, "statically unsatisfiable — the M and Q selectors are disjoint"));
    }

    /**
     * The full static match set of a fixed-domain (1-based) selector under
     * its fixed size. Negatives resolve statically here because the domain
     * never varies (M-1 is always December).
     */
    private static Set<Integer> staticSet(Ast.Selector s, int size) {
        Set<Integer> result = new LinkedHashSet<>();
        if (s == null || s.star) {
            for (int v = 1; v <= size; v++) {
                result.add(v);
            }
            return result;
        }
        if (s.stride) {
            long re = resolve(s.strideEnd, size, false);
            for (long v = s.strideStart; v <= Math.min(re, size); v++) {
                if ((v - s.strideStart) % s.interval < s.duration) {
                    result.add((int) v);
                }
            }
            return result;
        }
        if (s.ordinal) {
            return result; // not applicable (E only; never M/Q)
        }
        boolean[] set = mark(s.items, size, 1);
        for (int v = 1; v <= size; v++) {
            if (set[v] != s.exclusion) {
                result.add(v);
            }
        }
        return result;
    }

    // ------------------------------------------------------- concrete years

    /**
     * The concrete year set of a Y selector, or {@code null} when open,
     * excluded, or wider than 1,000 years (those MAY — and here DO — stay
     * quiet, spec section 9.1).
     */
    private static List<Integer> concreteYears(Ast.Selector y) {
        if (y == null || y.star || y.exclusion) {
            return null;
        }
        List<Integer> years = new ArrayList<>();
        if (y.stride) {
            if (y.strideEnd == Ast.STAR) {
                return null;
            }
            if (y.strideEnd - y.strideStart > 1000) {
                return null;
            }
            for (long v = y.strideStart; v <= y.strideEnd; v += 1) {
                if ((v - y.strideStart) % y.interval < y.duration) {
                    years.add((int) v);
                }
            }
            return years;
        }
        for (Ast.Item it : y.items) {
            if (!it.range) {
                years.add((int) it.start);
                continue;
            }
            if (it.start == Ast.STAR || it.end == Ast.STAR) {
                return null;
            }
            if (it.end - it.start > 1000) {
                return null;
            }
            for (long v = it.start; v <= it.end; v++) {
                years.add((int) v);
            }
        }
        return years.size() > 1001 ? null : years;
    }

    /** 52 or 53, via the ISO week fields of a date safely inside week-year {@code y}. */
    private static int weeksInWeekYear(int y) {
        return (int) IsoFields.WEEK_OF_WEEK_BASED_YEAR.rangeRefinedBy(LocalDate.of(y, 7, 1)).getMaximum();
    }

    private static int[] toArray(Set<Integer> set) {
        int[] a = new int[set.size()];
        int i = 0;
        for (int v : set) {
            a[i++] = v;
        }
        return a;
    }
}
