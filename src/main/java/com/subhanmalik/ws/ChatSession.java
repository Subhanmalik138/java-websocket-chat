package com.subhanmalik.ws;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connected client's session: welcome, chat commands, broadcast, and
 * clean disconnect. Deliberately transport-free — it works on any
 * {@link WebSocketConnection}, whether that connection came from a real
 * socket or from in-memory pipes in a test.
 *
 * <p>Chat commands (sent as text messages, starting with {@code /}):
 * <ul>
 *   <li>{@code /nick <name>} — change display name (unique per room)</li>
 *   <li>{@code /join <room>} — move to another room</li>
 *   <li>{@code /rooms} — list non-empty rooms</li>
 *   <li>{@code /who} — list people in your room</li>
 *   <li>{@code /help} — show this list</li>
 *   <li>{@code /quit} — leave</li>
 * </ul>
 * Anything else is broadcast to the sender's room.
 */
public final class ChatSession {

    public static final String DEFAULT_ROOM = "lobby";

    private final ChatHub hub;
    private final AtomicInteger guestCounter;

    public ChatSession(ChatHub hub, AtomicInteger guestCounter) {
        this.hub = hub;
        this.guestCounter = guestCounter;
    }

    /** Runs the session until the peer leaves or disconnects. */
    public void handle(WebSocketConnection conn) {
        String nick = "guest-" + guestCounter.getAndIncrement();
        conn.setNick(nick);
        hub.join(conn, DEFAULT_ROOM);
        conn.send("Welcome to ws-chat! You are " + nick + " in #" + DEFAULT_ROOM
                + ". Type /help for commands.");
        announce(conn, DEFAULT_ROOM, nick + " joined #" + DEFAULT_ROOM);
        try {
            String message;
            while ((message = conn.readMessage()) != null) {
                if (message.startsWith("/")) {
                    if (!handleCommand(conn, message)) {
                        return; // /quit
                    }
                } else if (!message.isBlank()) {
                    String room = hub.roomOf(conn);
                    hub.broadcast(room, conn.displayName() + ": " + message, conn);
                }
            }
        } catch (IOException e) {
            // Peer dropped mid-message; treat as a disconnect.
        } finally {
            String room = hub.roomOf(conn);
            hub.leave(conn);
            if (room != null) {
                announce(conn, room, nick + " left #" + room);
            }
            conn.close(1000, "");
        }
    }

    /** Returns {@code false} when the connection should be closed (/quit). */
    private boolean handleCommand(WebSocketConnection conn, String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        String room = hub.roomOf(conn);
        switch (command) {
            case "/nick": {
                if (arg.isEmpty() || arg.contains(" ")) {
                    conn.send("Usage: /nick <name>  (one word, no spaces)");
                    return true;
                }
                if (hub.memberNames(room).contains(arg)) {
                    conn.send("The name '" + arg + "' is already taken in #" + room + ".");
                    return true;
                }
                String old = conn.displayName();
                conn.setNick(arg);
                announce(conn, room, old + " is now known as " + arg + ".");
                conn.send("You are now " + arg + ".");
                return true;
            }
            case "/join": {
                if (arg.isEmpty() || arg.contains(" ")) {
                    conn.send("Usage: /join <room>  (one word, no spaces)");
                    return true;
                }
                if (arg.equals(room)) {
                    conn.send("You are already in #" + room + ".");
                    return true;
                }
                hub.leave(conn);
                announce(conn, room, conn.displayName() + " left #" + room + ".");
                hub.join(conn, arg);
                conn.send("You joined #" + arg + ".");
                announce(conn, arg, conn.displayName() + " joined #" + arg + ".");
                return true;
            }
            case "/rooms": {
                conn.send("Rooms: " + String.join(", ",
                        hub.roomNames().stream().map(r -> "#" + r).sorted().toList()));
                return true;
            }
            case "/who": {
                conn.send("In #" + room + ": "
                        + String.join(", ", hub.memberNames(room).stream().sorted().toList()));
                return true;
            }
            case "/help": {
                conn.send("Commands: /nick <name>  /join <room>  /rooms  /who  /quit");
                return true;
            }
            case "/quit": {
                conn.send("Goodbye!");
                return false;
            }
            default:
                conn.send("Unknown command '" + command + "'. Type /help.");
                return true;
        }
    }

    private void announce(WebSocketConnection conn, String room, String text) {
        hub.broadcast(room, "* " + text, conn);
    }
}
