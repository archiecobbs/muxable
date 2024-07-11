/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SimpleMuxableChannelTest {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    // Test that a muxable closes itself when its peer connection dies
    @Test
    public void test1() throws Exception {

        // Create outer muxable
        final Pipe left2rite = Pipe.open();
        final Pipe rite2left = Pipe.open();

        final SimpleMuxableChannel leftMuxable = newMuxable(rite2left.source(), left2rite.sink(), "left");
        final SimpleMuxableChannel riteMuxable = newMuxable(left2rite.source(), rite2left.sink(), "rite");

        this.log.info("TEST: starting leftMuxable");
        leftMuxable.start();
        this.log.info("TEST: starting riteMuxable");
        riteMuxable.start();

        // Close one direction of the peer-to-peer connection
        this.log.info("TEST: closing rite2left.source()");
        rite2left.source().close();

        // Sleep long enough for housekeeping
        Thread.sleep(1500);

        // Verify
        Assert.assertFalse(leftMuxable.isOpen());
        Assert.assertFalse(riteMuxable.isOpen());
    }

    // Test that a nested muxable gets automatically closed when the outer muxable is closed
    @Test
    public void test2() throws Exception {

        // Create outer muxable
        final Pipe left2rite = Pipe.open();
        final Pipe rite2left = Pipe.open();

        final SimpleMuxableChannel leftMuxable = newMuxable(rite2left.source(), left2rite.sink(), "left");
        final SimpleMuxableChannel riteMuxable = newMuxable(left2rite.source(), rite2left.sink(), "rite");

        this.log.info("TEST: starting leftMuxable");
        leftMuxable.start();
        this.log.info("TEST: starting riteMuxable");
        riteMuxable.start();

        // Create one nested channel
        final SimpleNestedChannel leftNested = requestNewNested(leftMuxable, 'A');
        final SimpleNestedChannel riteNested = acceptNewNested(riteMuxable, 'A');

        // Create inner muxable
        final SimpleMuxableChannel leftMuxable2 = newNestedMuxable(leftNested, "left2");
        final SimpleMuxableChannel riteMuxable2 = newNestedMuxable(riteNested, "rite2");

        this.log.info("TEST: starting leftMuxable2");
        leftMuxable2.start();
        this.log.info("TEST: starting riteMuxable2");
        riteMuxable2.start();

        // Close outer muxable
        this.log.info("TEST: closing leftMuxable");
        leftMuxable.close();
        this.log.info("TEST: closing riteMuxable");
        riteMuxable.close();

        Assert.assertFalse(leftMuxable.isOpen());
        Assert.assertFalse(riteMuxable.isOpen());

        // Sleep long enough for housekeeping
        Thread.sleep(1500);

        // Verify inner muxable got automatically closed
        Assert.assertFalse(leftMuxable2.isOpen());
        Assert.assertFalse(riteMuxable2.isOpen());
    }

    // Create a muxable with one nested channel, and send data over the nested channel
    @Test
    public void test3() throws Exception {

        final Pipe left2rite = Pipe.open();
        final Pipe rite2left = Pipe.open();

        final SimpleMuxableChannel leftMuxable = newMuxable(rite2left.source(), left2rite.sink(), "left");
        final SimpleMuxableChannel riteMuxable = newMuxable(left2rite.source(), rite2left.sink(), "rite");

        leftMuxable.start();
        riteMuxable.start();

        final SimpleNestedChannel leftNested = requestNewNested(leftMuxable, 'A');
        final SimpleNestedChannel riteNested = acceptNewNested(riteMuxable, 'A');

        final String l2r = "from left to rite";
        final String r2l = "from rite to left";

        write(leftNested, l2r);
        write(riteNested, r2l);

        verifyRead(leftNested, r2l);
        verifyRead(riteNested, l2r);

        leftMuxable.close();
        riteMuxable.close();

        Assert.assertFalse(leftNested.getInput().isOpen());
        Assert.assertFalse(riteNested.getInput().isOpen());
        Assert.assertFalse(leftNested.getOutput().isOpen());
        Assert.assertFalse(riteNested.getOutput().isOpen());
    }

    // Create a muxable with one nested channel, and then create another muxable using the nested channel
    // from the first muxable, and then send data over it
    @Test
    public void test4() throws Exception {

        final Pipe left2rite = Pipe.open();
        final Pipe rite2left = Pipe.open();

        final SimpleMuxableChannel leftMuxable = newMuxable(rite2left.source(), left2rite.sink(), "left");
        final SimpleMuxableChannel riteMuxable = newMuxable(left2rite.source(), rite2left.sink(), "rite");

        leftMuxable.start();
        riteMuxable.start();

        final SimpleNestedChannel leftNested = requestNewNested(leftMuxable, 'A');
        final SimpleNestedChannel riteNested = acceptNewNested(riteMuxable, 'A');

        final SimpleMuxableChannel leftMuxable2 = newNestedMuxable(leftNested, "left2");
        final SimpleMuxableChannel riteMuxable2 = newNestedMuxable(riteNested, "rite2");

        leftMuxable2.start();
        riteMuxable2.start();

        final SimpleNestedChannel leftNested2 = requestNewNested(leftMuxable2, 'B');
        final SimpleNestedChannel riteNested2 = acceptNewNested(riteMuxable2, 'B');

        final String l2r = "from left to rite";
        final String r2l = "from rite to left";

        write(leftNested2, l2r);
        write(riteNested2, r2l);

        verifyRead(leftNested2, r2l);
        verifyRead(riteNested2, l2r);

        //leftMuxable2.stop();
        //riteMuxable2.stop();

        leftMuxable.close();
        riteMuxable.close();

        Thread.sleep(500);

        Assert.assertFalse(leftMuxable.isOpen());
        Assert.assertFalse(riteMuxable.isOpen());
        Assert.assertFalse(leftNested.getInput().isOpen());
        Assert.assertFalse(riteNested.getInput().isOpen());
        Assert.assertFalse(leftNested.getOutput().isOpen());
        Assert.assertFalse(riteNested.getOutput().isOpen());

        Assert.assertFalse(leftMuxable2.isOpen());
        Assert.assertFalse(riteMuxable2.isOpen());
        Assert.assertFalse(leftNested2.getInput().isOpen());
        Assert.assertFalse(riteNested2.getInput().isOpen());
        Assert.assertFalse(leftNested2.getOutput().isOpen());
        Assert.assertFalse(riteNested2.getOutput().isOpen());
    }

    /*
        Create this topology:

                ┏━━━━━━━━┓
                ┃ Parent ┃
                ┗━━━━━━━━┛
                /        \
            ┏━━━┓        ┏━━━┓
            ┃ 1 ┃        ┃ 2 ┃
            ┗━━━┛        ┗━━━┛
            /   \       /    \
         ┏━━━┓ ┏━━━┓  ┏━━━┓ ┏━━━┓
         ┃ 3 ┃ ┃ 4 ┃  ┃ 5 ┃ ┃ 6 ┃
         ┗━━━┛ ┗━━━┛  ┗━━━┛ ┗━━━┛
    */

    @Test
    public void test5() throws Exception {

        final int port = 45308;
        final InetAddress loopback = InetAddress.getLoopbackAddress();
        final InetSocketAddress bindAddress = new InetSocketAddress(loopback, port);
        final ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(bindAddress);

        final ServerThread serverThread = new ServerThread(serverSocketChannel);
        final ClientThread clientThread = new ClientThread(bindAddress);
        serverThread.setClientThread(clientThread);
        clientThread.setServerThread(serverThread);

        this.log.info("starting server thread");
        serverThread.start();
        this.log.info("starting client thread");
        clientThread.start();
        this.log.info("joining server thread");
        serverThread.join();
        this.log.info("joining client thread");
        clientThread.join();

        Assert.assertTrue(serverThread.success);
        Assert.assertTrue(clientThread.success);
    }

    private class ServerThread extends Thread {

        public volatile boolean success;

        private final Logger log = LoggerFactory.getLogger(this.getClass());
        private final ServerSocketChannel serverSocketChannel;

        private ClientThread clientThread;

        ServerThread(ServerSocketChannel serverSocketChannel) {
            super("server");
            this.serverSocketChannel = serverSocketChannel;
        }

        public void setClientThread(ClientThread clientThread) {
            this.clientThread = clientThread;
        }

        @Override
        public void run() {
            try {

                // Accept connection from client
                this.log.info("TEST: accepting connection");
                final SocketChannel socketChannel = this.serverSocketChannel.accept();
                this.log.info("TEST: accepted connection via {}", socketChannel);

                // Create parent channel
                this.log.info("TEST: creating parent channel");
                final SimpleMuxableChannel parent = newMuxable(socketChannel, "server:parent");
                this.log.info("TEST: created {}", parent);
                parent.start();

                // Create I/O channel 1
                this.log.info("TEST: verifying nested channel 1");
                final SimpleNestedChannel nested1 = acceptNewNested(parent, '1');

                // Exchange data on channel 1
                this.log.info("TEST: exchanging data on nested channel 1");
                write(nested1, "a: hello from server on nested channel 1");
                verifyRead(nested1, "b: hello from client on nested channel 1");

                // Create muxable channel #1
                final SimpleMuxableChannel muxable1 = newNestedMuxable(nested1, "server:muxable1");
                this.log.info("TEST: created {}", muxable1);
                muxable1.start();

                // Create muxable channel #2
                this.log.info("TEST: requesting nested channel 2");
                final SimpleNestedChannel nested2 = requestNewNested(parent, '2');
                final SimpleMuxableChannel muxable2 = newNestedMuxable(nested2, "server:muxable2");
                this.log.info("TEST: created {}", muxable2);
                muxable2.start();

                // Create other muxable channels
                this.log.info("TEST: requesting nested channel 3");
                final SimpleNestedChannel nested3 = requestNewNested(muxable1, '3');
                this.log.info("TEST: requesting nested channel 5");
                final SimpleNestedChannel nested5 = requestNewNested(muxable2, '5');
                this.log.info("TEST: verifying nested channel 4");
                final SimpleNestedChannel nested4 = acceptNewNested(muxable1, '4');
                this.log.info("TEST: verifying nested channel 6");
                final SimpleNestedChannel nested6 = acceptNewNested(muxable2, '6');

                // Exchange data on various channels
                write(nested3, "c: hello from server on nested channel 3");
                verifyRead(nested3, "d: hello from client on nested channel 3");
                write(nested4, "hello from server on nested channel 4");
                write(nested6, "hello from server on nested channel 6");

                verifyRead(nested4, "hello from client on nested channel 4");
                verifyRead(nested6, "hello from client on nested channel 6");

                // Let other side finish reading
                Thread.sleep(500);

                // Close channel 6 - this should not affect channel 5
                this.log.info("TEST: closing nested channel 6");
                nested6.close();
                Thread.sleep(500);
                Assert.assertTrue(nested5.getInput().isOpen());
                Assert.assertFalse(nested6.getOutput().isOpen());
                Assert.assertTrue(muxable2.isOpen());

                write(nested5, "hello from server on nested channel 5");
                verifyRead(nested5, "hello from client on nested channel 5");

                // Let other side finish reading
                Thread.sleep(500);

                // Close nested channel 5
                nested5.close();
                Thread.sleep(500);
                Assert.assertFalse(nested5.getInput().isOpen());
                Assert.assertFalse(nested5.getOutput().isOpen());
                Assert.assertTrue(muxable2.isOpen());

                // Should still be able to open a new nested channel on channel 2
                requestNewNested(muxable2, 'x');
                acceptNewNested(muxable2, 'y');

                // Now close channel 1, which should also close channels 3 & 4
                Assert.assertTrue(muxable1.isOpen());
                Assert.assertSame(nested3.getParent(), muxable1);
                Assert.assertTrue(nested3.getInput().isOpen());
                Assert.assertTrue(nested3.getOutput().isOpen());
                Assert.assertTrue(nested4.getInput().isOpen());
                Assert.assertTrue(nested4.getOutput().isOpen());
                this.log.info("TEST: closing {}", muxable1);
                muxable1.close();
                Assert.assertFalse(muxable1.isOpen());
                Thread.sleep(500);
                Assert.assertFalse(nested3.getInput().isOpen());
                Assert.assertFalse(nested3.getOutput().isOpen());
                Assert.assertFalse(nested4.getInput().isOpen());
                Assert.assertFalse(nested4.getOutput().isOpen());

                // Done
                this.log.info("TEST: closing {}", parent);
                parent.close();

                this.success = true;
                this.log.info("TEST: done");
            } catch (Throwable e) {
                this.log.error("error in server thread: {}", String.valueOf(e), e);
                this.clientThread.interrupt();
            }
        }
    }

    private class ClientThread extends Thread {

        public volatile boolean success;

        private final Logger log = LoggerFactory.getLogger(this.getClass());
        private final InetSocketAddress serverAddress;

        private ServerThread serverThread;

        ClientThread(InetSocketAddress serverAddress) {
            super("client");
            this.serverAddress = serverAddress;
        }

        public void setServerThread(ServerThread serverThread) {
            this.serverThread = serverThread;
        }

        @Override
        public void run() {
            try {

                // Connect to server
                this.log.info("TEST: connecting to {}", this.serverAddress);
                final SocketChannel socketChannel = SocketChannel.open(this.serverAddress);

                // Create parent channel
                this.log.info("TEST: creating parent channel");
                final SimpleMuxableChannel parent = newMuxable(socketChannel, "client:parent");
                this.log.info("TEST: created {}", parent);
                parent.start();

                // Create I/O channel 1
                this.log.info("TEST: requesting nested channel 1");
                final SimpleNestedChannel nested1 = requestNewNested(parent, '1');

                // Exchange data on channel 1
                this.log.info("TEST: exchanging data on nested channel 1");
                write(nested1, "b: hello from client on nested channel 1");
                verifyRead(nested1, "a: hello from server on nested channel 1");

                // Create muxable channel #1
                final SimpleMuxableChannel muxable1 = newNestedMuxable(nested1, "client:muxable1");
                this.log.info("TEST: created {}", muxable1);
                muxable1.start();

                // Create muxable channel #2
                this.log.info("TEST: verifying nested channel 2");
                final SimpleNestedChannel request2 = acceptNewNested(parent, '2');
                final SimpleMuxableChannel muxable2 = newNestedMuxable(request2, "client:muxable2");
                this.log.info("TEST: created {}", muxable2);
                muxable2.start();

                // Create other muxable channels
                this.log.info("TEST: requesting nested channel 6");
                final SimpleNestedChannel nested6 = requestNewNested(muxable2, '6');
                this.log.info("TEST: requesting nested channel 4");
                final SimpleNestedChannel nested4 = requestNewNested(muxable1, '4');
                this.log.info("TEST: verifying nested channel 5");
                final SimpleNestedChannel nested5 = acceptNewNested(muxable2, '5');
                this.log.info("TEST: verifying nested channel 3");
                final SimpleNestedChannel nested3 = acceptNewNested(muxable1, '3');

                // Exchange data on various channels
                write(nested4, "hello from client on nested channel 4");
                write(nested6, "hello from client on nested channel 6");

                write(nested3, "d: hello from client on nested channel 3");
                verifyRead(nested3, "c: hello from server on nested channel 3");
                verifyRead(nested4, "hello from server on nested channel 4");
                verifyRead(nested6, "hello from server on nested channel 6");

                // Let other side finish reading
                Thread.sleep(500);

                // Close nested channel 6 - this should not affect nested channel 5
                this.log.info("TEST: closing nested channel 6");
                nested6.close();
                Thread.sleep(500);
                Assert.assertTrue(nested5.getInput().isOpen());
                Assert.assertTrue(nested5.getOutput().isOpen());
                Assert.assertFalse(nested6.getInput().isOpen());
                Assert.assertFalse(nested6.getOutput().isOpen());
                Assert.assertTrue(muxable2.isOpen());

                write(nested5, "hello from client on nested channel 5");
                verifyRead(nested5, "hello from server on nested channel 5");

                // Let other side finish reading
                Thread.sleep(500);

                // Close nested channel 5
                this.log.info("TEST: closing nested channel 5");
                nested5.close();
                Thread.sleep(500);
                Assert.assertFalse(nested5.getInput().isOpen());
                Assert.assertFalse(nested5.getOutput().isOpen());
                Assert.assertTrue(muxable2.isOpen());

                // Should still be able to open a new nested channel on channel 2
                requestNewNested(muxable2, 'y');
                acceptNewNested(muxable2, 'x');

                // Now close channel 1, which should also close channels 3 & 4
                Assert.assertTrue(muxable1.isOpen());
                Assert.assertTrue(nested3.getInput().isOpen());
                Assert.assertTrue(nested3.getOutput().isOpen());
                Assert.assertTrue(nested4.getInput().isOpen());
                Assert.assertTrue(nested4.getOutput().isOpen());
                this.log.info("TEST: closing {}", muxable1);
                muxable1.close();
                Assert.assertFalse(muxable1.isOpen());
                Thread.sleep(500);
                Assert.assertFalse(nested3.getInput().isOpen());
                Assert.assertFalse(nested3.getOutput().isOpen());

                // Done
                this.log.info("TEST: closing {}", parent);
                parent.close();

                this.success = true;
                this.log.info("TEST: done");
            } catch (Throwable e) {
                this.log.error("error in client thread: {}", String.valueOf(e), e);
                this.serverThread.interrupt();
            }
        }
    }

// Helpers

    static SimpleMuxableChannel newNestedMuxable(SimpleNestedChannel request, String name) {
        return newMuxable(request.getInput(), request.getOutput(), name);
    }

    static <C extends SelectableChannel & ByteChannel> SimpleMuxableChannel newMuxable(C channel, String name) {
        return new SimpleMuxableChannel(channel) {

            @Override
            protected LoggingSupport buildLoggingSupport() {
                return new LoggingSupport(LoggerFactory.getLogger(this.getClass()), name + ": ");
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    static <
        I extends SelectableChannel & ReadableByteChannel,
        O extends SelectableChannel & WritableByteChannel>
      SimpleMuxableChannel newMuxable(I input, O output, String name) {
        return new SimpleMuxableChannel(input, output) {

            @Override
            protected LoggingSupport buildLoggingSupport() {
                return new LoggingSupport(LoggerFactory.getLogger(this.getClass()), name + ": ");
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private SimpleNestedChannel requestNewNested(SimpleMuxableChannel channel, char id) throws Exception {
        return channel.newNestedChannel(ByteBuffer.wrap(new byte[] { (byte)id }));
    }

    private SimpleNestedChannel acceptNewNested(SimpleMuxableChannel channel, char id) throws Exception {

        // Wait for incoming request
        final SimpleNestedChannel request = channel.getNestedChannelRequests().poll(1, TimeUnit.SECONDS);
        if (request == null)
            throw new RuntimeException("no request rec'd after 1000ms");

        // Verify request data matches
        this.log.info("TEST: recv {}", request);
        final ByteBuffer data = request.getRequestData();
        Assert.assertEquals((char)(data.get() & 0xff), id);
        Assert.assertEquals(data.remaining(), 0);
        this.log.info("TEST: verified new channel request with \"{}\"", id);

        // Done
        return request;
    }

    private void write(SimpleNestedChannel request, String string) throws IOException, InterruptedException {
        if (string == null || string.indexOf('!') != -1)
            throw new IllegalArgumentException("invalid string");
        this.log.info("TEST: sending \"{}\"", string);
        string = string + "!";
        for (int i = 0; i < string.length(); i++) {
            final char ch = string.charAt(i);
            while (request.getOutput().write(ByteBuffer.wrap(new byte[] { (byte)ch })) == 0)
                Thread.sleep(10);
        }
    }

    private void verifyRead(SimpleNestedChannel request, String expected) throws IOException, InterruptedException {
        final char[] chars = new char[1000];
        int length = 0;
        while (true) {
            final ByteBuffer buf = ByteBuffer.allocate(1);
            while (request.getInput().read(buf) == 0)
                Thread.sleep(10);
            final char ch = (char)(buf.get(0) & 0xff);
            if (ch == '!')
                break;
            chars[length++] = ch;
        }
        final String actual = new String(chars, 0, length);
        this.log.info("TEST: received \"{}\"", actual);
        Assert.assertEquals(actual, expected);
    }
}
