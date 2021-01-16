
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
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
 * Any exception implies brokenness: if a {@link MuxableChannel} or any nested channel throws {@link IOException},
 * one should assume it (the channel that threw the exception) is no longer usable and should be closed.
 *
 * <p>
 * <b>Deadlock Requirements</b>
 *
 * <p>
 * Implementations may use varying strategies for multiplexing the nested channels over the underlying "real" channel, and
 * this may have subtle effects on behavior. For example, with implementations that multiplex over a single underlying TCP
 * stream, there may be situations where reading from nested channel A will block unless and until data is read from a sibling
 * nested channel B, etc. Nested channel blocking may also occur if the {@link BlockingQueue} returned by
 * {@link #getNestedChannelRequests} reaches its internal buffer capacity, etc. Any such restrictions or limitations
 * should be documented by the implementation.
 *
 * <p>
 * However, all implementations must avoid complete deadlock; more precisely: if, for every nested input channel
 * along with the {@link BlockingQueue} returned by {@link #getNestedChannelRequests}, there exists some {@link Thread}
 * currently polling for data, and there is any data is available, then at least one of those threads must immediately
 * awaken and return something.
 */
public interface MuxableChannel extends Channel {

    /**
     * Create a new nested channel, or pair of nested channels, scoped to this instance.
     *
     * <p>
     * The remote side will be notified by the appearance of a corresponding {@link NestedChannelRequest} in the
     * queue returned by {@link #getNestedChannelRequests} (with the input and output channels reversed, of course).
     * The delivery of requests on the remote side is guaranteed to preserve order.
     *
     * <p>
     * Note that it's possible to send a {@link NestedChannelRequest} but create neither input nor output channels.
     * This can be leveraged to send other control plane messages, etc.
     *
     * @param requestData data to supply to the remote side (via {@link NestedChannelRequest#getRequestData})
     * @param input true to include an input channel, false to include only an output channel
     * @param output true to include an output channel, false to include only an input channel
     * @return the newly created nested channel(s)
     * @throws IOException if an I/O error occurs
     * @throws ClosedChannelException if this instance is closed
     * @throws IllegalArgumentException if {@code requestData} is null
     */
    NestedChannelRequest newNestedChannelRequest(byte[] requestData, boolean input, boolean output) throws IOException;

    /**
     * Create a pair of nested input and output channels scoped to this instance.
     *
     * <p>
     * Equivalent to: {@link #newNestedChannelRequest(byte[], boolean, boolean) newNestedChannelRequest(requestData, true,true)}.
     *
     * @param requestData data to supply to the remote side (via {@link NestedChannelRequest#getRequestData})
     * @return the newly created nested channel(s)
     * @throws IOException if an I/O error occurs
     * @throws ClosedChannelException if this instance is closed
     * @throws IllegalArgumentException if {@code requestData} is null
     */
    default NestedChannelRequest newNestedChannelRequest(byte[] requestData) throws IOException {
        return this.newNestedChannelRequest(requestData, true, true);
    }

    /**
     * Access the queue of incoming {@link NestedChannelRequest}s initiated from the remote side.
     *
     * <p>
     * Each {@link NestedChannelRequest} corresponds to a remote invocation of {@link #newNestedChannelRequest}.
     * Moreover, the order of requests is preserved.
     *
     * <p>
     * Note: no special change happens to the returned {@link BlockingQueue} once this instance is {@link #close}'d;
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
