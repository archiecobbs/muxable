
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

/**
 * Used to specifify which of input and/or output directions are desired.
 *
 * @see MuxableChannel#newNestedChannelRequest(ByteBuffer, Directions)
 */
public enum Directions {

    /**
     * There should be an input channel but no output channel.
     */
    INPUT_ONLY,

    /**
     * There should be an output channel but no input channel.
     */
    OUTPUT_ONLY,

    /**
     * There should be both input and output channels.
     */
    BIDIRECTIONAL;

    /**
     * Determine whether this instance includes an input channel.
     *
     * @return true if an input channel is included
     */
    public boolean hasInput() {
        return this != OUTPUT_ONLY;
    }

    /**
     * Determine whether this instance includes an output channel.
     *
     * @return true if an output channel is included
     */
    public boolean hasOutput() {
        return this != INPUT_ONLY;
    }
}
