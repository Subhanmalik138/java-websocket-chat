package com.subhanmalik.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatHubTest {

    private static final class FakePeer implements ChatHub.Peer {
        final String name;
        final List<String> received = new ArrayList<>();

        FakePeer(String name) {
            this.name = name;
        }

        @Override
        public void send(String text) {
            received.add(text);
        }

        @Override
        public String displayName() {
            return name;
        }
    }

    @Test
    void broadcastReachesRoommatesButNotSender() {
        ChatHub hub = new ChatHub();
        FakePeer alice = new FakePeer("alice");
        FakePeer bob = new FakePeer("bob");
        FakePeer carol = new FakePeer("carol");
        hub.join(alice, "lobby");
        hub.join(bob, "lobby");
        hub.join(carol, "other");
        hub.broadcast("lobby", "hi", alice);
        assertTrue(alice.received.isEmpty());
        assertEquals(List.of("hi"), bob.received);
        assertTrue(carol.received.isEmpty());
    }

    @Test
    void movingRoomsStopsOldRoomDelivery() {
        ChatHub hub = new ChatHub();
        FakePeer alice = new FakePeer("alice");
        FakePeer bob = new FakePeer("bob");
        hub.join(alice, "lobby");
        hub.join(bob, "lobby");
        hub.join(alice, "dev");
        assertEquals("dev", hub.roomOf(alice));
        hub.broadcast("lobby", "hello lobby", bob);
        assertTrue(alice.received.isEmpty());
        assertTrue(bob.received.isEmpty());
    }

    @Test
    void leaveStopsDeliveryAndEmptiesRoom() {
        ChatHub hub = new ChatHub();
        FakePeer alice = new FakePeer("alice");
        FakePeer bob = new FakePeer("bob");
        hub.join(alice, "lobby");
        hub.join(bob, "lobby");
        hub.leave(alice);
        assertNull(hub.roomOf(alice));
        hub.broadcast("lobby", "hi", bob);
        assertTrue(alice.received.isEmpty());
        assertTrue(bob.received.isEmpty());
        assertTrue(hub.roomNames().contains("lobby")); // bob still there
        hub.leave(bob);
        assertTrue(hub.roomNames().isEmpty()); // empty rooms are dropped
    }

    @Test
    void roomNamesAndMemberNames() {
        ChatHub hub = new ChatHub();
        FakePeer alice = new FakePeer("alice");
        FakePeer bob = new FakePeer("bob");
        hub.join(alice, "lobby");
        hub.join(bob, "dev");
        assertEquals(java.util.Set.of("lobby", "dev"), hub.roomNames());
        assertEquals(java.util.Set.of("alice"), hub.memberNames("lobby"));
        assertEquals(java.util.Set.of("bob"), hub.memberNames("dev"));
        assertTrue(hub.memberNames("missing").isEmpty());
    }
}
