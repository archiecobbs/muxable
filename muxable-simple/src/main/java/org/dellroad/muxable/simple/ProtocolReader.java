
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import io.permazen.util.LongEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.dellroad.muxable.Directions;

/**
 * Input state machine for the {@link SimpleMuxableChannel} framing protocol.
 *
 * <p>
 * Instances are not thread safe.
 */
public class ProtocolReader {

    // Configuration setup
    private final ChannelIds channelIds;                                                // channel ID tracker
    private final InputHandler inputHandler;                                            // callback object

    // Stream position tracking
    private long offset;                                                                // total number of bytes read so far

    // Long value buffer/decoding
    private final ByteBuffer longValueBuffer                                            // buffers a long value, possibly encoded
      = ByteBuffer.allocate(LongEncoder.MAX_ENCODED_LENGTH);
    private long longValueOffset;                                                       // offset of start of long value
    private long longValue;                                                             // the long value once completed

    // Incoming payload info
    private long payloadChannelId;                                                      // payload's channel ID (always positive)
    private boolean payloadChannelIdIsLocal;                                            // true if channel ID is a local channel
    private boolean newChannelRequest;                                                  // payload is a new channel request
    private Directions newChannelDirections;                                            // new channel request input and/or output
    private ByteBuffer payloadBuffer;                                                   // payload data buffer

    // State machine state
    private State state = State.READING_PROTOCOL_COOKIE;                                // state machine current state
    private ProtocolViolationException violation;                                       // previous protocol violation, if any
    private boolean reentrantHandler;                                                   // we are invoking the input handler

    /**
     * Constructor.
     *
     * @param channelIds channel ID tracker (should be shared with the {@link ProtocolWriter})
     * @param inputHandler callback interface for generated events
     * @throws IllegalArgumentException if {@code inputHandler} is null
     */
    public ProtocolReader(ChannelIds channelIds, InputHandler inputHandler) {
        if (channelIds == null)
            throw new IllegalArgumentException("null channelIds");
        if (inputHandler == null)
            throw new IllegalArgumentException("null inputHandler");
        this.channelIds = channelIds;
        this.inputHandler = inputHandler;
    }

    /**
     * Input new data from the remote side into the protocol state machine.
     *
     * <p>
     * Any generated events will be delivered to the configured {@link InputHandler} synchronously (in the current thread).
     * However, this method must not be invoked re-entrantly.
     *
     * <p>
     * If a protocol violation is detected, {@link ProtocolViolationException} is thrown and this instance becomes
     * unusable (any further invocations of this method will result in an {@link IllegalStateException}).
     *
     * <p>
     * For performance reasons, this instance assumes it may take ownership of {@code data}, i.e., retain a reference,
     * modify, and potentially pass {@code data} back to the {@link InputHandler} at some later point.
     *
     * <p>
     * This method consumes all of {@code data}, unless a "close connection" frame is read; in that case, any data
     * after the "close connection" frame will remain in {@code data}, this method will return false, and any subsequent
     * invocations of this method will throw {@link IllegalStateException}.
     *
     * @param data new data received
     * @return normally true, or false if a "close connection" frame is read
     * @throws IOException if the {@link InputHandler} does
     * @throws ProtocolViolationException if the input violates the framing protocol
     * @throws IllegalArgumentException if {@code data} is null
     * @throws IllegalStateException if this method is invoked re-entrantly by the {@link InputHandler}
     * @throws IllegalStateException if this instance has previously encountered a protocol violation
     * @throws IllegalStateException if the remote instance has closed the connection
     */
    public boolean input(ByteBuffer data) throws IOException {

        // Sanity check
        if (this.violation != null)
            throw new IllegalStateException("a protocol violation has already occurred", this.violation);
        if (this.reentrantHandler)
            throw new IllegalStateException("illegal re-entrant invocation");

        // Consume the data until if/when a "close connection" frame is seen
        while (data.hasRemaining()) {
            if (!this.state.inputData(this, data))
                return false;
        }

        // Done
        return true;
    }

// State Machine

