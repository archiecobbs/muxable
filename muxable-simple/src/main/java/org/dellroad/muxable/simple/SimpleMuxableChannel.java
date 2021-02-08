
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.dellroad.muxable.Directions;
import org.dellroad.muxable.MuxableChannel;
import org.dellroad.muxable.NestedChannelRequest;
import org.dellroad.stuff.net.SelectorSupport;
import org.dellroad.stuff.util.LongMap;

/**
 * An implementation of the {@link MuxableChannel} interface that multiplexes nested channels over a single underlying
 * {@link ByteChannel} (or {@link ReadableByteChannel}, {@link WritableByteChannel} pair) using a simple framing protocol.
 *
 * <p>
 * The nested channel data and {@link NestedChannelRequest}s share the same underlying "real" channel,
 * so any one left unread for too long can block all the others. This implementation only guarantees that
 * "senseless" deadlock won't happen (see {@link MuxableChannel}).
 *
 * <p>
 * <b>Protocol Description</b>
 *
 * <p>
 * After the initial connection, each side transmits a {@linkplain ProtocolConstants#PROTOCOL_COOKIE protocol cookie}
 * followed its {@linkplain ProtocolConstants#CURRENT_PROTOCOL_VERSION current protocol version}. Once so established,
 * the protocol simply consists of <b>frames</b> being sent back and forth. A frame consists of a <b>channel ID</b>,
 * an optional <b>flags byte</b>, a <b>payload length</b>, and finally the <b>payload content</b>. The channel ID and
 * length values are encoded via {@link io.permazen.util.LongEncoder}.
 *
 * <p>
 * The channel ID is negated by the sender for channels created by the receiver; this way, the receiver can differentiate
 * a local channel ID (negative) from a remote channel ID (positive). A channel ID of zero means to immediately close
 * the entire connection.
 *
 * <p>
 * Each side keeps track of which channel ID's have been allocated (by either side). Reception of a remote channel ID
 * that is equal to the next available remote channel ID means the remote side is requesting to open a new nested channel;
 * the subsequent payload becomes the data associated with the request, and a flag byte is sent after the channel ID
 * specifying the {@link Directions} for the new nested channel.
 *
 * <p>
 * Reception of zero length payload implies closing the associated nested channel (however, this does not apply to the
 * initial frame that opens a new channel, as it's possible to request opening a new channel with zero bytes of
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
 * This is a Java NIO based implementation. The underlying channel(s) must be {@link SelectableChannel}s
 * and (for now) use the default {@link SelectorProvider}. The nested channels returned by this class will
 * use same {@link SelectorProvider} as the underlying channel(s).
 *
 * <p>
 * Because an internal service thread is created, instances must be explicitly {@link #start}'d before use
 * and {@link #stop}'d when no longer needed. When this instance is {@link #stop}'d, the underlying
 * channel(s) are closed.
 *
 * <p>
 * Invoking {@link #close} on this instance has the same effect as invoking {@link #stop}.
 *
 * <p>
 * Instances are thread safe.
 */
public class SimpleMuxableChannel extends SelectorSupport implements MuxableChannel {

    private static final int MAIN_CHANNEL_INPUT_BUFFER_SIZE = (1 << 20) - 64;       // 1MB minus 64 bytes of overhead
    private static final long MAIN_CHANNEL_OUTPUT_QUEUE_FULL = (1L << 26);          // 64MB

    private static final int NESTED_CHANNEL_INPUT_BUFFER_SIZE = (1 << 17) - 64;     // 128K minus 64 bytes of overhead
    private static final long NESTED_CHANNEL_OUTPUT_QUEUE_FULL = (1L << 23);        // 8MB

    private static final int REQUEST_QUEUE_CAPACITY = 1024;

    // Logging
    private final LoggingSupport log = new LoggingSupport(this);

    // Given channel(s)
    private final ReadableByteChannel input;
    private final WritableByteChannel output;

