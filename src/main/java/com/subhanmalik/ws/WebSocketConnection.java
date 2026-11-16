package com.subhanmalik.ws;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * One WebSocket connection: the HTTP Upgrade handshake plus the frame loop.
 *
 * <p>Server side only — this class accepts an incoming TCP socket, performs
 * the RFC 6455 opening handshake, then reads complete text messages,
 * reassembling fragmented ones and answering pings with pongs. A close frame
 * is answered with a close frame and {@link #readMessage()} then returns
 * {@code null}.
 */
public final class WebSocketConnection implements ChatHub.Peer, AutoCloseable {

    /** Magic GUID from RFC 6455, section 1.3. */
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile String nick;
    private volatile boolean open = true;

    private WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Derives the {@code Sec-WebSocket-Accept} value for a client key:
     * {@code base64(sha1(key + GUID))}.
     */
    public static String computeAcceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * Performs the opening handshake on an accepted socket and returns the
     * established connection.
     *
     * @throws IOException if the request is not a valid WebSocket upgrade
     */
    public static WebSocketConnection accept(Socket socket) throws IOException {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        handshake(in, out, socket);
        return new WebSocketConnection(socket, in, out);
    }

    /**
     * Performs the opening handshake on raw streams (no socket) and returns
     * the established connection. Used by tests to drive a full session over
     * in-memory pipes without binding a port.
     */
    static WebSocketConnection accept(InputStream rawIn, OutputStream out) throws IOException {
        BufferedInputStream in = new BufferedInputStream(rawIn);
        handshake(in, out, null);
        return new WebSocketConnection(null, in, out);
    }

    /** Reads the upgrade request, validates it and writes the 101 response. */
    private static void handshake(BufferedInputStream in, OutputStream out, Socket socket)
            throws IOException {
        Map<String, String> headers = readHttpRequest(in);
        String key = headers.get("sec-websocket-key");
        String upgrade = headers.get("upgrade");
        String connection = headers.get("connection");
        if (key == null || key.isBlank()
                || upgrade == null || !upgrade.equalsIgnoreCase("websocket")
                || connection == null || !connection.toLowerCase().contains("upgrade")) {
            String body = "Not a WebSocket upgrade request.\r\n";
            out.write(("HTTP/1.1 400 Bad Request\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (socket != null) {
                socket.close();
            }
            throw new IOException("invalid WebSocket upgrade request");
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + computeAcceptKey(key.trim()) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads the request line plus headers; header names are lower-cased. */
    static Map<String, String> readHttpRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || !line.startsWith("GET ")) {
            throw new IOException("expected an HTTP GET request line, got: " + line);
        }
        return readHttpRequestHeaders(in);
    }

    /** Reads header lines until the blank line that ends the header block. */
    static Map<String, String> readHttpRequestHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("end of stream while reading HTTP headers");
            }
            if (line.isEmpty()) {
                return headers; // blank line ends the header block
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }
        }
    }

    /** Reads one CRLF-terminated line, without buffering past the line end. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Override
    public String displayName() {
        return nick == null ? "guest" : nick;
    }

    public void setNick(String nick) {
        this.nick = nick;
    }

    public String nick() {
        return nick;
    }

    public boolean isOpen() {
        return open && (socket == null || !socket.isClosed());
    }

    @Override
    public void send(String text) {
        sendFrame(Frame.TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendPing(byte[] payload) {
        sendFrame(Frame.PING, payload);
    }

    private void sendFrame(int opcode, byte[] payload) {
        synchronized (writeLock) {
            try {
                Frame.write(out, opcode, payload, false); // server frames are never masked
            } catch (IOException e) {
                open = false;
            }
        }
    }

    /** Sends a close frame (code + reason) and closes the socket. */
    public void close(int code, String reason) {
        if (!open) {
            return;
        }
        open = false;
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        try {
            synchronized (writeLock) {
                Frame.write(out, Frame.CLOSE, payload, false);
            }
        } catch (IOException ignored) {
            // peer already gone; nothing to do
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already closing
                }
            }
        }
    }

    /**
     * Reads the next complete text message, reassembling fragments.
     *
     * @return the message, or {@code null} when the peer closed the connection
     */
    public String readMessage() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            Frame frame = Frame.decode(in);
            if (frame == null) {
                return null; // TCP closed without a close frame
            }
            switch (frame.opcode()) {
                case Frame.TEXT:
                case Frame.CONTINUATION: {
                    if (message == null) {
                        if (frame.opcode() == Frame.CONTINUATION) {
                            throw new IOException("continuation frame without a message start");
                        }
                        message = new ByteArrayOutputStream();
                    }
                    message.write(frame.payload());
                    if (frame.fin()) {
                        return message.toString(StandardCharsets.UTF_8);
                    }
                    break;
                }
                case Frame.BINARY:
                    // Chat is text-only; tell the peer and drop the connection.
                    close(1003, "binary frames are not supported");
                    return null;
                case Frame.PING:
                    sendFrame(Frame.PONG, frame.payload());
                    break;
                case Frame.PONG:
                    break; // unsolicited pong; ignore
                case Frame.CLOSE: {
                    int code = 1005;
                    byte[] payload = frame.payload();
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    }
                    String reason = payload.length > 2
                            ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                            : "";
                    close(code, reason);
                    return null;
                }
                default:
                    throw new IOException("unexpected opcode: " + frame.opcode());
            }
        }
    }

    @Override
    public void close() {
        close(1000, "");
    }
}
