
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.dellroad.muxable.MuxableChannel;
import org.dellroad.muxable.NestedChannelRequest;

/**
 * A straightforward implementation of the {@link NestedChannelRequest} interface.
 */
public class SimpleNestedChannelRequest implements NestedChannelRequest {

    private final MuxableChannel parent;
    private final ByteBuffer requestData;
    private final ReadableByteChannel input;
    private final WritableByteChannel output;

    /**
     * Constructor.
     *
     * @param parent parent channel
     * @param input nested channel input provided by {@code parent}
     * @param output nested channel output provided by {@code parent}
     * @param requestData nested channel request data
     * @throws IllegalArgumentException if any parameter is null
     */
    public SimpleNestedChannelRequest(MuxableChannel parent,
      ReadableByteChannel input, WritableByteChannel output, ByteBuffer requestData) {
        if (parent == null)
            throw new IllegalArgumentException("null parent");
        if (input == null)
            throw new IllegalArgumentException("null input");
        if (output == null)
            throw new IllegalArgumentException("null output");
        if (requestData == null)
            throw new IllegalArgumentException("null requestData");
        this.parent = parent;
        this.input = input;
        this.output = output;
        this.requestData = requestData;
    }

    /**
     * Convenience constructor for when the nested channel I/O is accessed via a {@link Pipe}.
     *
     * @param parent parent channel
     * @param pipe nested channel I/O provided by {@code parent}
     * @param requestData nested channel request data
     * @throws IllegalArgumentException if any parameter is null
     */
    public SimpleNestedChannelRequest(MuxableChannel parent, Pipe pipe, ByteBuffer requestData) {
        this(parent, pipe != null ? pipe.source() : null, pipe != null ? pipe.sink() : null, requestData);
    }

// NestedChannelRequest

    @Override
    public MuxableChannel getParent() {
        return this.parent;
    }

    @Override
    public ReadableByteChannel getInput() {
        return this.input;
    }

    @Override
    public WritableByteChannel getOutput() {
        return this.output;
    }

    @Override
    public ByteBuffer getRequestData() {
        return this.requestData;
    }
}
