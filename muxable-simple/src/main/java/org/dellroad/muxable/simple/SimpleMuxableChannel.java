/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.LongStream;

import org.dellroad.muxable.Directions;
import org.dellroad.muxable.MuxableChannel;
import org.dellroad.muxable.NestedChannel;
import org.dellroad.stuff.net.SelectorSupport;
import org.dellroad.stuff.util.LongMap;

/**
 * An implementation of the {@link MuxableChannel} interface that multiplexes nested channels over a single underlying
 * {@link ByteChannel} (or {@link ReadableByteChannel}, {@link WritableByteChannel} pair) using a simple framing protocol.
 *
 * <p>
 * The nested channel data and {@link NestedChannel}s share the same underlying "real" channel,
 * so any one left unread for too long can block all the others. This implementation only guarantees that
 * "senseless" deadlock won't happen (see {@link MuxableChannel}).
 *
 * <p>
 * <b>Protocol Description</b>
 *
 * <p>
 * After the initial connection, each side transmits a {@linkplain ProtocolConstants#PROTOCOL_COOKIE protocol cookie}
 * followed by its {@linkplain ProtocolConstants#CURRENT_PROTOCOL_VERSION current protocol version}. Once so established,
 * the protocol simply consists of <b>frames</b> being sent back and forth. A frame consists of a <b>channel ID</b>,
 * an optional <b>flags byte</b>, a <b>payload length</b>, and finally the <b>payload content</b>. The channel ID and
 * length values are encoded via {@link io.permazen.util.LongEncoder}.
 *
 * <p>
 * The channel ID is negated by the sender for channels created by the receiver; this way, the receiver can differentiate
 * a local channel ID (negative) from a remote channel ID (positive). Reception of a frame with a channel ID of zero means
 * to immediately close the entire parent connection.
 *
 * <p>
 * Each side keeps track of which channel ID's have been allocated (by either side). Reception of a remote channel ID
 * that is equal to the next available remote channel ID means the remote side is requesting to open a new nested channel;
 * the subsequent payload becomes the data associated with the request, and a flag byte is sent after the channel ID
 * specifying the {@link Directions} for the new nested channel.
 *
 * <p>
 * Reception of zero length payload implies closing the associated nested channel (however, this does not apply to the
 * initial frame that opens a new channel, so it's possible to request opening a new channel with zero bytes of
 * request data). Closing a nested channel always means closing both directions.
 *
 * <p>
 * Note that it's possible for one side to receive data on a nested channel that it has already closed, because that data
 * may have been sent prior to the remote side receiving the local side's close notification. Such data is discarded.
 *
 * <p>
 * <b>Java NIO</b>
 *
 * <p>
 * Because an internal service thread is created, instances must be explicitly {@link #start}'d before use
 * and {@link #stop}'d when no longer needed. When this instance is {@link #stop}'d, the underlying
 * channel(s) with which it was constructed are closed.
 *
 * <p>
 * Invoking {@link #close} on this instance has the same effect as invoking {@link #stop}.
 *
 * <p>
 * Instances are thread safe.
 */
public class SimpleMuxableChannel extends SelectorSupport implements MuxableChannel<Pipe.SourceChannel, Pipe.SinkChannel> {

    private static final int MAIN_CHANNEL_INPUT_BUFFER_SIZE = (1 << 20) - 64;       // 1MB minus 64 bytes of overhead
    private static final long MAIN_CHANNEL_OUTPUT_QUEUE_FULL = (1L << 26);          // 64MB

    private static final int NESTED_CHANNEL_INPUT_BUFFER_SIZE = (1 << 17) - 64;     // 128K minus 64 bytes of overhead
    private static final long NESTED_CHANNEL_OUTPUT_QUEUE_FULL = (1L << 23);        // 8MB

    private static final int REQUEST_QUEUE_CAPACITY = 1024;

    private static final int DEFAULT_HOUSEKEEPING_INTERVAL = 200;                   // 200ms

    // Logging
    @SuppressWarnings("this-escape")
    private final LoggingSupport log = this.buildLoggingSupport();

