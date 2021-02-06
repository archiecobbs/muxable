
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import io.permazen.util.LongEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.dellroad.muxable.Directions;

/**
 * Output state machine for the {@link SimpleMuxableChannel} framing protocol.
 *
 * <p>
 * Instances are not thread safe.
 */
public class ProtocolWriter extends LoggingSupport {

    private final ChannelIds channelIds;                                        // channel ID tracker
    private final OutputHandler outputHandler;                                  // how we send bytes to the other side

    private State state = State.INITIAL;                                        // current state
    private boolean reentrantHandler;                                           // we are invoking the event handler

    /**
     * Constructor.
     *
     * @param channelIds channel ID tracker (should be shared with the {@link ProtocolReader})
     * @param outputHandler invoked when there are bytes to send
     * @throws IllegalArgumentException if {@code outputHandler} is null
     */
    public ProtocolWriter(ChannelIds channelIds, OutputHandler outputHandler) {
        if (channelIds == null)
            throw new IllegalArgumentException("null channelIds");
        if (outputHandler == null)
            throw new IllegalArgumentException("null outputHandler");
        this.channelIds = channelIds;
        this.outputHandler = outputHandler;
    }

    /**
     * Open a new local nested channel.
     *
     * <p>
     * Because the channel was created by the local side, the returned channel ID will always be positive.
     *
     * <p>
     * Any generated output will delivered to the configured {@link OutputHandler} synchronously (in the current thread).
     * However, this method must not be invoked re-entrantly.
     *
     * @param requestData the request data to send to the remote side
     * @param directions which I/O direction(s) are being established (from the local point of view)
     * @return the ID for the newly opened channel (always positive)
     * @throws IOException if thrown by the {@link OutputHandler}
     * @throws IllegalArgumentException if either parameter is null
     * @throws IllegalStateException if this method is invoked re-entrantly by the {@link OutputHandler}
     * @throws IllegalStateException if all 2<sup>63</sup> channel ID's have already been allocated
     */
    public long openNestedChannel(ByteBuffer requestData, Directions directions) throws IOException {

        // Sanity check
        if (requestData == null)
            throw new IllegalArgumentException("null requestData");
        if (directions == null)
            throw new IllegalArgumentException("null directions");
        if (this.reentrantHandler)
            throw new IllegalStateException("illegal re-entrant invocation");

        // Allocate new local channel ID
        final long channelId = this.channelIds.allocateLocalChannelId();

        // Send channel request to peer
        this.sendData(channelId, directions, requestData);

        // Done
        return channelId;
    }

    /**
     * Send data on the nested channel with the specified channel ID.
     *
     * <p>
     * The specified nested channel may have already been {@linkplain #closeNestedChannel closed} (this can happen
     * due to race conditions between the local side writing and the remote side closing); if so, no data is actually
     * sent and this method returns false.
     *
     * <p>
     * Generated output, if any, will delivered to the configured {@link OutputHandler} synchronously (in the current thread).
     * However, this method must not be invoked re-entrantly.
     *
     * @param channelId encoded channel ID (positive for local channels, negative for remote channels)
     * @param data the data to send
     * @return true if data was framed and written to the {@link OutputHandler}, false if the channel has already been closed
     * @throws IOException if thrown by the {@link OutputHandler}
     * @throws IllegalArgumentException if {@code channelId} is not a valid channel ID
     * @throws IllegalArgumentException if {@code data} is null
     * @throws IllegalStateException if {@link #closeConnection closeConnection()} has been invoked on this instance
     * @throws IllegalStateException if this method is invoked re-entrantly by the {@link OutputHandler}
     */
    public boolean writeNestedChannel(long channelId, ByteBuffer data) throws IOException {

        // Sanity check
        if (data == null)
            throw new IllegalArgumentException("null data");
        if (this.reentrantHandler)
            throw new IllegalStateException("illegal re-entrant invocation");
        if (this.state.equals(State.CLOSED))
            throw new IllegalStateException("connection is closed");

        // Check whether channel is still open
        if (!this.channelIds.isChannelOpen(channelId))
            return false;

        // Send data
        this.sendData(channelId, null, data);
        return true;
    }

