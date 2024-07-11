/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;

import org.dellroad.muxable.MuxableChannel;
import org.dellroad.muxable.NestedChannel;

/**
 * A straightforward implementation of most of the {@link NestedChannel} interface.
 *
 * @param <I> input channel type
 * @param <O> output channel type
 */
public abstract class AbstractNestedChannel<
  I extends SelectableChannel & ReadableByteChannel,
  O extends SelectableChannel & WritableByteChannel>
    implements NestedChannel<I, O> {

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
    protected AbstractNestedChannel(MuxableChannel<I, O> parent, I input, O output, ByteBuffer requestData) {
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

// NestedChannel

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

// Object

    @Override
    public String toString() {
        return this.getClass().getSimpleName()
          + "[data=" + LoggingSupport.toString(this.requestData, 64) + "]";
    }
}