    // State READING_PROTOCOL_COOKIE
    private boolean inputProtocolCookieData(ByteBuffer data) {

        // Decode plain (big endian) long value
        if (!this.inputPlainLongValueData(data))
            return true;

        // Validate protocol cookie
        final long protocolCookie = this.longValue;
        if (protocolCookie != ProtocolConstants.PROTOCOL_COOKIE) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd invalid protocol cookie 0x%016x != 0x%016x", protocolCookie, ProtocolConstants.PROTOCOL_COOKIE));
        }

        // Proceed
        this.state = State.READING_PROTOCOL_VERSION;
        return true;
    }

    // State READING_PROTOCOL_VERSION
    private boolean inputProtocolVersionData(ByteBuffer data) {

        // Decode encoded long value
        if (!this.inputEncodedLongValueData(data))
            return true;

        // Validate protocol version
        final long protocolVersion = this.longValue;
        if (protocolVersion != ProtocolConstants.CURRENT_PROTOCOL_VERSION) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd unsupported protocol version %d (current is %d)",
               protocolVersion, ProtocolConstants.CURRENT_PROTOCOL_VERSION));
        }

        // Proceed
        this.state = State.READING_CHANNEL_ID;
        return true;
    }

    // State READING_CHANNEL_ID
    private boolean inputChannelIdData(ByteBuffer data) {

        // Decode encoded long value
        if (!this.inputEncodedLongValueData(data))
            return true;

        // Zero means close the whole thing down
        if (this.longValue == 0) {
            this.state = State.CLOSED;
            return false;
        }

        // Validate channel ID
        if (this.longValue == Long.MIN_VALUE) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd invalid frame: invalid encoded channel ID %d", this.longValue));
        }

        // Value was encoded by peer, so negative & positive are swapped
        this.payloadChannelIdIsLocal = this.longValue < 0;
        this.payloadChannelId = Math.abs(this.longValue);

        // Handle local channel ID vs. remote channel ID
        try {
            if (this.payloadChannelIdIsLocal) {
                this.newChannelRequest = false;
                this.channelIds.validateLocalChannelId(this.payloadChannelId);
            } else {
                this.newChannelRequest = this.channelIds.allocateRemoteChannelId(this.payloadChannelId);
                if (this.newChannelRequest) {
                    this.state = State.READING_DIRECTIONS;
                    return true;
                }
                this.channelIds.validateRemoteChannelId(this.payloadChannelId);
            }
        } catch (IllegalArgumentException e) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd invalid frame: %s", e.getMessage()));
        }

        // Proceed
        this.state = State.READING_PAYLOAD_LENGTH;
        return true;
    }

    // State READING_DIRECTIONS
    private boolean inputDirections(ByteBuffer data) {

        // Get single flags byte
        final byte flags = data.get();
        switch (flags) {
        case ProtocolConstants.FLAG_DIRECTION_INPUT:
            this.newChannelDirections = Directions.OUTPUT_ONLY;                 // reverse peer's view of the world
            break;
        case ProtocolConstants.FLAG_DIRECTION_OUTPUT:
            this.newChannelDirections = Directions.INPUT_ONLY;                  // reverse peer's view of the world
            break;
        case ProtocolConstants.FLAG_DIRECTION_INPUT | ProtocolConstants.FLAG_DIRECTION_OUTPUT:
            this.newChannelDirections = Directions.BIDIRECTIONAL;
            break;
        default:
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd invalid frame: invalid flags byte 0x%02x", flags & 0xff));
        }

        // Proceed
        this.state = State.READING_PAYLOAD_LENGTH;
        return true;
    }

    // State READING_PAYLOAD_LENGTH
    private boolean inputPayloadLengthData(ByteBuffer data) throws IOException {

        // Decode encoded long value
        if (!this.inputEncodedLongValueData(data))
            return true;

        // Check value is within range
        if (this.longValue < 0 || this.longValue > Integer.MAX_VALUE) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("rec'd frame on %s channel %d with invalid payload length %d",
               this.payloadChannelIdIsLocal ? "local" : "remote", this.payloadChannelId, this.longValue));
        }
        final int payloadLength = (int)this.longValue;

        // Check for zero length payload on an already-open channel, which means "close channel"
        if (!this.newChannelRequest && payloadLength == 0) {

            // Deallocate channel
            this.channelIds.freeChannelId(this.payloadChannelId, this.payloadChannelIdIsLocal);

            // Notify input handler
            final long encodedChannelId = this.getEncodedChannelId();
            this.reentrantHandler = true;
            try {
                this.inputHandler.nestedChannelClosed(encodedChannelId);
            } finally {
                this.reentrantHandler = false;
            }

            // Read the next frame
            this.state = State.READING_CHANNEL_ID;
            return true;
        }

        // If we received the entire payload in this data buffer, deliver it directly to the handler without doing any copying
        if (data.remaining() >= payloadLength) {
            this.deliverPayload(this.readOut(data, payloadLength));
            return true;
        }

        // Partial payload received: initialize our payload buffer and copy the initial portion of the payload into it
        this.payloadBuffer = ByteBuffer.allocate(payloadLength);
        this.payloadBuffer.put(data);
        this.state = State.READING_PAYLOAD;
        return true;
    }

    // State READING_PAYLOAD
    private boolean inputPayloadData(ByteBuffer data) throws IOException {

        // How much more data do we need?
        final int payloadRemaining = this.payloadBuffer.remaining();

        // If payload still not incomplete, go back for more
        if (data.remaining() < payloadRemaining) {
            this.payloadBuffer.put(data);
            return true;
        }

        // Complete the payload using the new data
        this.payloadBuffer.put(this.readOut(data, payloadRemaining));

        // Deliver it to the input handler
        this.deliverPayload((ByteBuffer)this.payloadBuffer.flip());
        return true;
    }

    // State CLOSED
    private boolean inputClosed(ByteBuffer data) {
        throw this.violation = new ProtocolViolationException(this.offset, "the connection has been closed by the remote side");
    }

