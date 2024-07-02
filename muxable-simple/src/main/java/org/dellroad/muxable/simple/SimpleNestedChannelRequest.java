/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;

import org.dellroad.muxable.NestedChannelRequest;

/**
 * {@link NestedChannelRequest} implementation used by {@link SimpleMuxableChannel}.
 */
public class SimpleNestedChannelRequest extends DefaultNestedChannelRequest<Pipe.SourceChannel, Pipe.SinkChannel> {

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
    public SimpleNestedChannelRequest(SimpleMuxableChannel parent,
      Pipe.SourceChannel input, Pipe.SinkChannel output, ByteBuffer requestData) {
        super(parent, input, output, requestData);
    }

    @Override
    public SimpleMuxableChannel getParent() {
        return (SimpleMuxableChannel)this.parent;
    }
}
