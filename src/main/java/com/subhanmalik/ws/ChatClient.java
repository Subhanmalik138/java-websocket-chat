package com.subhanmalik.ws;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal terminal client: performs the opening handshake, then forwards
 * stdin lines as masked text frames and prints incoming messages.
 *
 * <p>Deliberately small — it exists to exercise the server by hand, not to
 * be a full chat UI. Any WebSocket client (a browser console, {@code websocat},
 * Python's {@code websockets}) can connect to the server instead.
 */
public final class ChatClient {

    private ChatClient() {
    }

    public static void run(String host, int port, String nick) throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(0);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET /chat HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = WebSocketConnection.readLine(in);
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("handshake failed: " + statusLine);
        }
        Map<String, String> headers = WebSocketConnection.readHttpRequestHeaders(in);
        String accept = headers.get("sec-websocket-accept");
        if (!WebSocketConnection.computeAcceptKey(key).equals(accept)) {
            throw new IOException("handshake failed: bad Sec-WebSocket-Accept");
        }
        System.out.println("Connected to ws://" + host + ":" + port + " — type /quit to leave.");

        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    Frame frame = Frame.decode(in);
                    if (frame == null) {
                        System.out.println("\n[connection closed by server]");
                        System.exit(0);
                    }
                    if (frame.opcode() == Frame.TEXT) {
                        System.out.println(new String(frame.payload(), StandardCharsets.UTF_8));
                    } else if (frame.opcode() == Frame.PING) {
                        Frame.write(out, Frame.PONG, frame.payload(), true);
                    } else if (frame.opcode() == Frame.CLOSE) {
                        System.out.println("\n[server closed the connection]");
                        System.exit(0);
                    }
                }
            } catch (IOException e) {
                System.out.println("\n[connection lost: " + e.getMessage() + "]");
                System.exit(1);
            }
        }, "ws-client-reader");
        reader.setDaemon(true);
        reader.start();

        if (nick != null && !nick.isBlank()) {
            Frame.write(out, Frame.TEXT, ("/nick " + nick).getBytes(StandardCharsets.UTF_8), true);
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            Frame.write(out, Frame.TEXT, line.getBytes(StandardCharsets.UTF_8), true);
            if (line.trim().equals("/quit")) {
                break;
            }
        }
        socket.close();
    }
}
