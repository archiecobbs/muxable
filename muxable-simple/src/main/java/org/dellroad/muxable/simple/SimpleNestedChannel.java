/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;

import org.dellroad.muxable.NestedChannel;

/**
 * {@link NestedChannel} implementation used by {@link SimpleMuxableChannel}.
 */
public class SimpleNestedChannel extends AbstractNestedChannel<Pipe.SourceChannel, Pipe.SinkChannel> {

    final long channelId;

    SimpleNestedChannel(SimpleMuxableChannel parent, long channelId,
      Pipe.SourceChannel input, Pipe.SinkChannel output, ByteBuffer requestData) {
        super(parent, input, output, requestData);
        this.channelId = channelId;
    }

    @Override
    public SimpleMuxableChannel getParent() {
        return (SimpleMuxableChannel)this.parent;
    }

    @Override
    public void close() {
        this.getParent().handleNestedChannelClosed(this.channelId);
    }
}
