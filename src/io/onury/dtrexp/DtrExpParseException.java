package io.onury.dtrexp;

import java.io.Serial;

/**
 * Thrown when a DTRExp expression fails to parse or fails static validation.
 * Carries the zero-based character position in the source string at which the
 * problem was detected.
 */
public final class DtrExpParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int position;

    public DtrExpParseException(int position, String message) {
        super(message + " (at " + position + ")");
        this.position = position;
    }

    /** Zero-based character offset into the source expression. */
    public int position() {
        return position;
    }
}