    // Underlying channel info's
    private InputChannelInfo<?> mainInput;
    private OutputChannelInfo<?> mainOutput;

    // Nested channel info's
    private final LongMap<NestedInputChannelInfo> nestedInputMap = new LongMap<>();     // local -> peer data flow
    private final LongMap<NestedOutputChannelInfo> nestedOutputMap = new LongMap<>();   // local <- peer data flow

    // Incoming new channel reqeusts
    private final ArrayBlockingQueue<NestedChannelRequest> requests = new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY);

    // Framing protocol state
    private final ChannelIds channelIds = new ChannelIds();
    private final ProtocolReader reader = new ProtocolReader(this.channelIds, new ProtocolReader.InputHandler() {

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
    private final ProtocolWriter writer = new ProtocolWriter(this.channelIds, this::sendOutput);

    // This counts how many of the nested output queues are full. We only really care if this number is zero or not,
    // as that's what tells us whether to select for read on the main channel input. But we keep track of the exact
    // count to avoid having to survey all of the other nested output queues every time any one of them changes.
    private long numNestedOutputsFull;

    // Misc other state
    private State state = State.NOT_STARTED;
    private volatile Throwable shutdownCause;

// Constructors

    /**
     * Constructor taking a single, bi-directional {@link ByteChannel}.
     *
     * <p>
     * Although it's not enforced by the parameter type, the given {@code channel} must subclass {@link SelectableChannel}.
     *
     * @param channel underlying channel for both input and output
     * @throws IllegalArgumentException if {@code channel} is null
     * @throws IllegalArgumentException if {@code channel} is not a {@link SelectableChannel}
     */
    public SimpleMuxableChannel(ByteChannel channel) {
        this(channel, channel);
    }

    /**
     * Primary constructor.
     *
     * <p>
     * Although it's not enforced by the parameter types, the given {@code input} and {@code output} must subclass
     * {@link SelectableChannel}.
     *
     * @param input channel receiving input from the remote side
     * @param output channel taking output from the local side
     * @throws IllegalArgumentException if either channel is not a {@link SelectableChannel}
     */
    public SimpleMuxableChannel(ReadableByteChannel input, WritableByteChannel output) {
        //super(input.provider());          - TODO with newer dellroad-stuff
        if (!(input instanceof SelectableChannel))
            throw new IllegalArgumentException("input is not a SelectableChannel");
        if (!(output instanceof SelectableChannel))
            throw new IllegalArgumentException("output is not a SelectableChannel");
        this.input = input;
        this.output = output;
    }

// MuxableChannel

    @Override
    public synchronized NestedChannelRequest newNestedChannelRequest(ByteBuffer requestData, Directions directions)
      throws IOException {

        // Sanity check
        if (requestData == null)
            throw new IllegalArgumentException("null requestData");
        if (directions == null)
            throw new IllegalArgumentException("null directions");
        if (!this.state.equals(State.RUNNING))
            throw new IOException("channel is in state " + this.state, this.shutdownCause);

        // Allocate a new local channel ID and send request to peer
        this.log.info("creating new local channel %d", this.channelIds.getNextLocalChannelId());
        final long channelId = this.writer.openNestedChannel(requestData, directions);

        // Setup the nested channel locally
        return this.newNestedChannel(channelId, requestData, directions);
    }

    @Override
    public BlockingQueue<NestedChannelRequest> getNestedChannelRequests() {
        return this.requests;
    }

// I/O Event Handling

    // Read data and run it through the input protocol state machine
    private void handleMainChannelReadable() throws IOException {
        this.log.trace("%s %s", "main channel", "readable");
        if (!this.reader.input(this.mainInput.read()))
            throw new IOException("framing protocol was terminated by the remote peer");
    }

    // Read data and run it through the output protocol state machine
    private void handleNestedChannelReadable(NestedInputChannelInfo nestedInput) throws IOException {
        this.log.trace("%s %s", nestedInput, "readable");
        this.writer.writeNestedChannel(nestedInput.getChannelId(), nestedInput.read());
    }

    // Write out some enqueued data on the main channel and update selectors if there was a meaningful change
    private void handleMainChannelWritable() throws IOException {
        this.log.trace("%s %s", "main channel", "writable");
        final int flags = this.mainOutput.write();
        this.log.trace("%s %s", "main channel", OutputQueue.describeFlags(flags));
        if (this.wentNonFull(flags))
            this.nestedInputMap.values().forEach(NestedInputChannelInfo::startReading);
        if (this.wentEmpty(flags))
            this.mainOutput.stopWriting();
    }

    // Write out some enqueued data on the nested channel and update selectors if there was a meaningful change
    private void handleNestedChannelWritable(NestedOutputChannelInfo nestedOutput) throws IOException {
        this.log.trace("%s %s", nestedOutput, "writable");
        final int flags = nestedOutput.write();
        this.log.trace("%s %s", nestedOutput, OutputQueue.describeFlags(flags));
        if (this.wentFull(flags) && this.numNestedOutputsFull++ == 0)
            this.mainInput.stopReading();
        if (this.wentNonFull(flags) && --this.numNestedOutputsFull == 0)
            this.mainInput.startReading();
        if (this.wentEmpty(flags))
            nestedOutput.stopWriting();
    }

    private void handleMainChannelClosed(Throwable cause) {
        if (this.shutdownCause == null) {
            this.log.info("exception on %s: %s", "main channel", cause);
            this.shutdownCause = cause;
            this.close();
        }
    }

    private void handleNestedChannelClosed(NestedChannelInfo nestedInfo, Throwable cause) {

        // Close the nested channel (both sides)
        final long channelId = nestedInfo.getChannelId();
        this.log.info("exception on %s: %s", nestedInfo, cause);
        this.closeNestedChannel(channelId);

        // Notify peer
        try {
            this.writer.closeNestedChannel(channelId);
        } catch (IOException e) {
            // ignore
        }
    }

// ProtocolReader.InputHandler

    private void nestedChannelRequest(long channelId, ByteBuffer requestData, Directions directions) throws IOException {
        this.log.debug("rec'd new channel request: remote channel %d, requestData %s",
          -channelId, this.log.toString(requestData, 64));
        this.requests.add(this.newNestedChannel(channelId, requestData, directions));
    }

    private void nestedChannelData(long channelId, ByteBuffer data) {
        final NestedOutputChannelInfo nestedOutput = this.nestedOutputMap.get(channelId);
        if (nestedOutput == null) {                         // remote must have sent the data before it knew we closed the channel
            this.log.debug("ignoring data on closed %s channel %s: %s",
              channelId < 0 ? "remote" : "local", Math.abs(channelId), this.log.toString(data, 64));
            return;
        }
        this.log.trace("rec'd data on %s: %s", nestedOutput, this.log.toString(data, 64));
        nestedOutput.enqueue(data);
    }

    private void nestedChannelClosed(long channelId) {
        this.log.debug("rec'd close for %s channel %d", channelId < 0 ? "remote" : "local", Math.abs(channelId));
        this.closeNestedChannel(channelId);
    }

// ProtocolWriter.OutputHandler

    // Write out some enqueued data on the nested channel and update selectors if there was a meaningful change
    private void sendOutput(ByteBuffer data) throws IOException {
        if (data == null)
            throw new IllegalArgumentException("null data");
        this.log.trace("writing to main output: %s", this.log.toString(data, 64));
        final int flags = this.mainOutput.enqueue(data);
        this.log.trace("%s %s", "main output", OutputQueue.describeFlags(flags));
        if (this.wentNonEmpty(flags))
            this.mainOutput.startWriting();
        if (this.wentFull(flags))
            this.nestedInputMap.values().forEach(NestedInputChannelInfo::stopReading);
    }

// SelectorSupport

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public synchronized void start() throws IOException {

        // Sanity check
        if (!this.state.equals(State.NOT_STARTED))
            throw new IllegalStateException("can't start in state " + this.state);
        this.log.info("starting");
        super.start();

        // Create main input & output
        this.mainInput = new MainInputChannelInfo((SelectableChannel)this.input);
        this.mainOutput = new MainOutputChannelInfo((SelectableChannel)this.output);

        // Start reading main input
        this.mainInput.startReading();
        this.log.info("started");
    }

    @Override
    public synchronized void stop() {
        if (this.state.equals(State.RUNNING)) {
            this.log.info("stopping");
            super.stop();
            this.shutdown();
            this.state = State.STOPPED;
            this.log.info("stopped");
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

    private NestedChannelRequest newNestedChannel(long channelId, ByteBuffer requestData, Directions directions)
      throws IOException {

        // Sanity check
        if (this.nestedInputMap.containsKey(channelId) || this.nestedOutputMap.containsKey(channelId))
            throw new RuntimeException("internal error");

        // Debug
        this.log.info("opening new %s %s channel %d", directions, channelId < 0 ? "remote" : "local", Math.abs(channelId));

        // Initialize
        //final SelectorProvider provider = this.provider;              - TODO with newer dellroad-stuff
        final SelectorProvider provider = SelectorProvider.provider();

        // Create channel for local <- peer data flow
        final ReadableByteChannel inputChannel;
        if (directions.hasInput()) {
            final Pipe pipe = provider.openPipe();
            inputChannel = pipe.source();
            final NestedOutputChannelInfo nestedOutput = new NestedOutputChannelInfo(pipe.sink(), channelId);
            this.nestedOutputMap.put(channelId, nestedOutput);
        } else
            inputChannel = null;

        // Create channel for local -> peer data flow
        final WritableByteChannel outputChannel;
        if (directions.hasOutput()) {
            final Pipe pipe = provider.openPipe();
            outputChannel = pipe.sink();
            final NestedInputChannelInfo nestedInput = new NestedInputChannelInfo(pipe.source(), channelId);
            this.nestedInputMap.put(channelId, nestedInput);
            nestedInput.startReading();
        } else
            outputChannel = null;

        // Build request object and add to queue
        return new SimpleNestedChannelRequest(this, inputChannel, outputChannel, requestData);
    }

    private void closeNestedChannel(long channelId) {

        // Debug
        this.log.info("closing %s channel %d", channelId < 0 ? "remote" : "local", Math.abs(channelId));

        // Close local -> peer direction
        final NestedInputChannelInfo nestedInput = this.nestedInputMap.remove(channelId);
        if (nestedInput != null)
            nestedInput.close();

        // Close local <- peer direction
        final NestedOutputChannelInfo nestedOutput = this.nestedOutputMap.remove(channelId);
        if (nestedOutput != null) {
            nestedOutput.close();
            if (nestedOutput.getOutputQueue().isFull() && --this.numNestedOutputsFull == 0)
                this.mainInput.startReading();
        }
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
        this.mainInput.close();
        this.mainOutput.close();
        this.nestedInputMap.values()
          .forEach(NestedInputChannelInfo::close);
        this.nestedInputMap.clear();
        this.nestedOutputMap.values()
          .forEach(NestedOutputChannelInfo::close);
        this.nestedOutputMap.clear();
        this.numNestedOutputsFull = 0;
    }

    protected void closeAndCatch(SelectableChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

// ChannelInfo

    private abstract class ChannelInfo<T extends SelectableChannel> implements IOHandler, Closeable {

        private final T channel;
        private final SelectionKey key;

        ChannelInfo(T channel) throws IOException {
            if (channel == null)
                throw new IllegalArgumentException("null channel");
            this.channel = channel;
            this.key = SimpleMuxableChannel.this.createSelectionKey(this.channel, this);
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
        public void close() {
            SimpleMuxableChannel.this.closeAndCatch(this.channel);
        }
    }

// InputChannelInfo

    private abstract class InputChannelInfo<T extends SelectableChannel & ReadableByteChannel> extends ChannelInfo<T> {

        private final int bufferSize;

        InputChannelInfo(T channel, int bufferSize) throws IOException {
            super(channel);
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
            this.getChannel().read(data);
            return data;
        }
    }

// NestedChannelInfo

    private interface NestedChannelInfo {

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

// MainInputChannelInfo

    private class MainInputChannelInfo<T extends SelectableChannel & ReadableByteChannel> extends InputChannelInfo<T> {

        MainInputChannelInfo(T channel) throws IOException {
            super(channel, MAIN_CHANNEL_INPUT_BUFFER_SIZE);
        }

        @Override
        public void serviceIO(SelectionKey key) throws IOException {
            SimpleMuxableChannel.this.handleMainChannelReadable();
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleMainChannelClosed(cause);
        }

        @Override
        public String toString() {
            return "main input channel";
        }
    }

// NestedInputChannelInfo

    private class NestedInputChannelInfo extends InputChannelInfo<Pipe.SourceChannel> implements NestedChannelInfo {

        private final long channelId;

        NestedInputChannelInfo(Pipe.SourceChannel channel, long channelId) throws IOException {
            super(channel, NESTED_CHANNEL_INPUT_BUFFER_SIZE);
            this.channelId = channelId;
        }

        @Override
        public long getChannelId() {
            return this.channelId;
        }

        @Override
        public void serviceIO(SelectionKey key) throws IOException {
            SimpleMuxableChannel.this.handleNestedChannelReadable(this);
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleNestedChannelClosed(this, cause);
        }

        @Override
        public String toString() {
            return String.format("%s input channel %d", this.isRemote() ? "remote" : "local", Math.abs(this.channelId));
        }
    }

// OutputChannelInfo

    private abstract class OutputChannelInfo<T extends SelectableChannel & WritableByteChannel> extends ChannelInfo<T> {

        private final OutputQueue queue;

        OutputChannelInfo(T channel, long outputQueueFullMark) throws IOException {
            super(channel);
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

// MainOutputChannelInfo

    private class MainOutputChannelInfo<T extends SelectableChannel & WritableByteChannel> extends OutputChannelInfo<T> {

        MainOutputChannelInfo(T channel) throws IOException {
            super(channel, MAIN_CHANNEL_OUTPUT_QUEUE_FULL);
        }

        @Override
        public void serviceIO(SelectionKey key) throws IOException {
            SimpleMuxableChannel.this.handleMainChannelWritable();
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleMainChannelClosed(cause);
        }

        @Override
        public String toString() {
            return "main output channel";
        }
    }

// NestedOutputChannelInfo

    private class NestedOutputChannelInfo extends OutputChannelInfo<Pipe.SinkChannel> implements NestedChannelInfo {

        private final long channelId;

        NestedOutputChannelInfo(Pipe.SinkChannel channel, long channelId) throws IOException {
            super(channel, NESTED_CHANNEL_OUTPUT_QUEUE_FULL);
            this.channelId = channelId;
        }

        @Override
        public long getChannelId() {
            return this.channelId;
        }

        @Override
        public void serviceIO(SelectionKey key) throws IOException {
            SimpleMuxableChannel.this.handleNestedChannelWritable(this);
        }

        @Override
        public void close(Throwable cause) {
            SimpleMuxableChannel.this.handleNestedChannelClosed(this, cause);
        }

        @Override
        public String toString() {
            return String.format("%s output channel %d", this.isRemote() ? "remote" : "local", Math.abs(this.channelId));
        }
    }

// State

    private enum State {
        NOT_STARTED,
        RUNNING,
        STOPPED;
    }
}
