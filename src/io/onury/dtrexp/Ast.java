package io.onury.dtrexp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Internal parsed representation. One {@code Expr} per union branch; each
 * branch holds selectors, at most one cadence and at most one bounds.
 */
final class Ast {

    private Ast() {
    }

    /** Endpoint marker for {@code *} (the domain edge in force). */
    static final long STAR = Long.MIN_VALUE;

    /**
     * One value-list item: a single value or a range. For {@code T} the
     * values are milliseconds-of-day and {@code unitMs} is the span implied
     * by the literal's precision (hour/minute/second/millisecond).
     */
    static final class Item {
        final boolean range;
        final long start;  // value, or STAR (range endpoints only)
        final long end;    // range only; STAR allowed
        final boolean wrap; // syntactic: both endpoints literal non-negative and start > end
        final long unitMs; // T single values only; 0 elsewhere

        Item(boolean range, long start, long end, boolean wrap, long unitMs) {
            this.range = range;
            this.start = start;
            this.end = end;
            this.wrap = wrap;
            this.unitMs = unitMs;
        }

        static Item single(long v, long unitMs) {
            return new Item(false, v, 0, false, unitMs);
        }

        static Item of(long start, long end, boolean wrap) {
            return new Item(true, start, end, wrap, 0);
        }
    }

    /** Selector component: designator + one of star/list/exclusion/stride/ordinal. */
    static final class Selector {
        final char des;
        final int pos;
        final boolean star;
        final boolean exclusion;
        final List<Item> items;      // list/exclusion items; null for star/stride/ordinal
        final boolean stride;
        final long strideStart;      // literal non-negative
        final long strideEnd;        // literal, negative, or STAR (= domain edge)
        final int interval;
        final int duration;
        final boolean ordinal;       // E only
        final long ordValue;         // the weekday value (may be negative)
        final int ordN;              // -5..-1, 1..5

        private Selector(char des, int pos, boolean star, boolean exclusion, List<Item> items,
                boolean stride, long strideStart, long strideEnd, int interval, int duration,
                boolean ordinal, long ordValue, int ordN) {
            this.des = des;
            this.pos = pos;
            this.star = star;
            this.exclusion = exclusion;
            this.items = items;
            this.stride = stride;
            this.strideStart = strideStart;
            this.strideEnd = strideEnd;
            this.interval = interval;
            this.duration = duration;
            this.ordinal = ordinal;
            this.ordValue = ordValue;
            this.ordN = ordN;
        }

        static Selector all(char des, int pos) {
            return new Selector(des, pos, true, false, null, false, 0, 0, 0, 0, false, 0, 0);
        }

        static Selector list(char des, int pos, List<Item> items) {
            return new Selector(des, pos, false, false, items, false, 0, 0, 0, 0, false, 0, 0);
        }

        static Selector not(char des, int pos, List<Item> items) {
            return new Selector(des, pos, false, true, items, false, 0, 0, 0, 0, false, 0, 0);
        }

        static Selector strided(char des, int pos, long start, long end, int interval, int duration) {
            return new Selector(des, pos, false, false, null, true, start, end, interval, duration, false, 0, 0);
        }

        static Selector nth(char des, int pos, long value, int n) {
            return new Selector(des, pos, false, false, null, false, 0, 0, 0, 0, true, value, n);
        }
    }

    /** Anchored cadence (spec section 5.2). */
    static final class Cadence {
        final int pos;
        final LocalDateTime anchor; // naive local date-time
        final int periodN;
        final char periodUnit;      // Y M W D H m
        final int durN;
        final char durUnit;

        Cadence(int pos, LocalDateTime anchor, int periodN, char periodUnit, int durN, char durUnit) {
            this.pos = pos;
            this.anchor = anchor;
            this.periodN = periodN;
            this.periodUnit = periodUnit;
            this.durN = durN;
            this.durUnit = durUnit;
        }
    }

    /** Absolute bounds (spec section 6) as a naive local wall-clock window. */
    static final class Bounds {
        final int pos;
        final LocalDateTime start;   // inclusive; null = open
        final LocalDateTime endExcl; // exclusive; null = open

        Bounds(int pos, LocalDateTime start, LocalDateTime endExcl) {
            this.pos = pos;
            this.start = start;
            this.endExcl = endExcl;
        }
    }

    /** One union branch. */
    static final class Expr {
        final List<Selector> selectors;
        final Cadence cadence; // nullable
        final Bounds bounds;   // nullable

        Expr(List<Selector> selectors, Cadence cadence, Bounds bounds) {
            this.selectors = selectors;
            this.cadence = cadence;
            this.bounds = bounds;
        }

        Selector find(char des) {
            for (Selector s : selectors) {
                if (s.des == des) {
                    return s;
                }
            }
            return null;
        }

        boolean has(char des) {
            return find(des) != null;
        }

        /**
         * Scope of D values and E ordinals: nearest of M/Q/Y present in this
         * expression, else M (spec section 2 table).
         */
        char scopeOfDay() {
            if (has('M')) {
                return 'M';
            }
            if (has('Q')) {
                return 'Q';
            }
            if (has('Y')) {
                return 'Y';
            }
            return 'M';
        }
    }
}
