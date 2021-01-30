
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

/**
 * Exception thrown when a {@link SimpleMuxableChannel} framing protocol violation is detected.
 */
public class ProtocolViolationException extends IllegalArgumentException {

    private static final long serialVersionUID = 6972726186447031873L;

    private final long offset;

    /**
     * Constructor.
     *
     * @param offset the absolute offset in the input stream where the violation occurred
     * @param message description of the protocol violation
     */
    public ProtocolViolationException(long offset, String message) {
        super(message);
        this.offset = offset;
    }

    /**
     * Constructor.
     *
     * @param offset the absolute offset in the input stream where the violation occurred
     * @param message description of the protocol violation
     * @param cause underlying cause of the problem
     */
    public ProtocolViolationException(long offset, String message, Throwable cause) {
        super(message, cause);
        this.offset = offset;
    }

    /**
     * Get the absolute position in the input stream at which the protocol violation was detected.
     *
     * @return violation offset
     */
    public long getOffset() {
        return this.offset;
    }
}
