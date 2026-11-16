# WebSocket Chat Server — Java

A zero-dependency WebSocket chat server in pure Java, implementing the
RFC 6455 protocol from scratch: the HTTP Upgrade handshake, frame encoding
and decoding, ping/pong, fragmentation and the close handshake. On top of
the protocol it runs a small multi-room chat server with nicknames, plus a
minimal terminal client to talk to it.

No libraries, no frameworks — just `java.net` and the JDK. That is the
point: a WebSocket library is mostly a careful byte-level protocol, and
this one is small enough to read end to end.

## Features

- **RFC 6455 frame codec** — text, binary, close, ping and pong frames;
  7-bit, 16-bit and 64-bit payload lengths; client masking with random keys;
  fragmented-message reassembly; strict validation (reserved bits, unknown
  opcodes, fragmented control frames, oversized control payloads,
  truncated frames all fail loudly).
- **Opening handshake** — parses the HTTP Upgrade request and derives
  `Sec-WebSocket-Accept` as `base64(sha1(key + GUID))`, verified against
  the worked example in the RFC. Non-upgrade requests get a plain `400`.
- **Multi-room chat** — join named rooms, unique nicknames per room,
  join/leave announcements, `/rooms` and `/who` listings.
- **Thread-per-connection server** — one cached-pool thread per client,
  synchronized writes, broadcasts that skip the sender.
- **Terminal client** — handshakes, sends masked frames from stdin, prints
  incoming messages, answers pings. Any WebSocket client works too
  (browser console, `websocat`, Python `websockets`).

## Quick start

```bash
./build.sh            # compiles and builds out/ws-chat.jar
./build.sh test       # compiles and runs the JUnit 5 suite
```

Requires JDK 17+.

```bash
# Terminal 1 — start the server
java -jar out/ws-chat.jar server --port 8080

# Terminal 2 and 3 — join as two users
java -jar out/ws-chat.jar client --port 8080 --nick alice
java -jar out/ws-chat.jar client --port 8080 --nick bob
```

## Chat commands

| Command | What it does |
|---|---|
| `/nick <name>` | Change display name (unique per room) |
| `/join <room>` | Move to another room |
| `/rooms` | List non-empty rooms |
| `/who` | List people in your room |
| `/help` | Show this list |
| `/quit` | Leave |

Anything else is broadcast to everyone in your room.

## Design decisions

- **Frames are parsed by hand, byte by byte.** WebSocket framing is a
  small, well-specified state machine (header byte, length marker, optional
  mask key, payload), so a hand-rolled codec stays honest and testable
  instead of hiding behind a library. The codec is the most-tested part
  of the project.
- **The handshake reader never buffers past the headers.** HTTP headers
  are read one byte at a time from the same `BufferedInputStream` the
  frame loop later uses, so no frame bytes are ever swallowed by a
  `BufferedReader` sitting on top of the socket.
- **Rooms are the only shared state, and it is a `ConcurrentHashMap`.**
  `ChatHub` knows nothing about sockets — it works against a tiny `Peer`
  interface (`send` + `displayName`), which is what makes it unit-testable
  with a fake peer and keeps the protocol layer separate from the chat
  layer.
- **The server is strict about protocol errors.** A binary frame gets a
  `1003` close (chat is text-only); a bad frame kills that connection,
  not the server. Other clients are never affected by one bad peer.

## Project layout

```
src/main/java/com/subhanmalik/ws/
  Frame.java                 RFC 6455 frame codec
  WebSocketConnection.java   handshake + one connection's frame loop
  ChatHub.java               rooms, membership, broadcast
  ChatSession.java           one client's session: commands + broadcast
  ChatServer.java            TCP accept loop, one thread per connection
  ChatClient.java            minimal terminal client
  Main.java                  server / client CLI
src/test/java/com/subhanmalik/ws/
  FrameTest.java             codec round-trips + protocol violations
  HandshakeTest.java         RFC accept-key example + header parsing
  ChatHubTest.java           rooms and broadcast with fake peers
  ChatSessionIntegrationTest.java  full sessions over in-memory pipes
```

## Tests

```bash
./build.sh test
```

27 tests: codec round-trips at all three length encodings, masked and
unmasked frames, every protocol-violation branch, the RFC handshake
example, room/broadcast logic, and end-to-end session tests (broadcast,
`/nick` + `/join`, ping/pong, close handshake, non-upgrade rejection).
The session tests drive a real `ChatSession` over in-memory pipes instead
of loopback sockets, so the suite runs anywhere — including sandboxes
that block TCP — while still exercising the full handshake-to-close path.
The thin TCP accept loop in `ChatServer` is the only untested code.