// Internal methods

    // Deliver completed payload to input handler
    private void deliverPayload(ByteBuffer payload) throws IOException {

        // Check whether channel is still open, and if so deliver payload to handler
        final long encodedChannelId = this.getEncodedChannelId();
        if (this.channelIds.isChannelOpen(encodedChannelId)) {
            this.reentrantHandler = true;
            try {
                if (this.newChannelRequest)
                    this.inputHandler.nestedChannelRequest(encodedChannelId, payload, this.newChannelDirections);
                else
                    this.inputHandler.nestedChannelData(encodedChannelId, payload);
            } finally {
                this.reentrantHandler = false;
            }
        }

        // Reset state and start reading the next frame
        this.payloadBuffer = null;
        this.payloadChannelId = 0;
        this.payloadChannelIdIsLocal = false;
        this.newChannelRequest = false;
        this.state = State.READING_CHANNEL_ID;
    }

    // Decode a LongEncoder-encoded long value
    private boolean inputEncodedLongValueData(ByteBuffer data) {

        // Remember the offset of the start of the long value; calculate total length
        final int decodeLength;
        assert data.hasRemaining();
        if (this.longValueBuffer.position() == 0) {
            this.longValueOffset = this.offset;
            final byte firstByte = data.get();
            this.longValueBuffer.put(firstByte);
            try {
                decodeLength = LongEncoder.decodeLength(firstByte);
            } catch (IllegalArgumentException e) {
                throw this.violation = new ProtocolViolationException(this.longValueOffset,
                  String.format("read invalid encoded long value (in state %s)", this.state), e);
            }
            this.offset++;
        } else
            decodeLength = LongEncoder.decodeLength(this.longValueBuffer.get(0));

        // Add more bytes to accumulator until we get a whole value
        while (this.longValueBuffer.position() < decodeLength) {
            if (!data.hasRemaining())
                return false;
            this.longValueBuffer.put(data.get());
            this.offset++;
        }

        // Decode the encoded value
        this.longValueBuffer.flip();
        try {
            this.longValue = LongEncoder.read(this.longValueBuffer);
        } catch (IllegalArgumentException e) {
            throw this.violation = new ProtocolViolationException(this.longValueOffset,
              String.format("read invalid encoded long value (in state %s)", this.state), e);
        }
        this.longValueBuffer.clear();                                   // reset for next time
        return true;
    }

    // Decode an 8 byte, big endian long value
    private boolean inputPlainLongValueData(ByteBuffer data) {

        // Remember the offset of the start of the long value
        if (this.longValueBuffer.position() == 0)
            this.longValueOffset = this.offset;

        // Attempt to fill buffer with 8 bytes
        while (this.longValueBuffer.position() < 8) {
            if (!data.hasRemaining())
                return false;
            this.longValueBuffer.put(data.get());
            this.offset++;
        }

        // Decode the (big endian) long value
        this.longValueBuffer.flip();
        this.longValue = this.longValueBuffer.getLong();
        this.longValueBuffer.clear();                                   // reset for next time
        return true;
    }

    private long getEncodedChannelId() {
        assert this.payloadChannelId >= 1;
        return this.payloadChannelIdIsLocal ? this.payloadChannelId : -this.payloadChannelId;
    }

    // Read out the next "length" bytes from the given ByteBuffer and return them in a (possibly) new ByteBuffer
    private ByteBuffer readOut(ByteBuffer buffer, int length) {

        // Sanity check
        final int available = buffer.remaining();
        if (available < length)
            throw new IllegalArgumentException("invalid length");

        // If exact match, just return the original buffer
        if (available == length)
            return buffer;

        // Extract "length" bytes from what's available
        final ByteBuffer slice = (ByteBuffer)buffer.slice().limit(length);

        // Advance the underlying buffer
        buffer.position(buffer.position() + length);

        // Done
        return slice;
    }

