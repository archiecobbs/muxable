
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import org.dellroad.stuff.util.LongSet;

/**
 * Tracks channel ID's for the {@link SimpleMuxableChannel} framing protocol.
 *
 * <p>
 * A shared instance of this class should be provided to both the {@link ProtocolReader} and {@link ProtocolWriter}.
 *
 * <p>
 * Valid channel ID's are range from one to {@code Long.MAX_VALUE}. Each side of the connection has its own channel ID
 * namespace. In some cases, when both local and remote channel ID's are being passe around, remote channel ID's are negated
 * to avoid confusion (but this should always be clearly documented). Channel ID's are never reused.
 *
 * <p>
 * Instances are thread safe.
 */
public class ChannelIds {

    private final LongSet openChannelIds = new LongSet();           // ID's of all unclosed channels (local and remote)

    private long prevLocalChannelId;                                // the previous local channel ID allocated
    private long prevRemoteChannelId;                               // the previous remote channel ID allocated

    /**
     * Allocate a new local channel ID.
     *
     * @return new local channel ID (always positive)
     * @throws IllegalStateException if all 2<sup>63</sup> channel ID's have already been allocated
     */
    public synchronized long allocateLocalChannelId() {
        if (this.prevLocalChannelId == Long.MAX_VALUE)
            throw new IllegalStateException("channel ID's exhausted");
        final long channelId = ++this.prevLocalChannelId;
        this.openChannelIds.add(channelId);
        return channelId;
    }

    /**
     * Validate a local channel ID.
     *
     * <p>
     * This verifies that {@code channelId} is valid and represents a channel allocated via {@link #allocateLocalChannelId}.
     *
     * @param channelId local channel ID
     * @return true if {@code channelId} is valid and open, false if valid but released by {@link #freeChannelId freeChannelId()}
     * @throws IllegalArgumentException if {@code channelId} is non-positive or not yet allocated
     */
    public synchronized boolean validateLocalChannelId(final long channelId) {
        if (channelId < 1 || channelId > this.prevLocalChannelId) {
            throw new IllegalArgumentException(String.format("invalid %s channel ID %d: not in the range %d..%d",
               "local", channelId, 1, this.prevLocalChannelId));
        }
        return this.openChannelIds.contains(channelId);
    }

    /**
     * Allocate a remote channel ID, if {@code channelId} represents the next available remote channel ID to be allocated.
     *
     * <p>
     * If {@code channelId} represents the next available remote channel ID, then allocate it and return true.
     * Otherwise, return false.
     *
     * @param channelId remote channel ID
     * @return true if {@code channelId} is the next available remote channel ID which was also just allocated, otherwise false
     * @throws IllegalArgumentException if {@code channelId} is non-positive
     */
    public synchronized boolean allocateRemoteChannelId(final long channelId) {
        if (this.prevRemoteChannelId == Long.MAX_VALUE || channelId != this.prevRemoteChannelId + 1)
            return false;
        this.prevRemoteChannelId++;
        this.openChannelIds.add(channelId);
        return true;
    }

    /**
     * Validate a remote channel ID.
     *
     * <p>
     * This verifies that {@code channelId} is valid and represents a channel allocated via {@link #allocateRemoteChannelId}.
     *
     * @param channelId remote channel ID
     * @return true if {@code channelId} is valid and open, false if valid but released by {@link #freeChannelId freeChannelId()}
     * @throws IllegalArgumentException if {@code channelId} is non-positive or not yet allocated
     */
    public synchronized boolean validateRemoteChannelId(final long channelId) {
        if (channelId < 1 || channelId > this.prevRemoteChannelId) {
            throw new IllegalArgumentException(String.format("invalid %s channel ID %d: not in the range %d..%d",
               "remote", channelId, 1, this.prevRemoteChannelId));
        }
        return this.openChannelIds.contains(-channelId);
    }

    /**
     * Deallocate (i.e., mark as closed) a channel ID previously allocated by
     * {@link #allocateLocalChannelId allocateLocalChannelId()} or {@link #allocateRemoteChannelId allocateRemoteChannelId()}.
     *
     * @param channelId local channel ID
     * @param local true if local channel ID, false if remote channel ID
     * @return true if channel was actually open, false if channel was already closed
     * @throws IllegalArgumentException if {@code channelId} is non-positive, never allocated, or already closed
     */
    public synchronized boolean freeChannelId(long channelId, boolean local) {
        if (channelId < 1)
            throw new IllegalArgumentException(String.format("invalid %s channel ID %d", local ? "local" : "remote", channelId));
        return this.openChannelIds.remove(local ? channelId : -channelId);
    }

    /**
     * Determine whether the specified channel is still open.
     *
     * @param channelId encoded channel ID (positive for local, negative for remote)
     * @return true if channel is still open, false if channel has been closed
     * @throws IllegalArgumentException if {@code channelId} is invalid or not yet allocated
     */
    public synchronized boolean isChannelOpen(long channelId) {
        if (channelId == 0 || channelId == Long.MIN_VALUE)
            throw new IllegalArgumentException(String.format("invalid channel ID %d", channelId));
        return channelId < 0 ? this.validateRemoteChannelId(-channelId) : this.validateLocalChannelId(channelId);
    }

    /**
     * Get all open channel ID's.
     *
     * <p>
     * The returned set contains both local and remote channels, where remote channels are encoded
     * as the negatives of their actual values.
     *
     * <p>
     * The returned set is a copy; modifications do not affect this instance.
     *
     * @return all open channel ID's, where remote channel ID's are encoded by negation
     */
    public LongSet getOpenChannelIds() {
        return this.openChannelIds.clone();
    }
}