    // Given channel(s)
    private final SelectableChannel input;
    private final SelectableChannel output;

/*
                   +-------------------------------------+
                  /                                       \
                 /                                  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
                /      ┏━━━━━━━━━━━━━━━┓            ┃      SimpleMuxableChannel        ┃
               /      /┃ NestedChannel ┃            ┃                                  ┃
              /      / ┃               ┃            ┃                                  ┃
             /      /  ┃       input  ━╋━━━━━━━━━━━━╋━ NestedInfo.inputFlow            ┃
      ┏━━━━━━━━━━━━━┓  ┃      output  ━╋━━+      +━━╋━ NestedInfo.inputFlow            ┃
      ┃             ┃  ┗━━━━━━━━━━━━━━━┛   \    /   ┃  ...                             ┃
      ┃ Application ┃                       \  /    ┃                     [peerInput] ━╋━━━━━ from network
      ┃             ┃  ┏━━━━━━━━━━━━━━━┓     \/     ┃                                  ┃
      ┗━━━━━━━━━━━━━┛  ┃ NestedChannel ┃     /\     ┃                    [peerOutput] ━╋━━━━━ to network
                    \  ┃               ┃    /  \    ┃                                  ┃
                     \ ┃       input  ━╋━━━+    +━━━╋━ NestedInfo.outputFlow           ┃
                      \┃      output  ━╋━━━━━━━━━━━━╋━ NestedInfo.outputFlow           ┃
                       ┗━━━━━━━━━━━━━━━┛            ┃  ...                             ┃
                                                    ┃                                  ┃
                                                    ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
*/

    // Peer flows
    private PeerInputFlow<?> peerInput;
    private PeerOutputFlow<?> peerOutput;

    // Nested channels
    private final LongMap<NestedInfo> nestedInfoMap = new LongMap<>();

    // Incoming new channel requests
    private final ArrayBlockingQueue<SimpleNestedChannel> requests = new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY);

    // Framing protocol input & output state
    private final ChannelIds channelIds = new ChannelIds(this.log.log, this.log.logPrefix + "ChannelIds: ");
    private final ProtocolReader reader = new ProtocolReader(this.log.log, this.log.logPrefix + "ProtocolReader: ",
      this.channelIds, new ProtocolReader.InputHandler() {

        @Override
        public void nestedChannelRequest(long channelId, ByteBuffer requestData, Directions directions) throws IOException {
            SimpleMuxableChannel.this.nestedChannelRequest(channelId, requestData, directions);
        }

        @Override
        public void nestedChannelData(long channelId, ByteBuffer data) throws IOException {
            SimpleMuxableChannel.this.nestedChannelData(channelId, data);
        }

        @Override
        public void nestedChannelClosed(long channelId) throws IOException {
            SimpleMuxableChannel.this.nestedChannelClosed(channelId);
        }
    });
    private final ProtocolWriter writer = new ProtocolWriter(this.log.log, this.log.logPrefix + "ProtocolWriter: ",
      this.channelIds, this::enqueuePeerOutput);

    // This counts how many of the nested output queues are full. We only really care if this number is zero or not,
    // as that's what tells us whether to select for read on the peer's input. But we keep track of the exact count
    // to avoid having to survey all of the other nested output queues every time any one of them changes.
    private long numNestedOutputsFull;

    // Misc other state
    private State state = State.NOT_STARTED;
    private volatile Throwable shutdownCause;

// Constructors

    /**
     * Constructor taking a single, bi-directional {@link ByteChannel}.
     *
     * <p>
     * Equivalent to: {@code SimpleMuxableChannel(channel, channel)}.
     *
     * @param channel underlying channel for both input and output
     * @param <C> bidirectional channel type
     * @throws IllegalArgumentException if {@code channel} is null
     */
    public <C extends SelectableChannel & ByteChannel> SimpleMuxableChannel(C channel) {
        this(channel, channel);
    }

    /**
     * Constructor inferring the {@link SelectorProvider} to use.
     *
     * <p>
     * The {@link SelectorProvider} is inferred from {@code input}.
     *
     * @param input channel receiving input from the remote side
     * @param output channel taking output from the local side; may be the same as {@code input}
     * @param <I> input channel type
     * @param <O> output channel type
     * @throws IllegalArgumentException if either channel is null
     */
    public <I extends SelectableChannel & ReadableByteChannel, O extends SelectableChannel & WritableByteChannel>
      SimpleMuxableChannel(I input, O output) {
        this(input != null ? input.provider() : SelectorProvider.provider(), input, output);
    }

    /**
     * Primary constructor.
     *
     * <p>
     * The given channels will be closed when this instance is closed.
     *
     * @param provider the {@link SelectorProvider} that this instance will use
     * @param input channel receiving input from the remote side
     * @param output channel taking output from the local side; may be the same as {@code input}
     * @param <I> input channel type
     * @param <O> output channel type
     * @throws IllegalArgumentException if any parameter is null
     */
    public <I extends SelectableChannel & ReadableByteChannel, O extends SelectableChannel & WritableByteChannel>
      SimpleMuxableChannel(SelectorProvider provider, I input, O output) {
        super(provider);
        if (input == null)
            throw new IllegalArgumentException("null input");
        if (output == null)
            throw new IllegalArgumentException("null output");
        this.input = input;
        this.output = output;
        this.setHousekeepingInterval(SimpleMuxableChannel.DEFAULT_HOUSEKEEPING_INTERVAL);
    }

    protected LoggingSupport buildLoggingSupport() {
        return new LoggingSupport(this);
    }

