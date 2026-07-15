package io.onury.dtrexp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * A parsed DTRExp — Date-Time Range &amp; Recurrence Expression (draft 2.8).
 *
 * <p>A DTRExp denotes a possibly infinite set of time intervals and is
 * evaluated for <em>coverage</em>: {@link #covers(Instant, ZoneId)} answers
 * "is this instant inside the set?" in O(#components) after one calendar
 * field extraction. Expressions are time-zone agnostic; the zone is an
 * evaluation parameter (default UTC).</p>
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

    private DTRExp(String source, List<Ast.Expr> exprs, List<DTRExpWarning> warnings) {
        this.source = source;
        this.exprs = exprs;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * Parses an expression, throwing a positioned {@link DTRExpParseException}
     * on any syntax or static-validity error. Non-fatal findings are exposed
     * via {@link #warnings()}.
     */
    public static DTRExp parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        Parser.Parsed parsed = Parser.parse(expression);
        return new DTRExp(expression, parsed.exprs(), parsed.warnings());
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

    /** Coverage in UTC (the default evaluation zone). */
    public boolean covers(Instant instant) {
        return covers(instant, ZoneOffset.UTC);
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
