
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * A output queue of bytes stored in {@link ByteBuffer}s.
 *
 * <p>
 * Instances are not thread safe.
 */
public class OutputQueue {

    /**
     * Flag bit in the flags returned by {@link #enqueue enqueue()} and {@link #writeTo writeTo()}.
     */
    public static final int NOW_EMPTY = 0x01;

    /**
     * Flag bit in the flags returned by {@link #enqueue enqueue()} and {@link #writeTo writeTo()}.
     */
    public static final int NOW_FULL = 0x02;

    /**
     * Flag bit in the flags returned by {@link #enqueue enqueue()} and {@link #writeTo writeTo()}.
     */
    public static final int WAS_EMPTY = 0x04;

    /**
     * Flag bit in the flags returned by {@link #enqueue enqueue()} and {@link #writeTo writeTo()}.
     */
    public static final int WAS_FULL = 0x08;

    // Pre-calculate flags description strings
    private static final String[] FLAGS_DESCRIPTIONS = new String[16];
    static {
        FLAGS_DESCRIPTIONS[0] = "NOFLAGS";
        for (int flags = 1; flags < 16; flags++) {
            final ArrayList<String> flagNames = new ArrayList<>(4);
            if ((flags & WAS_EMPTY) != 0)
                flagNames.add("WAS_EMPTY");
            if ((flags & NOW_EMPTY) != 0)
                flagNames.add("NOW_EMPTY");
            if ((flags & WAS_FULL) != 0)
                flagNames.add("WAS_FULL");
            if ((flags & NOW_FULL) != 0)
                flagNames.add("NOW_FULL");
            FLAGS_DESCRIPTIONS[flags] = flagNames.stream().collect(Collectors.joining(","));
        }
    }

    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private final long fullMark;

    private long totalBytesQueued;

    /**
     * Constructor.
     *
     * @param fullMark the number of bytes that means this queue should be considered {@linkplain #isFull "full"}
     * @throws IllegalArgumentException if {@code fullMark} is not positive
     */
    public OutputQueue(long fullMark) {
        if (fullMark <= 0)
            throw new IllegalArgumentException("non-positive fullMark");
        this.fullMark = fullMark;
    }

    /**
     * Get the total number of bytes of data currently enqueued.
     *
     * @return total number of bytes queued
     */
    public long getTotalBytesQueued() {
        return this.totalBytesQueued;
    }

    /**
     * Determine if this queue is empty.
     *
     * @return true if this queue is empty, otherwise false
     */
    public boolean isEmpty() {
        return this.totalBytesQueued == 0;
    }

    /**
     * Determine if this queue is full.
     *
     * @return true if {@link #getTotalBytesQueued} is at least the full mark provided to the constructor.
     */
    public boolean isFull() {
        return this.totalBytesQueued >= this.fullMark;
    }

    /**
     * Add the given data to queue.
     *
     * <p>
     * The caller must assume that this method takes "ownership" of {@code data}.
     *
     * @param data to enqueue
     * @return some combination of the flags {@link #WAS_EMPTY}, {@link #WAS_FULL}, {@link #NOW_EMPTY}, and/or {@link #NOW_FULL}
     * @throws IllegalArgumentException if {@code data} is null
     */
    public int enqueue(ByteBuffer data) {

        // Sanity check
        if (data == null)
            throw new IllegalArgumentException("null data");

        // Initialize flags
        int flags = this.readWasFlags();

        // Add data (unless buffer is empty)
        final int remaining = data.remaining();
        if (remaining > 0) {
            this.queue.addLast(data);
            this.totalBytesQueued += data.remaining();
        }

        // Done
        return flags | this.readNowFlags();
    }

    /**
     * Write as much data as possible to the specified output channel.
     *
     * @param output destination for enqueued data
     * @return some combination of the flags {@link #WAS_EMPTY}, {@link #WAS_FULL}, {@link #NOW_EMPTY}, and/or {@link #NOW_FULL}
     * @throws IOException if thrown by {@code output}
     * @throws IllegalArgumentException if {@code output} is null
     */
    public int writeTo(WritableByteChannel output) throws IOException {

        // Sanity check
        if (output == null)
            throw new IllegalArgumentException("null output");

        // Initialize flags
        int flags = this.readWasFlags();

        // Output data, using scatter/gather if possible
        if (output instanceof GatheringByteChannel) {

            // Attempt a gather write of all buffers at once
            final long written = ((GatheringByteChannel)output).write(this.queue.toArray(new ByteBuffer[0]));
            this.totalBytesQueued -= written;

            // Discard now-empty buffers
            while (!this.queue.isEmpty() && !this.queue.getFirst().hasRemaining())
                this.queue.removeFirst();
        } else {

            // Write data from each buffer one at a time
            while (!this.queue.isEmpty()) {
                final ByteBuffer data = this.queue.getFirst();
                final int written = output.write(data);
                this.totalBytesQueued -= written;
                if (data.hasRemaining())                    // output didn't accept all of this buffer's data, so stop for now
                    break;
                this.queue.removeFirst();
            }
        }

        // Done
        return flags | this.readNowFlags();
    }

    /**
     * Return a debug description of the given flags.
     *
     * @param flags flag bits
     * @return debug description
     */
    public static String describeFlags(int flags) {
        return FLAGS_DESCRIPTIONS[flags & (NOW_EMPTY | NOW_FULL | WAS_EMPTY | WAS_FULL)];
    }

    private int readWasFlags() {
        return this.readNowFlags() << 2;
    }

    private int readNowFlags() {
        int flags = 0;
        if (this.isEmpty())
            flags |= NOW_EMPTY;
        if (this.isFull())
            flags |= NOW_FULL;
        return flags;
    }
}
