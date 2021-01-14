
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;

/**
 * A single connection of some sort that supports the creation of multiple nested {@link Channel}s.
 *
 * <p>
 * The nested channels operate independently, but are all scoped to the parent {@link MuxableChannel}.
 * If the parent {@link MuxableChannel} is disconnected or closed, the same immediately happens to all nested channels.
 *
 * <p>
 * Implementations may use different strategies that affect behavior. For example, in implementations that multiplex
 * over a single underlying "real" channel, some channel A could block until data is first read from some channel B, etc.
 */
public interface MuxableChannel extends Channel {

    /**
     * Create a new {@link NestedChannel} scoped this instance.
     *
     * @throws ClosedChannelException if this instance is already closed
     */
    NestedChannel newNestedChannel();
}
