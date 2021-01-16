
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Represents nested channel(s) newly created by the remote side.
 *
 * <p>
 * The {@linkplain #getInput input} and {@linkplain #getOutput output} channels operate independently:
 * for example, closing one does not affect the other.
 */
public interface NestedChannelRequest {

    /**
     * Get the parent {@link MuxableChannel}.
     *
     * @return the {@link MuxableChannel} associated with this request
     */
    MuxableChannel getParent();

    /**
     * Get the request data associated with this request.
     *
     * @return the request data provided by the remote side via
     * {@link MuxableChannel#newNestedChannelRequest MuxableChannel.newNestedChannelRequest()}.
     */
    byte[] getRequestData();

    /**
     * Get the input channel, if any.
     *
     * <p>
     * The returned channel will typically also implement {@link InterruptibleChannel}, but this is implementation-dependent.
     *
     * <p>
     * In some implementations, {@link #getInput} and {@link #getOutput} may return the same channel; this is explicitly permitted.
     *
     * @return the input channel associated with this instance, or null if this instance was created with only an output channel
     */
    ReadableByteChannel getInput();

    /**
     * Get the output channel, if any.
     *
     * <p>
     * The returned channel will typically also implement {@link InterruptibleChannel}, but this is implementation-dependent.
     *
     * <p>
     * In some implementations, {@link #getInput} and {@link #getOutput} may return the same channel; this is explicitly permitted.
     *
     * @return the output channel associated with this instance, or null if this instance was created with only an input channel
     */
    WritableByteChannel getOutput();
}
