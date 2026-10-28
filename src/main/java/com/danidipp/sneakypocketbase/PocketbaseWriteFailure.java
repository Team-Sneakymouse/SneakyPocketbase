package com.danidipp.sneakypocketbase;

import java.io.IOException;
import java.util.Objects;

/** A conservative create outcome. The cause is diagnostic only and may contain sensitive data. */
public final class PocketbaseWriteFailure extends IOException {
    /** Whether the manager can prove that a create did not apply. */
    public enum Outcome {
        /** The operation was not started; no HTTP create was attempted. */
        NOT_SENT,
        /** A standard PocketBase validation/authentication/authorization rejection (400/401/403). */
        REJECTED,
        /** The operation may have applied. Reconcile; do not blindly resend. */
        UNKNOWN
    }

    private final Outcome outcome;
    private final Integer statusCode;

    /** Builds a sanitized failure without copying any cause message or response body. */
    public PocketbaseWriteFailure(Outcome outcome, Integer statusCode, Throwable cause) {
        super("PocketBase create failed (" + Objects.requireNonNull(outcome) + ")", cause);
        this.outcome = outcome;
        this.statusCode = statusCode;
    }

    public Outcome getOutcome() { return outcome; }

    /** Returns the HTTP status if known, otherwise null. */
    public Integer getStatusCode() { return statusCode; }
}
