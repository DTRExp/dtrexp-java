package io.onury.dtrexp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A parsed DTRExp — Date-Time Range &amp; Recurrence Expression (draft 2.9).
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
     * {@code "Europe/Berlin"}. An unknown or empty identifier fails here, at once,
     * as {@link ZoneId#of(String)} does.
     */
    public DTRExp withZone(String zoneId) {
        return withZone(ZoneId.of(Objects.requireNonNull(zoneId, "zoneId")));
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

    /** Coverage with the zone given as an IANA identifier, e.g. {@code "Europe/Berlin"}. */
    public boolean covers(Instant instant, String zoneId) {
        return covers(instant, ZoneId.of(Objects.requireNonNull(zoneId, "zoneId")));
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
