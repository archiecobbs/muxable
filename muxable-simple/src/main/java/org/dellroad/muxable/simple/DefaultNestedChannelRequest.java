/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;

import org.dellroad.muxable.MuxableChannel;
import org.dellroad.muxable.NestedChannelRequest;

/**
 * A straightforward implementation of the {@link NestedChannelRequest} interface.
 *
 * @param <I> input channel type
 * @param <O> output channel type
 */
public class DefaultNestedChannelRequest<
  I extends SelectableChannel & ReadableByteChannel,
  O extends SelectableChannel & WritableByteChannel>
    implements NestedChannelRequest<I, O> {

    protected final MuxableChannel<I, O> parent;
    protected final ByteBuffer requestData;
    protected final I input;
    protected final O output;

    /**
     * Constructor.
     *
     * @param parent parent channel
     * @param input nested channel input provided by {@code parent}
     * @param output nested channel output provided by {@code parent}
     * @param requestData nested channel request data
     * @throws IllegalArgumentException if {@code parent} is null
     * @throws IllegalArgumentException if {@code input} and {@code output} are both null
     * @throws IllegalArgumentException if {@code requestData} is null
     */
    public DefaultNestedChannelRequest(MuxableChannel<I, O> parent, I input, O output, ByteBuffer requestData) {
        if (parent == null)
            throw new IllegalArgumentException("null parent");
        if (input == null && output == null)
            throw new IllegalArgumentException("null input and output");
        if (requestData == null)
            throw new IllegalArgumentException("null requestData");
        this.parent = parent;
        this.input = input;
        this.output = output;
        this.requestData = requestData;
    }

// NestedChannelRequest

    @Override
    public MuxableChannel<I, O> getParent() {
        return this.parent;
    }

    @Override
    public I getInput() {
        return this.input;
    }

    @Override
    public O getOutput() {
        return this.output;
    }

    @Override
    public ByteBuffer getRequestData() {
        return this.requestData;
    }
}