// InputHandler

    /**
     * Callback interface invoked by a {@link ProtocolReader}.
     *
     * <p>
     * Channel ID's are {@code long} values between one and {@link Long#MAX_VALUE}, inclusive. To disambiguate
     * local vs. remote channels, local channel ID's are encoded as their positive values, while remote channel ID's
     * are encoded as the negative of their values (note: zero is not a valid channel ID).
     */
    public interface InputHandler {

        /**
         * A new nested channel request has been received.
         *
         * <p>
         * Because the channel was created by the remote side, {@code channelId} will always be negative.
         *
         * @param channelId the encoded channel ID of the new nested channel (always negative)
         * @param requestData associated request data (read-only)
         * @param directions which I/O direction(s) are being established (from the local point of view)
         * @throws IOException if an I/O error occurs
         */
        void nestedChannelRequest(long channelId, ByteBuffer requestData, Directions directions) throws IOException;

        /**
         * Data has been received on a nested channel.
         *
         * <p>
         * Note that it's possible to receive data on a nested channel after the local side has closed that channel,
         * because the remote side may not yet know that the nested channel has been closed.
         *
         * @param channelId the encoded ID of a nested channel (postive for local channels, negative for remote channels)
         * @param data new channel data
         * @throws IOException if an I/O error occurs
         */
        void nestedChannelData(long channelId, ByteBuffer data) throws IOException;

        /**
         * An existing nested channel has been closed by the remote side.
         *
         * <p>
         * No more incoming data will {@linkplain #nestedChannelData appear} on the specified nested channel.
         *
         * <p>
         * Although nested channels are in general bidirectional, both directions are opened and closed at the same time.
         * This method implies both the incoming and outgoing directions are being closed.
         *
         * @param channelId the encoded ID of an open nested channel (postive for local channels, negative for remote channels)
         * @throws IOException if an I/O error occurs
         */
        void nestedChannelClosed(long channelId) throws IOException;
    }

// State

    @FunctionalInterface
    private interface StateHook {
        boolean inputData(ProtocolReader reader, ByteBuffer data) throws IOException;
    }

    private enum State {
        READING_PROTOCOL_COOKIE(ProtocolReader::inputProtocolCookieData),
        READING_PROTOCOL_VERSION(ProtocolReader::inputProtocolVersionData),
        READING_CHANNEL_ID(ProtocolReader::inputChannelIdData),
        READING_DIRECTIONS(ProtocolReader::inputDirections),
        READING_PAYLOAD_LENGTH(ProtocolReader::inputPayloadLengthData),
        READING_PAYLOAD(ProtocolReader::inputPayloadData),
        CLOSED(ProtocolReader::inputClosed);

        private final StateHook hook;

        State(StateHook hook) {
            this.hook = hook;
        }

        public boolean inputData(ProtocolReader input, ByteBuffer data) throws IOException {
            return this.hook.inputData(input, data);
        }
    }
}
