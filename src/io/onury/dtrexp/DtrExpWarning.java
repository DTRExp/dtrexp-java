package io.onury.dtrexp;

/**
 * A non-fatal validation finding (spec section 9.1): the expression parses,
 * but is statically unsatisfiable or otherwise suspicious.
 *
 * @param position zero-based character offset of the offending component
 * @param message  human-readable description
 */
public record DtrExpWarning(int position, String message) {

    @Override
    public String toString() {
        return message + " (at " + position + ")";
    }
}