// MuxableChannel

    @Override
    public SimpleNestedChannel newNestedChannel(ByteBuffer requestData) throws IOException {
        return (SimpleNestedChannel)MuxableChannel.super.newNestedChannel(requestData);
    }

    @Override
    public synchronized SimpleNestedChannel newNestedChannel(ByteBuffer requestData, Directions directions)
      throws IOException {

        // Sanity check
        if (requestData == null)
            throw new IllegalArgumentException("null requestData");
        if (directions == null)
            throw new IllegalArgumentException("null directions");
        if (!this.state.equals(State.RUNNING))
            throw new IOException("channel is in state " + this.state, this.shutdownCause);

        // Allocate a new local channel ID and send request to peer
        this.log.debug("creating new local channel %d", this.channelIds.getNextLocalChannelId());
        final long channelId = this.writer.openNestedChannel(requestData, directions);

        // Setup the nested channel locally
        return this.newNestedChannel(channelId, requestData, directions);
    }

    @Override
    public BlockingQueue<SimpleNestedChannel> getNestedChannelRequests() {
        return this.requests;
    }

// I/O Event Handling

    // Read data from the peer on the peer input channel and run it through the input protocol state machine
    private void handlePeerChannelReadable() throws IOException {
        assert Thread.holdsLock(this);
        this.log.trace("%s %s", "peer channel", "readable");
        final ByteBuffer data = this.peerInput.read();
        this.log.trace("%s input %s", "peer channel", LoggingSupport.toString(data, 64));
        if (!this.reader.input(data))
            throw new EOFException("connection closed by the remote peer");
    }

    // Read data from the application on a nested channel and run it through the output protocol state machine
    private void handleNestedChannelReadable(NestedInputFlow nestedInput) throws IOException {
        assert Thread.holdsLock(this);
        this.log.trace("%s %s", nestedInput, "readable");
        final ByteBuffer data = nestedInput.read();
        this.log.trace("%s input %s", nestedInput, LoggingSupport.toString(data, 64));
        this.writer.writeNestedChannel(nestedInput.getChannelId(), data);
    }

    // Write data to the peer on the peer output channel and update selectors if there was a meaningful output queue change
    private void handlePeerChannelWritable() throws IOException {
        assert Thread.holdsLock(this);
        this.log.trace("%s %s", "peer channel", "writable");
        final int flags = this.peerOutput.write();
        this.log.trace("%s %s", "peer channel", OutputQueue.describeFlags(flags));
        if (this.wentNonFull(flags))
            this.nestedInfoMap.values().forEach(NestedInfo::startReading);
        if (this.wentEmpty(flags))
            this.peerOutput.stopWriting();
    }

    // Write data to the application on a nested channel and update selectors if there was a meaningful output queue change
    private void handleNestedChannelWritable(NestedOutputFlow nestedOutput) throws IOException {
        assert Thread.holdsLock(this);
        this.log.trace("%s %s", nestedOutput, "writable");
        final int flags = nestedOutput.write();
        this.log.trace("%s %s", nestedOutput, OutputQueue.describeFlags(flags));
        if (this.wentNonFull(flags) && --this.numNestedOutputsFull == 0)
            this.peerInput.startReading();
        if (this.wentEmpty(flags))
            nestedOutput.stopWriting();
    }

    // An exception has occurred on one of the channels that connect to the peer over the network.
    // This causes the whole thing to be shutdown.
    private void handlePeerChannelClosed(Flow<?> flow, Throwable cause) {
        assert Thread.holdsLock(this);
        if (this.shutdownCause == null) {
            this.log.debug("exception on %s: %s", flow, cause);
            this.shutdownCause = cause;
            this.close();
        }
    }

    // An exception has occurred on one of the nested channels that connect to the application
    private void handleNestedChannelException(NestedFlow nestedInfo, Throwable cause) {
        assert Thread.holdsLock(this);

        // Log the exception
        final long channelId = nestedInfo.getChannelId();
        final boolean alreadyClosed = !this.nestedInfoMap.containsKey(channelId);
        if (alreadyClosed)
            this.log.trace("exception on %s: %s", nestedInfo, cause);
        else
            this.log.debug("exception on %s: %s", nestedInfo, cause);

        // Close the nested channel
        if (!this.closeNestedChannel(channelId, true))
            return;
    }

    // Somebody invoked SimpleNestedChannel.close()
    synchronized void handleNestedChannelClosed(long channelId) {
        this.log.debug("close() invoked on channel %s%d", channelId < 0 ? "R" : "L", Math.abs(channelId));
        this.closeNestedChannel(channelId, true);
    }