    /**
     * Close an open nested channel.
     *
     * <p>
     * This sends a "close connection" frame to the peer, unless we know the already peer knows the channel is closed.
     *
     * <p>
     * Generated output, if any, will delivered to the configured {@link OutputHandler} synchronously (in the current thread).
     * However, this method must not be invoked re-entrantly.
     *
     * @param channelId encoded channel ID (positive for local channels, negative for remote channels)
     * @return true if a "close connection" frame was written to the {@link OutputHandler},
     *  false the peer already knows that the channel is closed
     * @throws IOException if thrown by the {@link OutputHandler}
     * @throws IllegalArgumentException if {@code channelId} is not the ID of an open nested channel
     * @throws IllegalStateException if {@link #closeConnection closeConnection()} has been invoked on this instance
     */
    public boolean closeNestedChannel(long channelId) throws IOException {

        // Sanity check
        if (this.reentrantHandler)
            throw new IllegalStateException("illegal re-entrant invocation");

        // Deallocate channel; if already deallocated nothing need be done
        final boolean closed = channelId < 0 ?
          this.channelIds.freeChannelId(-channelId, false) : this.channelIds.freeChannelId(channelId, true);
        if (closed)
            return false;

        // Send data
        this.sendData(channelId, null, null);
        return true;
    }

    /**
     * Close the entire connection.
     *
     * <p>
     * This sends a "close connection" frame to the peer, which closes the overall connection and all nested channels.
     *
     * <p>
     * Generated output will delivered to the configured {@link OutputHandler} synchronously (in the current thread).
     * However, this method must not be invoked re-entrantly.
     *
     * @throws IOException if thrown by the {@link OutputHandler}
     * @throws IllegalStateException if this method is invoked re-entrantly by the {@link OutputHandler}
     * @throws IllegalStateException if this method has already been invoked on this instance
     */
    public void closeConnection() throws IOException {

        // Sanity check
        if (this.reentrantHandler)
            throw new IllegalStateException("illegal re-entrant invocation");

        // Write a "close" frame
        this.sendData(0, null, null);
        this.state = State.CLOSED;
    }

// Internal methods

    @SuppressWarnings("fallthrough")
    private void sendData(long channelId, Directions directions, ByteBuffer payload) throws IOException {
        this.reentrantHandler = true;
        try {

            // Build initial header
            final ByteBuffer header = ByteBuffer.allocate(8 + 3 * LongEncoder.MAX_ENCODED_LENGTH + 1);
            switch (this.state) {
            case INITIAL:                                                               // send initial greeting
                header.putLong(ProtocolConstants.PROTOCOL_COOKIE);                      // big endian value
                LongEncoder.write(header, ProtocolConstants.CURRENT_PROTOCOL_VERSION);
                this.state = State.RUNNING;
                // FALLTHROUGH
            case RUNNING:                                                               // send channel ID and payload length
                LongEncoder.write(header, channelId);
                if (directions != null) {
                    final int flags;
                    switch (directions) {
                    case INPUT_ONLY:
                        flags = ProtocolConstants.FLAG_DIRECTION_INPUT;
                        break;
                    case OUTPUT_ONLY:
                        flags = ProtocolConstants.FLAG_DIRECTION_OUTPUT;
                        break;
                    case BIDIRECTIONAL:
                        flags = ProtocolConstants.FLAG_DIRECTION_INPUT | ProtocolConstants.FLAG_DIRECTION_OUTPUT;
                        break;
                    default:
                        throw new RuntimeException("internal error");
                    }
                    header.put((byte)flags);
                }
                LongEncoder.write(header, payload != null ? payload.remaining() : 0);
                break;
            case CLOSED:
                throw new IllegalStateException("connection is closed");
            default:
                throw new RuntimeException("internal error");
            }
            header.flip();

            // Send header
            this.debug("send header %s", this.toString(header, Integer.MAX_VALUE));
            this.outputHandler.sendOutput(header);

            // Send payload
            if (payload != null && payload.hasRemaining()) {
                this.debug("send payload %s", this.toString(payload, 64));
                this.outputHandler.sendOutput(payload);
            }
        } finally {
            this.reentrantHandler = false;
        }
    }

// OutputHandler

    @FunctionalInterface
    public interface OutputHandler {

        /**
         * Send raw data to the remote peer.
         *
         * @param data the data to send; this method may take ownership
         * @throws IOException if an I/O error occurs
         * @throws IllegalArgumentException if {@code data} is null
         */
        void sendOutput(ByteBuffer data) throws IOException;
    }

// State

    private enum State {
        INITIAL,
        RUNNING,
        CLOSED;
    }
}
