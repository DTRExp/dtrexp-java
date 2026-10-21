package io.onury.dtrexp;

import java.io.Serial;

/**
 * Thrown when a DTR expression fails to parse or fails static validation.
 * Carries the position of the token at fault: the 0-based offset, in Unicode
 * code points, of its first character (spec section 8).
 */
public final class DTRExpParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int position;

    public DTRExpParseException(int position, String message) {
        super(message + " (at " + position + ")");
        this.position = position;
    }

    /**
     * The 0-based code-point offset of the token at fault. Every character
     * before it is ASCII, so it is also the {@code String} index.
     */
    public int position() {
        return position;
    }
}