// ProtocolReader.InputHandler

    // The peer has asked to create a new nested channel
    private void nestedChannelRequest(long channelId, ByteBuffer requestData, Directions directions) throws IOException {
        assert Thread.holdsLock(this);
        this.log.debug("rec'd new channel request: remote channel %d, requestData %s",
          -channelId, LoggingSupport.toString(requestData, 64));
        this.requests.add(this.newNestedChannel(channelId, requestData, directions));
    }

    // The peer has sent us some data on a nested channel
    private void nestedChannelData(long channelId, ByteBuffer data) {
        assert Thread.holdsLock(this);
        this.log.trace("recv data on channel %s%d: %s",
          channelId < 0 ? "R" : "L", Math.abs(channelId), LoggingSupport.toString(data, 64));
        final NestedInfo nestedInfo = this.nestedInfoMap.get(channelId);
        if (nestedInfo == null) {                           // remote must have sent the data before it knew we closed the channel
            this.log.debug("ignoring data on closed channel %s%d: %s",
              channelId < 0 ? "R" : "L", Math.abs(channelId), LoggingSupport.toString(data, 64));
            return;
        }
        if (nestedInfo.outputFlow() == null) {
            throw new ProtocolViolationException(this.reader.getOffset() - data.remaining(),
              String.format("rec'd data on write-only %s", nestedInfo));
        }
        if (this.enqueueOutputCheckFull(nestedInfo.outputFlow(), data) && this.numNestedOutputsFull++ == 0)
            this.peerInput.stopReading();
    }

    // The peer has told us that it is closing a nested channel
    private void nestedChannelClosed(long channelId) {
        assert Thread.holdsLock(this);
        this.log.debug("rec'd close for channel %s%d", channelId < 0 ? "R" : "L", Math.abs(channelId));
        this.closeNestedChannel(channelId, false);
    }

// ProtocolWriter.OutputHandler

    // The ProtocolWriter has generated some output to send to the peer
    private void enqueuePeerOutput(ByteBuffer data) {
        assert Thread.holdsLock(this);
        if (this.enqueueOutputCheckFull(this.peerOutput, data))
            this.nestedInfoMap.values().forEach(NestedInfo::stopReading);
    }

// Helper

    // Enqueue some data on the given output and enable writing if the queue went from empty to non-empty.
    // Returns true if the queue transitioned from less-than-full to full; in that case, the caller must enact flow control.
    private boolean enqueueOutputCheckFull(OutputFlow<?> outputFlow, ByteBuffer data) {
        assert Thread.holdsLock(this);
        if (outputFlow == null)
            throw new IllegalArgumentException("null outputFlow");
        if (data == null)
            throw new IllegalArgumentException("null data");
        this.log.trace("writing to %s: %s", outputFlow, LoggingSupport.toString(data, 64));
        final int flags = outputFlow.enqueue(data);
        this.log.trace("%s %s", outputFlow, OutputQueue.describeFlags(flags));
        if (this.wentNonEmpty(flags))
            outputFlow.startWriting();
        return this.wentFull(flags);
    }

