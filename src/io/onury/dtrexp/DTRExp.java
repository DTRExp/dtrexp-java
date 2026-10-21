package io.onury.dtrexp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A parsed DTRExp — Date-Time Range &amp; Recurrence Expression (draft 3).
 *
 * <p>A DTRExp denotes a possibly infinite set of time intervals and is
 * evaluated for <em>coverage</em>: {@link #covers(Instant, ZoneId)} answers
 * "is this instant inside the set?" in O(#components) after one calendar
 * field extraction. Expressions are time-zone agnostic; the zone is an
 * evaluation parameter (default UTC), or bound once with
 * {@link #withZone(ZoneId)}.</p>
 *
 * <pre>{@code
 * DTRExp businessHours = DTRExp.parse("T0900:1800 E1:5");
 * boolean open = businessHours.covers(Instant.now(), ZoneId.of("Europe/Berlin"));
 * }</pre>
 */
public final class DTRExp {

    private final String source;
    private final List<Ast.Expr> exprs;
    private final List<DTRExpWarning> warnings;
    private final ZoneId zone; // null when unbound

    private DTRExp(String source, List<Ast.Expr> exprs, List<DTRExpWarning> warnings, ZoneId zone) {
        this.source = source;
        this.exprs = exprs;
        this.warnings = List.copyOf(warnings);
        this.zone = zone;
    }

    /**
     * Parses an expression, throwing a positioned {@link DTRExpParseException}
     * on any syntax or static-validity error. Non-fatal findings are exposed
     * via {@link #warnings()}.
     */
    public static DTRExp parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        Parser.Parsed parsed = Parser.parse(expression);
        return new DTRExp(expression, parsed.exprs(), parsed.warnings(), null);
    }

    /** Parses without throwing: returns the errors (at most one) or the warnings. */
    public static ValidationResult validate(String expression) {
        try {
            DTRExp exp = parse(expression);
            return new ValidationResult(true, List.of(), exp.warnings);
        } catch (DTRExpParseException e) {
            return new ValidationResult(false, List.of(e), List.of());
        }
    }

    /** The original source string. */
    public String source() {
        return source;
    }

    /** Static-analysis warnings (spec section 9.1), possibly empty. */
    public List<DTRExpWarning> warnings() {
        return warnings;
    }

    /**
     * A copy whose evaluation defaults to {@code zone}; this one is left as it
     * is. The copy's source and warnings are the same, since the zone belongs
     * to evaluation, never to the expression. A zone passed to a call still wins.
     */
    public DTRExp withZone(ZoneId zone) {
        return new DTRExp(source, exprs, warnings, Objects.requireNonNull(zone, "zone"));
    }

    /**
     * {@link #withZone(ZoneId)} with the zone given as an IANA identifier, e.g.
     * {@code "Europe/Berlin"}, matched without regard to case (see
     * {@link #covers(Instant, String)}). An unknown or empty identifier fails
     * here, at once, with a {@link ZoneRulesException}.
     */
    public DTRExp withZone(String zoneId) {
        return withZone(zoneOf(zoneId));
    }

    /** The bound evaluation zone, empty when none is bound. */
    public Optional<ZoneId> zone() {
        return Optional.ofNullable(zone);
    }

    /** Coverage in the bound zone, else UTC (the default evaluation zone). */
    public boolean covers(Instant instant) {
        return covers(instant, zone == null ? ZoneOffset.UTC : zone);
    }

    /** Coverage of {@code instant} evaluated in {@code zone}. */
    public boolean covers(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        return Evaluator.covers(exprs, instant, zone);
    }

    /**
     * Coverage with the zone given as an IANA identifier, e.g.
     * {@code "Europe/Berlin"}: a zone or link name of the tz database that
     * {@code java.time} carries, matched without regard to case and
     * canonicalized ({@code "europe/berlin"} is {@code Europe/Berlin}).
     * Anything else throws a {@link ZoneRulesException}, as an unknown zone
     * does: a fixed offset ({@code "+02:00"}, {@code "UTC+2"}), {@code "Z"},
     * the retired {@code SystemV/} names, and the empty string.
     */
    public boolean covers(Instant instant, String zoneId) {
        return covers(instant, zoneOf(zoneId));
    }

    /**
     * Resolves a zone identifier against the names the registered zone rules
     * providers know (the JDK's tz database), so {@link ZoneId#of(String)}'s
     * offsets and prefixed offsets never reach evaluation.
     */
    static ZoneId zoneOf(String zoneId) {
        String name = ZoneNames.CANONICAL.get(asciiLower(Objects.requireNonNull(zoneId, "zoneId")));
        if (name == null) {
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }
        return ZoneId.of(name);
    }

    /** Lowercases ASCII letters only, so no other character folds onto a name. */
    static String asciiLower(String s) {
        char[] chars = s.toCharArray();
        for (int k = 0; k < chars.length; k++) {
            if (chars[k] >= 'A' && chars[k] <= 'Z') {
                chars[k] += 'a' - 'A';
            }
        }
        return new String(chars);
    }

    /** The zone names, lowercased to their spelling; built on first use. */
    private static final class ZoneNames {
        static final Map<String, String> CANONICAL = load();

        private static Map<String, String> load() {
            Map<String, String> names = new HashMap<>();
            for (String id : ZoneRulesProvider.getAvailableZoneIds()) {
                // the JDK still carries the SystemV names tz retired in 2020b
                if (!id.startsWith("SystemV/")) {
                    names.put(asciiLower(id), id);
                }
            }
            return names;
        }
    }

    /**
     * The source expression, verbatim. Canonicalization is not implemented;
     * per API.md, the built-in string conversion returns the source until it is.
     */
    @Override
    public String toString() {
        return source;
    }
}
