
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Selector;
import java.util.concurrent.BlockingQueue;

/**
 * A single channel supporting parallel nested byte-oriented channels operating independently.
 *
 * <p>
 * The nested "child" channels are byte-oriented and operate independently, and all scoped to the parent {@link MuxableChannel}.
 * In other words, if a parent is closed, all of its nested channels are also implicitly closed; on the other hand,
 * if a nested channel is closed, only that nested channel is affected, and its parent and siblings are unaffected.
 *
 * <p>
 * Nested channels are bidirectional. Unlike TCP sockets, they do not support shutting down only one direction.
 * Closing either input or output channel results in both channels being closed.
 *
 * <p>
 * Any exception implies brokenness: if a {@link MuxableChannel} or any nested channel throws {@link IOException},
 * one should assume it (the channel that threw the exception) is no longer usable and should be closed.
 *
 * <p>
 * <b>Deadlock Requirements</b>
 *
 * <p>
 * Implementations may use varying strategies for multiplexing the nested channels over the underlying "real" channel, and
 * this may have subtle effects on behavior. For example, with implementations that multiplex over a single underlying TCP
 * stream, there may be situations where, after certain internal buffer limits are reached, further attempts to read from
 * nested channel A will block unless and until more data is read from a sibling nested channel B, etc. Nested channels
 * might also block if the {@link BlockingQueue} returned by {@link #getNestedChannelRequests} reaches its internal buffer
 * capacity, etc. In any case, such restrictions or limitations should be clearly documented by the implementation.
 *
 * <p>
 * However, all implementations must avoid "senseless deadlock"; more precisely: if, for every nested input channel
 * along with the {@link BlockingQueue} returned by {@link #getNestedChannelRequests}, there exists some {@link Thread}
 * or {@link Selector} currently polling for data, and there is any data is available, then at least one must become
 * readable and provide new data.
 */
public interface MuxableChannel extends Channel {

    /**
     * Create a new nested channel, or pair of nested channels, scoped to this instance.
     *
     * <p>
     * The remote side will be notified by the appearance of a corresponding {@link NestedChannelRequest} in the
     * queue returned by {@link #getNestedChannelRequests} (with the input and output channels reversed, of course).
     * The delivery of requests on the remote side is guaranteed to preserve order (but only to the extent order
     * is well-defined on the sending side, i.e., there is a "happens before" relationship).
     *
     * @param requestData data to supply to the remote side (via {@link NestedChannelRequest#getRequestData})
     * @param directions which of input and/or output to create
     * @return the newly created nested channel(s)
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if either parameter is null
     */
    NestedChannelRequest newNestedChannelRequest(ByteBuffer requestData, Directions directions) throws IOException;

    /**
     * Create a pair of nested input and output channels scoped to this instance.
     *
     * <p>
     * Equivalent to: {@link #newNestedChannelRequest(ByteBuffer, Directions)
     *  newNestedChannelRequest(requestData, Directions.BIDIRECTIONAL)}.
     *
     * @param requestData data to supply to the remote side (via {@link NestedChannelRequest#getRequestData})
     * @return the newly created nested channel(s)
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if {@code requestData} is null
     */
    default NestedChannelRequest newNestedChannelRequest(ByteBuffer requestData) throws IOException {
        return this.newNestedChannelRequest(requestData, Directions.BIDIRECTIONAL);
    }

    /**
     * Access the queue of incoming {@link NestedChannelRequest}s initiated from the remote side.
     *
     * <p>
     * Each {@link NestedChannelRequest} corresponds to a remote invocation of
     * {@link #newNestedChannelRequest newNestedChannelRequest()}. Moreover, the order of requests is preserved.
     *
     * <p>
     * Because the remote side can close a nested channel at any time, it is possible that a {@link NestedChannelRequest}
     * is already closed by the time it's pulled from this queue. In this case, the input and output channels
     * will throw {@link IOException} on first access.
     *
     * <p>
     * Note: no special change happens to the returned {@link BlockingQueue} once this instance is closed;
     * instead, new reqeusts simply stop appearing. Therefore, after closing this instance, any thread(s)
     * that are blocked polling for new data may need to be woken up via {@link Thread#interrupt}.
     *
     * @return queue of {@link NestedChannelRequest} initiated from the remote side
     */
    BlockingQueue<NestedChannelRequest> getNestedChannelRequests();

    /**
     * Close this instance.
     *
     * <p>
     * All outstanding nested channels are also implicitly closed.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    void close() throws IOException;
}