// SelectorSupport

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public synchronized void start() throws IOException {

        // Sanity check
        if (!this.state.equals(State.NOT_STARTED))
            throw new IllegalStateException("can't start in state " + this.state);
        this.log.debug("starting");
        super.start();

        // Create peer input & output; if they are the same channel, use a single common SelectionKey
        if (this.input == this.output) {

            // Create combined IOHandler
            final IOHandler combinedHandler = new IOHandler() {

                @Override
                public void serviceIO(SelectionKey key) throws IOException {
                    final boolean invalid = !key.isValid();
                    if (invalid || key.isReadable())
                        SimpleMuxableChannel.this.peerInput.serviceIO(key);
                    if (invalid || key.isWritable())
                        SimpleMuxableChannel.this.peerOutput.serviceIO(key);
                }

                @Override
                public void close(Throwable cause) {
                    SimpleMuxableChannel.this.closeAndCatch(SimpleMuxableChannel.this.peerInput);
                    SimpleMuxableChannel.this.closeAndCatch(SimpleMuxableChannel.this.peerOutput);
                }

                @Override
                public String toString() {
                    return "peer input/output channel";
                }
            };

            // Use a single selection key for both flows
            final SelectionKey key = this.createSelectionKey(this.input, combinedHandler, true);
            this.peerInput = new PeerInputFlow(this.input, key);
            this.peerOutput = new PeerOutputFlow(this.output, key);
        } else {
            this.peerInput = new PeerInputFlow(this.input);
            this.peerOutput = new PeerOutputFlow(this.output);
        }

        // Start reading peer input
        this.peerInput.startReading();
        this.state = State.RUNNING;
        this.log.debug("started");
    }

    @Override
    public synchronized void stop() {
        if (this.state.equals(State.RUNNING)) {
            this.log.debug("stopping");
            super.stop();
            this.shutdown();
            this.state = State.STOPPED;
            this.log.debug("stopped");
        }
    }

// Channel

    @Override
    public synchronized boolean isOpen() {
        return this.state.equals(State.RUNNING);
    }

    @Override
    public void close() {
        this.stop();
    }

// Internal methods

    private SimpleNestedChannel newNestedChannel(long channelId, ByteBuffer requestData, Directions directions) throws IOException {

        // Sanity check
        assert Thread.holdsLock(this);
        if (this.nestedInfoMap.containsKey(channelId))
            throw new RuntimeException("internal error");

        // Debug
        this.log.debug("opening new %s channel %s%d", directions, channelId < 0 ? "R" : "L", Math.abs(channelId));

        // Create channel for local <- peer data flow
        final NestedOutputFlow outputFlow;
        final Pipe.SourceChannel nestedInput;
        if (directions.hasInput()) {
            final Pipe pipe = this.provider.openPipe();
            outputFlow = new NestedOutputFlow(pipe, channelId);
            nestedInput = pipe.source();
        } else {
            outputFlow = null;
            nestedInput = null;
        }

        // Create channel for local -> peer data flow
        final NestedInputFlow inputFlow;
        final Pipe.SinkChannel nestedOutput;
        if (directions.hasOutput()) {
            final Pipe pipe = this.provider.openPipe();
            inputFlow = new NestedInputFlow(pipe, channelId);
            inputFlow.startReading();
            nestedOutput = pipe.sink();
        } else {
            inputFlow = null;
            nestedOutput = null;
        }

        // Add nested channel
        this.nestedInfoMap.put(channelId, new NestedInfo(channelId, inputFlow, outputFlow));

        // Build request object and add to queue
        return new SimpleNestedChannel(this, channelId, nestedInput, nestedOutput, requestData);
    }

    private boolean closeNestedChannel(long channelId, boolean notifyPeer) {
        assert Thread.holdsLock(this);

        // Have we already closed this nested channel? If so, ignore this duplicate request.
        final NestedInfo nestedInfo = this.nestedInfoMap.get(channelId);
        if (nestedInfo == null)
            return false;

        // Debug
        this.log.debug("closing %s", nestedInfo);

        // Close local -> peer direction
        if (nestedInfo.inputFlow() != null)
            nestedInfo.inputFlow().close();

        // Close local <- peer direction
        if (nestedInfo.outputFlow() != null) {
            nestedInfo.outputFlow().close();
            if (nestedInfo.outputFlow().getOutputQueue().isFull() && --this.numNestedOutputsFull == 0)
                this.peerInput.startReading();
        }

        // Notify the peer
        if (notifyPeer) {
            try {
                this.writer.closeNestedChannel(channelId);
            } catch (IOException e) {
                // ignore
            }
        }

        // Done
        return true;
    }

    private boolean wentEmpty(int flags) {
        return (flags & (OutputQueue.WAS_EMPTY | OutputQueue.NOW_EMPTY)) == OutputQueue.NOW_EMPTY;
    }

    private boolean wentFull(int flags) {
        return (flags & (OutputQueue.WAS_FULL | OutputQueue.NOW_FULL)) == OutputQueue.NOW_FULL;
    }

    private boolean wentNonEmpty(int flags) {
        return (flags & (OutputQueue.WAS_EMPTY | OutputQueue.NOW_EMPTY)) == OutputQueue.WAS_EMPTY;
    }

    private boolean wentNonFull(int flags) {
        return (flags & (OutputQueue.WAS_FULL | OutputQueue.NOW_FULL)) == OutputQueue.WAS_FULL;
    }

    private void shutdown() {
        assert Thread.holdsLock(this);
        LongStream.of(this.nestedInfoMap.keySet().toLongArray())
          .forEach(channelId -> this.closeNestedChannel(channelId, false));
        this.numNestedOutputsFull = 0;
        this.log.debug("closing %s", this.peerInput);
        this.peerInput.close();
        this.log.debug("closing %s", this.peerOutput);
        this.peerOutput.close();
    }

    protected void closeAndCatch(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore
        }
    }

