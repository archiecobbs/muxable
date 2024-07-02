/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.SelectorProvider;

/**
 * Represents a request from one end of a {@link MuxableChannel} to the other to create a new nested channel.
 *
 * <p>
 * Instances provide access to the input and output I/O streams associated with the new nested channel, as well
 * any application-specific {@code byte[]} data associated with the request by the initiator.
 *
 * <p>
 * The {@linkplain #getInput input} and {@linkplain #getOutput output} function independently, but they are
 * considered part of the same connected nested channel. Unlike TCP sockets, they do not support shutting
 * down only one direction: closing either {@linkplain #getInput input} or {@linkplain #getOutput output}
 * results in both channels being closed.
 *
 * @param <I> input channel type
 * @param <O> output channel type
 * @see MuxableChannel#newNestedChannelRequest(ByteBuffer, Directions) MuxableChannel.newNestedChannelRequest()
 * @see MuxableChannel#getNestedChannelRequests
 */
public interface NestedChannelRequest<I extends SelectableChannel & ReadableByteChannel,
  O extends SelectableChannel & WritableByteChannel> {

    /**
     * Get the parent {@link MuxableChannel}.
     *
     * @return the {@link MuxableChannel} associated with this request
     */
    MuxableChannel<I, O> getParent();

    /**
     * Get the request data associated with this request.
     *
     * @return the request data provided by the remote side via
     * {@link MuxableChannel#newNestedChannelRequest MuxableChannel.newNestedChannelRequest()}.
     */
    ByteBuffer getRequestData();

    /**
     * Get the input channel, if any. This corresponds to the output channel on the remote side.
     *
     * <p>
     * It's possible that the returned channel requires a non-default {@link SelectorProvider}; if so, that must be
     * documented by the implementation. Unless specified otherwise, the default {@link SelectorProvider} may be assumed.
     * In any case, the channels returned by {@link #getInput} and {@link #getOutput} must share the same {@link SelectorProvider}.
     *
     * <p>
     * Closing this channel also closes the channel returned by {@link #getOutput}.
     *
     * <p>
     * In some implementations, {@link #getInput} and {@link #getOutput} may return the same channel; this is explicitly permitted.
     *
     * @return the input channel associated with this instance, or null if this instance was created with only an output channel
     */
    I getInput();

    /**
     * Get the output channel, if any. This corresponds to the input channel on the remote side.
     *
     * <p>
     * It's possible that the returned channel requires a non-default {@link SelectorProvider}; if so, that must be
     * documented by the implementation. Unless specified otherwise, the default {@link SelectorProvider} may be assumed.
     * In any case, the channels returned by {@link #getInput} and {@link #getOutput} must share the same {@link SelectorProvider}.
     *
     * <p>
     * Closing this channel also closes the channel returned by {@link #getInput}.
     *
     * <p>
     * In some implementations, {@link #getInput} and {@link #getOutput} may return the same channel; this is explicitly permitted.
     *
     * @return the output channel associated with this instance, or null if this instance was created with only an input channel
     */
    O getOutput();
}
