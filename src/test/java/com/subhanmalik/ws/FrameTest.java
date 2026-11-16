package com.subhanmalik.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FrameTest {

    private static Frame roundTrip(Frame frame, boolean mask) throws IOException {
        byte[] bytes = frame.encode(mask);
        Frame decoded = Frame.decode(new ByteArrayInputStream(bytes));
        assertEquals(frame.fin(), decoded.fin());
        assertEquals(frame.opcode(), decoded.opcode());
        assertArrayEquals(frame.payload(), decoded.payload());
        return decoded;
    }

    @Test
    void shortTextFrameRoundTrip() throws IOException {
        roundTrip(new Frame(true, Frame.TEXT, "hello".getBytes(StandardCharsets.UTF_8)), false);
        roundTrip(new Frame(true, Frame.TEXT, "hello".getBytes(StandardCharsets.UTF_8)), true);
    }

    @Test
    void emptyPayloadRoundTrip() throws IOException {
        roundTrip(new Frame(true, Frame.TEXT, new byte[0]), false);
        roundTrip(new Frame(true, Frame.PING, new byte[0]), true);
    }

    @Test
    void mediumPayloadUses16BitLength() throws IOException {
        byte[] payload = new byte[300];
        Arrays.fill(payload, (byte) 'a');
        byte[] encoded = new Frame(true, Frame.BINARY, payload).encode(false);
        assertEquals(126, encoded[1] & 0x7F); // 126 marker
        assertEquals(300, ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF));
        roundTrip(new Frame(true, Frame.BINARY, payload), true);
    }

    @Test
    void largePayloadUses64BitLength() throws IOException {
        byte[] payload = new byte[70_000];
        Arrays.fill(payload, (byte) 0x5A);
        byte[] encoded = new Frame(true, Frame.BINARY, payload).encode(false);
        assertEquals(127, encoded[1] & 0x7F); // 127 marker
        roundTrip(new Frame(true, Frame.BINARY, payload), true);
    }

    @Test
    void maskedFrameIsUnmaskedOnDecode() throws IOException {
        // Fixed mask key so the test is deterministic.
        byte[] payload = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] maskKey = {0x11, 0x22, 0x33, 0x44};
        byte[] encoded = Frame.encode(Frame.TEXT, payload, true, maskKey);
        assertTrue((encoded[1] & 0x80) != 0); // mask bit set
        Frame decoded = Frame.decode(new ByteArrayInputStream(encoded));
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void controlFrameOpcodesSurvive() throws IOException {
        roundTrip(new Frame(true, Frame.PING, "ping".getBytes(StandardCharsets.UTF_8)), false);
        roundTrip(new Frame(true, Frame.PONG, "pong".getBytes(StandardCharsets.UTF_8)), false);
        roundTrip(new Frame(true, Frame.CLOSE, new byte[]{0x03, (byte) 0xE8}), false);
    }

    @Test
    void fragmentedFramesDecodeIndividually() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new Frame(false, Frame.TEXT, "hel".getBytes(StandardCharsets.UTF_8)).encode(false));
        out.write(new Frame(true, Frame.CONTINUATION, "lo".getBytes(StandardCharsets.UTF_8)).encode(false));
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Frame first = Frame.decode(in);
        Frame second = Frame.decode(in);
        assertFalse(first.fin());
        assertEquals(Frame.TEXT, first.opcode());
        assertTrue(second.fin());
        assertEquals(Frame.CONTINUATION, second.opcode());
        assertEquals("lo", new String(second.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void cleanCloseReturnsNull() throws IOException {
        assertNull(Frame.decode(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void reservedBitsRejected() {
        // FIN + RSV1 set, opcode TEXT.
        byte[] bad = {(byte) 0xC1, 0x00};
        assertThrows(IOException.class, () -> Frame.decode(new ByteArrayInputStream(bad)));
    }

    @Test
    void unknownOpcodeRejected() {
        // FIN set, opcode 3 (reserved data opcode).
        byte[] bad = {(byte) 0x83, 0x00};
        assertThrows(IOException.class, () -> Frame.decode(new ByteArrayInputStream(bad)));
    }

    @Test
    void fragmentedControlFrameRejected() {
        // FIN clear, opcode PING.
        byte[] bad = {0x09, 0x00};
        assertThrows(IOException.class, () -> Frame.decode(new ByteArrayInputStream(bad)));
    }

    @Test
    void oversizedControlFrameRejected() {
        // FIN set, opcode PING, length marker 126 with length 200.
        byte[] bad = {(byte) 0x89, 126, 0x00, (byte) 0xC8};
        assertThrows(IOException.class, () -> Frame.decode(new ByteArrayInputStream(bad)));
    }

    @Test
    void truncatedPayloadRejected() {
        // Claims 10 bytes, delivers 3.
        byte[] bad = {(byte) 0x81, 0x0A, 'a', 'b', 'c'};
        assertThrows(IOException.class, () -> Frame.decode(new ByteArrayInputStream(bad)));
    }
}