// Flow

    private abstract class Flow<T extends SelectableChannel> implements IOHandler, Closeable {

        private final T channel;
        private final SelectionKey key;

        Flow(T channel, SelectionKey key) throws IOException {
            if (channel == null)
                throw new IllegalArgumentException("null channel");
            this.channel = channel;
            this.key = key != null ? key : SimpleMuxableChannel.this.createSelectionKey(this.channel, this, true);
        }

        public SelectionKey getKey() {
            return this.key;
        }

        public T getChannel() {
            return this.channel;
        }

        /**
         * Describe this channel for debug purposes.
         */
        @Override
        public abstract String toString();

        @Override
        public final void serviceIO(SelectionKey key) throws IOException {
            if (!key.isValid()) {
                this.close(new AsynchronousCloseException());
                return;
            }
            this.service();
        }

        protected abstract void service() throws IOException;

        @Override
        public void close() {
            SimpleMuxableChannel.this.closeAndCatch(this.channel);
        }
    }

// InputFlow

    private abstract class InputFlow<T extends SelectableChannel & ReadableByteChannel> extends Flow<T> {

        private final int bufferSize;

        InputFlow(T channel, SelectionKey key, int bufferSize) throws IOException {
            super(channel, key);
            this.bufferSize = bufferSize;
        }

        public void startReading() {
            SimpleMuxableChannel.this.log.trace("%s %s %s", this, "enable", "select for read");
            SimpleMuxableChannel.this.selectFor(this.getKey(), SelectionKey.OP_READ, true);
        }

        public void stopReading() {
            SimpleMuxableChannel.this.log.trace("%s %s %s", this, "disable", "select for read");
            SimpleMuxableChannel.this.selectFor(this.getKey(), SelectionKey.OP_READ, false);
        }

        public ByteBuffer read() throws IOException {
            final ByteBuffer data = ByteBuffer.allocate(this.bufferSize);
            final int numRead = this.getChannel().read(data);
            if (numRead == -1)
                throw new EOFException("got EOF reading channel");
            data.flip();
            return data;
        }
    }

// NestedFlow

    private interface NestedFlow {

        /**
         * Determine whether channel was originated remotely or locally.
         */
        default boolean isRemote() {
            return this.getChannelId() < 0;
        }

        /**
         * Get the (encoded) channel ID for this channel.
         */
        long getChannelId();
    }

// PeerInputFlow

    // Represents the "real" underlying input stream from the remote peer
    private class PeerInputFlow<T extends SelectableChannel & ReadableByteChannel> extends InputFlow<T> {

        PeerInputFlow(T channel, SelectionKey key) throws IOException {
            super(channel, key, MAIN_CHANNEL_INPUT_BUFFER_SIZE);
        }

        PeerInputFlow(T channel) throws IOException {
            this(channel, null);
        }

        @Override
        public void service() throws IOException {
            SimpleMuxableChannel.this.handlePeerChannelReadable();
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handlePeerChannelClosed(this, cause);
        }

        @Override
        public String toString() {
            return "peer input channel";
        }
    }

