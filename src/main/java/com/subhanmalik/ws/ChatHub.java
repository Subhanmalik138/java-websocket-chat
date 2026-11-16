package com.subhanmalik.ws;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat rooms: a peer belongs to exactly one room at a time, and broadcasts
 * go to every other peer in the same room.
 *
 * <p>Thread-safe — the server drives it from one thread per connection.
 */
public final class ChatHub {

    /** A connected peer that can receive a text message. */
    public interface Peer {
        void send(String text);

        String displayName();
    }

    private final ConcurrentHashMap<String, Set<Peer>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Peer, String> membership = new ConcurrentHashMap<>();

    /** Moves {@code peer} into {@code room}, leaving its previous room. */
    public void join(Peer peer, String room) {
        String previous = membership.put(peer, room);
        if (previous != null) {
            removeFromRoom(peer, previous);
        }
        rooms.computeIfAbsent(room, r -> ConcurrentHashMap.newKeySet()).add(peer);
    }

    /** Removes {@code peer} from whatever room it is in. */
    public void leave(Peer peer) {
        String room = membership.remove(peer);
        if (room != null) {
            removeFromRoom(peer, room);
        }
    }

    private void removeFromRoom(Peer peer, String room) {
        Set<Peer> members = rooms.get(room);
        if (members != null) {
            members.remove(peer);
            if (members.isEmpty()) {
                rooms.remove(room, members);
            }
        }
    }

    /** Sends {@code text} to every peer in {@code room} except {@code sender}. */
    public void broadcast(String room, String text, Peer sender) {
        Set<Peer> members = rooms.get(room);
        if (members == null) {
            return;
        }
        // Copy so a slow peer's send() cannot block concurrent joins/leaves.
        for (Peer peer : new HashSet<>(members)) {
            if (peer != sender) {
                peer.send(text);
            }
        }
    }

    /** The room {@code peer} is currently in, or {@code null}. */
    public String roomOf(Peer peer) {
        return membership.get(peer);
    }

    /** Names of all non-empty rooms. */
    public Set<String> roomNames() {
        return Collections.unmodifiableSet(new HashSet<>(rooms.keySet()));
    }

    /** Display names of the peers currently in {@code room}. */
    public Set<String> memberNames(String room) {
        Set<Peer> members = rooms.get(room);
        if (members == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Peer peer : members) {
            names.add(peer.displayName());
        }
        return Collections.unmodifiableSet(names);
    }
}
