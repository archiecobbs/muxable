
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

/**
 * Constants used in the {@link SimpleMuxableChannel} framing protocol.
 */
public final class ProtocolConstants {

    /**
     * Magic value that verifies remote side is speaking our language.
     */
    public static final long PROTOCOL_COOKIE = 0x6cca45a0a414793eL;

    /**
     * Current protocol version.
     */
    public static final long CURRENT_PROTOCOL_VERSION = 1;

    /**
     * Flag that indicates a new nested channel should support communicating from the peer to the originator.
     */
    public static final int FLAG_DIRECTION_INPUT = 0x01;

    /**
     * Flag that indicates a new nested channel should support communicating from the originator to the peer.
     */
    public static final int FLAG_DIRECTION_OUTPUT = 0x02;

    private ProtocolConstants() {
    }
}