// NestedInputFlow

    // Represents the input from the application for outgoing data to be written on a nested channel
    private class NestedInputFlow extends InputFlow<Pipe.SourceChannel> implements NestedFlow {

        private final long channelId;
        private final Pipe.SinkChannel pipeSink;

        NestedInputFlow(Pipe pipe, long channelId) throws IOException {
            super(pipe.source(), null, NESTED_CHANNEL_INPUT_BUFFER_SIZE);
            this.channelId = channelId;
            this.pipeSink = pipe.sink();
        }

        @Override
        public long getChannelId() {
            return this.channelId;
        }

        @Override
        public void service() throws IOException {
            SimpleMuxableChannel.this.handleNestedChannelReadable(this);
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleNestedChannelException(this, cause);
        }

        @Override
        public void close() {
            super.close();
            SimpleMuxableChannel.this.closeAndCatch(this.pipeSink);
        }

        @Override
        public String toString() {
            return String.format("nested input channel %s%d", this.isRemote() ? "R" : "L", Math.abs(this.channelId));
        }
    }

// OutputFlow

    private abstract class OutputFlow<T extends SelectableChannel & WritableByteChannel> extends Flow<T> {

        private final OutputQueue queue;

        OutputFlow(T channel, SelectionKey key, long outputQueueFullMark) throws IOException {
            super(channel, key);
            this.queue = new OutputQueue(outputQueueFullMark);
        }

        public void startWriting() {
            SimpleMuxableChannel.this.log.trace("%s %s %s", this, "enable", "select for write");
            SimpleMuxableChannel.this.selectFor(this.getKey(), SelectionKey.OP_WRITE, true);
        }

        public void stopWriting() {
            SimpleMuxableChannel.this.log.trace("%s %s %s", this, "disable", "select for write");
            SimpleMuxableChannel.this.selectFor(this.getKey(), SelectionKey.OP_WRITE, false);
        }

        public OutputQueue getOutputQueue() {
            return this.queue;
        }

        public int enqueue(ByteBuffer data) {
            return this.queue.enqueue(data);
        }

        public int write() throws IOException {
            return this.queue.writeTo(this.getChannel());
        }
    }

// PeerOutputFlow

    // Represents the "real" underlying output stream to the remote peer
    private class PeerOutputFlow<T extends SelectableChannel & WritableByteChannel> extends OutputFlow<T> {

        PeerOutputFlow(T channel, SelectionKey key) throws IOException {
            super(channel, key, MAIN_CHANNEL_OUTPUT_QUEUE_FULL);
        }

        PeerOutputFlow(T channel) throws IOException {
            this(channel, null);
        }

        @Override
        public void service() throws IOException {
            SimpleMuxableChannel.this.handlePeerChannelWritable();
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handlePeerChannelClosed(this, cause);
        }

        @Override
        public String toString() {
            return "peer output channel";
        }
    }

// NestedOutputFlow

    // Represents the output to the application for incoming data that was received on a nested channel
    private class NestedOutputFlow extends OutputFlow<Pipe.SinkChannel> implements NestedFlow {

        private final long channelId;
        private final Pipe.SourceChannel pipeSource;

        NestedOutputFlow(Pipe pipe, long channelId) throws IOException {
            super(pipe.sink(), null, NESTED_CHANNEL_OUTPUT_QUEUE_FULL);
            this.channelId = channelId;
            this.pipeSource = pipe.source();
        }

        @Override
        public long getChannelId() {
            return this.channelId;
        }

        @Override
        public void service() throws IOException {
            SimpleMuxableChannel.this.handleNestedChannelWritable(this);
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleNestedChannelException(this, cause);
        }

        @Override
        public void close() {
            super.close();
            SimpleMuxableChannel.this.closeAndCatch(this.pipeSource);
        }

        @Override
        public String toString() {
            return String.format("nested output channel %s%d", this.isRemote() ? "R" : "L", Math.abs(this.channelId));
        }
    }

// NestedInfo

    private record NestedInfo(long channelId, NestedInputFlow inputFlow, NestedOutputFlow outputFlow) {

        public void startReading() {
            Optional.ofNullable(this.inputFlow())
              .ifPresent(InputFlow::startReading);
        }

        public void stopReading() {
            Optional.ofNullable(this.inputFlow())
              .ifPresent(InputFlow::stopReading);
        }

        @Override
        public String toString() {
            final long channelId = this.channelId();
            return String.format("nested channel %s%d", channelId < 0 ? "R" : "L", Math.abs(channelId));
        }
    }

// State

    private enum State {
        NOT_STARTED,
        RUNNING,
        STOPPED;
    }
}
