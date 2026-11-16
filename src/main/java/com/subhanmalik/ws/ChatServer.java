package com.subhanmalik.ws;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP accept loop for the chat server: one thread per connection, each
 * connection handed to a {@link ChatSession}. All chat behaviour lives in
 * {@link ChatSession}; this class only owns the socket.
 */
public final class ChatServer {

    private final ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ChatHub hub = new ChatHub();
    private final ChatSession session = new ChatSession(hub, new AtomicInteger(1));
    private volatile boolean running;

    public ChatServer(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    /** The port actually bound (useful when constructed with port 0). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public ChatHub hub() {
        return hub;
    }

    public void start() {
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() throws IOException {
        running = false;
        serverSocket.close();
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.submit(() -> {
                    try {
                        session.handle(WebSocketConnection.accept(socket));
                    } catch (IOException e) {
                        // Handshake failed or peer vanished; nothing to clean up.
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                            // already closing
                        }
                    }
                });
            } catch (IOException e) {
                if (running) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }
}
