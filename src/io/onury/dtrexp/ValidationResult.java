package io.onury.dtrexp;

import java.util.List;

/**
 * Result of {@link DTRExp#validate(String)}: positioned parse errors (empty
 * when valid — parse failure is the only failure, so at most one) or a
 * successful parse with zero or more warnings.
 *
 * @param valid    whether the expression parsed
 * @param errors   positioned parse errors; empty when valid
 * @param warnings static-analysis warnings (empty when invalid or clean)
 */
public record ValidationResult(boolean valid, List<DTRExpParseException> errors, List<DTRExpWarning> warnings) {

    public ValidationResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
