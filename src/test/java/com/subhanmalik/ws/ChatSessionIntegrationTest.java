package com.subhanmalik.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end session tests: a real {@link ChatSession} driving a real
 * {@link WebSocketConnection}, with the client speaking the wire protocol by
 * hand over in-memory pipes. The full path is exercised — handshake, masked
 * client frames, broadcast, commands, ping/pong and the close handshake —
 * without binding a port, so the suite runs anywhere, including sandboxes
 * that block loopback TCP.
 */
@Timeout(15)
class ChatSessionIntegrationTest {

    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private final ChatHub hub = new ChatHub();
    private final ChatSession session = new ChatSession(hub, new AtomicInteger(1));

    /** A client connected to a session over two in-memory pipes. */
    private static final class PipeClient implements AutoCloseable {
        final PipedOutputStream toServer = new PipedOutputStream();
        final PipedInputStream fromServer = new PipedInputStream();
        // One buffered stream for the whole connection: a fresh BufferedInputStream
        // per read would swallow frame bytes sitting behind the headers.
        final BufferedInputStream in = new BufferedInputStream(fromServer);
        final Thread serverThread;

        PipeClient(ChatSession session) throws IOException {
            PipedInputStream serverIn = new PipedInputStream(toServer);
            PipedOutputStream serverOut = new PipedOutputStream(fromServer);
            serverThread = new Thread(() -> {
                try {
                    session.handle(WebSocketConnection.accept(serverIn, serverOut));
                } catch (IOException ignored) {
                    // Handshake rejected or peer gone; expected in the 400 test.
                }
            }, "ws-session-test");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        void handshake() throws IOException {
            String request = "GET /chat HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + KEY + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            toServer.write(request.getBytes(StandardCharsets.US_ASCII));
            toServer.flush();
            String status = WebSocketConnection.readLine(in);
            assertTrue(status.contains("101"), "expected 101, got: " + status);
            var headers = WebSocketConnection.readHttpRequestHeaders(in);
            assertEquals(WebSocketConnection.computeAcceptKey(KEY),
                    headers.get("sec-websocket-accept"));
        }

        void sendText(String text) throws IOException {
            Frame.write(toServer, Frame.TEXT, text.getBytes(StandardCharsets.UTF_8), true);
        }

        void sendRaw(byte[] bytes) throws IOException {
            toServer.write(bytes);
            toServer.flush();
        }

        /** Reads frames until one is a text frame containing {@code needle}. */
        String readTextContaining(String needle) throws IOException {
            for (int i = 0; i < 20; i++) {
                Frame frame = Frame.decode(in);
                if (frame == null) {
                    throw new IOException("connection closed while waiting for: " + needle);
                }
                if (frame.opcode() == Frame.TEXT) {
                    String text = new String(frame.payload(), StandardCharsets.UTF_8);
                    if (text.contains(needle)) {
                        return text;
                    }
                }
            }
            throw new AssertionError("never received a message containing: " + needle);
        }

        /** Reads the next frame, whatever it is. */
        Frame readFrame() throws IOException {
            return Frame.decode(in);
        }

        /**
         * Reads whatever text frames are already queued (up to {@code max}),
         * waiting briefly for the first one. Piped streams have no timeout,
         * so polling via {@code available()} keeps a quiet server from
         * blocking the test.
         */
        List<String> drainTexts(int max) throws IOException, InterruptedException {
            List<String> texts = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 1_000;
            while (texts.size() < max && System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    Frame frame = Frame.decode(in);
                    if (frame == null || frame.opcode() != Frame.TEXT) {
                        break;
                    }
                    texts.add(new String(frame.payload(), StandardCharsets.UTF_8));
                } else {
                    Thread.sleep(10);
                }
            }
            return texts;
        }

        @Override
        public void close() throws IOException {
            toServer.close();
            fromServer.close();
        }
    }

    @Test
    void messageFromOneClientReachesTheOther() throws IOException {
        try (PipeClient alice = new PipeClient(session);
             PipeClient bob = new PipeClient(session)) {
            alice.handshake();
            bob.handshake();
            alice.readTextContaining("Welcome");
            bob.readTextContaining("Welcome");
            alice.sendText("hello room");
            String received = bob.readTextContaining("hello room");
            assertTrue(received.contains("guest-"), "broadcast should carry the sender's nick");
        }
    }

    @Test
    void nickAndJoinCommandsWork() throws IOException, InterruptedException {
        try (PipeClient alice = new PipeClient(session);
             PipeClient bob = new PipeClient(session)) {
            alice.handshake();
            bob.handshake();
            alice.readTextContaining("Welcome");
            bob.readTextContaining("Welcome");
            alice.sendText("/nick alice");
            assertEquals("You are now alice.", alice.readTextContaining("You are now"));
            alice.sendText("/join dev");
            assertEquals("You joined #dev.", alice.readTextContaining("You joined"));
            // alice left the lobby, so bob no longer hears her.
            alice.sendText("secret in dev");
            List<String> bobHeard = bob.drainTexts(3);
            assertTrue(bobHeard.stream().noneMatch(t -> t.contains("secret in dev")));
            // But a second client in #dev does hear her.
            try (PipeClient carol = new PipeClient(session)) {
                carol.handshake();
                carol.readTextContaining("Welcome");
                carol.sendText("/join dev");
                carol.readTextContaining("You joined");
                alice.sendText("hello dev");
                assertTrue(carol.readTextContaining("hello dev").contains("alice:"));
            }
        }
    }

    @Test
    void pingIsAnsweredWithPong() throws IOException {
        try (PipeClient client = new PipeClient(session)) {
            client.handshake();
            client.readTextContaining("Welcome");
            byte[] payload = "heartbeat".getBytes(StandardCharsets.UTF_8);
            Frame.write(client.toServer, Frame.PING, payload, true);
            Frame reply = client.readFrame();
            assertEquals(Frame.PONG, reply.opcode());
            assertEquals("heartbeat", new String(reply.payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void closeFrameIsEchoedAndConnectionEnds() throws IOException {
        try (PipeClient client = new PipeClient(session)) {
            client.handshake();
            client.readTextContaining("Welcome");
            Frame.write(client.toServer, Frame.CLOSE, new byte[]{0x03, (byte) 0xE8}, true);
            Frame reply = client.readFrame();
            assertEquals(Frame.CLOSE, reply.opcode());
        }
    }

    @Test
    void nonUpgradeRequestIsRejected() throws IOException {
        try (PipeClient client = new PipeClient(session)) {
            client.sendRaw("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String status = WebSocketConnection.readLine(client.in);
            assertTrue(status.contains("400"), "expected 400, got: " + status);
        }
    }
}
