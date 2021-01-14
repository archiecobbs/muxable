
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * A pair of I/O channels spawned from a {@link MuxableChannel}.
 */
public interface NestedChannel {

    /**
     * Get the parent {@link MuxableChannel}.
     */
    MuxableChannel getParent();

    /**
     * Get the input channel.
     *
     * <p>
     * The returned channel will normally also implement {@link InterruptibleChannel}.
     */
    ReadableByteChannel getInput();

    /**
     * Get the output channel.
     *
     * <p>
     * The returned channel will normally also implement {@link InterruptibleChannel}.
     */
    WritableByteChannel getOutput();
}
