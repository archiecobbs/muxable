/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.SelectorProvider;

/**
 * Represents one nested I/O channel within a {@link MuxableChannel}.
 *
 * <p>
 * Instances provide access to the input and output I/O streams associated with the channel, as well
 * any application-specific {@code byte[]} data sent by the initiator when creating the channel.
 *
 * <p>
 * The {@linkplain #getInput input} and {@linkplain #getOutput output} function independently, but they are
 * considered part of the same connected nested channel. Unlike TCP sockets, they do not support shutting
 * down only one direction: closing either {@linkplain #getInput input} or {@linkplain #getOutput output}
 * may result in both channels being rendered unusable.
 *
 * <p>
 * To close a {@link NestedChannel}, invoke {@link #close}. This implicitly also closes the input and output channels.
 *
 * @param <I> input channel type
 * @param <O> output channel type
 * @see MuxableChannel#newNestedChannel(ByteBuffer, Directions) MuxableChannel.newNestedChannel()
 * @see MuxableChannel#getNestedChannelRequests
 */
public interface NestedChannel<
    I extends SelectableChannel & ReadableByteChannel,
    O extends SelectableChannel & WritableByteChannel>
  extends Closeable {

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
     * {@link MuxableChannel#newNestedChannel MuxableChannel.newNestedChannel()}.
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
     * Closing this channel also closes the channel returned by {@link #getOutput}, if any.
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
     * Closing this channel also closes the channel returned by {@link #getInput}, if any.
     *
     * <p>
     * In some implementations, {@link #getInput} and {@link #getOutput} may return the same channel; this is explicitly permitted.
     *
     * @return the output channel associated with this instance, or null if this instance was created with only an input channel
     */
    O getOutput();

    /**
     * Close this nested channel.
     *
     * <p>
     * When this method is invoked, it is not necessary to also close the input and output channels.
     */
    @Override
    void close();
}
