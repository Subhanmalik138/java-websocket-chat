package com.subhanmalik.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

/**
 * RFC 6455 frame codec.
 *
 * <p>Wire format: {@code FIN(1) RSV(3) opcode(4) | MASK(1) len(7) |
 * [ext len] | [mask key] | payload}. Lengths are 7-bit inline, 16-bit
 * (126 marker) or 64-bit (127 marker). Client frames must be masked;
 * server frames must not be. Control frames (close/ping/pong) are never
 * fragmented and carry at most 125 bytes of payload.
 */
public final class Frame {

    public static final int CONTINUATION = 0x0;
    public static final int TEXT = 0x1;
    public static final int BINARY = 0x2;
    public static final int CLOSE = 0x8;
    public static final int PING = 0x9;
    public static final int PONG = 0xA;

    private static final Random RANDOM = new Random();

    private final boolean fin;
    private final int opcode;
    private final byte[] payload;

    public Frame(boolean fin, int opcode, byte[] payload) {
        this.fin = fin;
        this.opcode = opcode;
        this.payload = payload.clone();
    }

    public boolean fin() {
        return fin;
    }

    public int opcode() {
        return opcode;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public boolean isControl() {
        return opcode >= 0x8;
    }

    /** Encodes this frame. {@code mask} must be true for client-sent frames. */
    public byte[] encode(boolean mask) {
        byte[] maskKey = null;
        if (mask) {
            maskKey = new byte[4];
            RANDOM.nextBytes(maskKey);
        }
        return encode(opcode, payload, fin, maskKey);
    }

    /** Encodes a frame with an explicit mask key ({@code null} = unmasked). */
    static byte[] encode(int opcode, byte[] payload, boolean fin, byte[] maskKey) {
        int headerLen = 2 + lengthBytes(payload.length) + (maskKey == null ? 0 : 4);
        byte[] out = new byte[headerLen + payload.length];
        out[0] = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));
        out[1] = (byte) (maskKey == null ? 0x00 : 0x80);
        int pos = 2;
        if (payload.length < 126) {
            out[1] |= (byte) payload.length;
        } else if (payload.length <= 0xFFFF) {
            out[1] |= 126;
            out[pos++] = (byte) (payload.length >>> 8);
            out[pos++] = (byte) payload.length;
        } else {
            out[1] |= 127;
            long len = payload.length;
            for (int i = 7; i >= 0; i--) {
                out[pos++] = (byte) (len >>> (8 * i));
            }
        }
        if (maskKey != null) {
            System.arraycopy(maskKey, 0, out, pos, 4);
            pos += 4;
            for (int i = 0; i < payload.length; i++) {
                out[pos + i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
        } else {
            System.arraycopy(payload, 0, out, pos, payload.length);
        }
        return out;
    }

    private static int lengthBytes(int payloadLength) {
        if (payloadLength < 126) {
            return 0;
        }
        return payloadLength <= 0xFFFF ? 2 : 8;
    }

    /**
     * Reads one frame from the stream.
     *
     * @return the frame, or {@code null} if the peer closed the connection
     *         cleanly before sending a frame header
     * @throws IOException on any protocol violation or truncated frame
     */
    public static Frame decode(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        boolean fin = (b0 & 0x80) != 0;
        if ((b0 & 0x70) != 0) {
            throw new IOException("reserved bits set; extensions are not supported");
        }
        int opcode = b0 & 0x0F;
        if (opcode != CONTINUATION && opcode != TEXT && opcode != BINARY
                && opcode != CLOSE && opcode != PING && opcode != PONG) {
            throw new IOException("unknown opcode: " + opcode);
        }
        int b1 = readByte(in);
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
            if (length < 0) {
                throw new IOException("frame length exceeds 2^63-1");
            }
        }
        if (opcode >= 0x8) {
            if (!fin) {
                throw new IOException("control frame must not be fragmented");
            }
            if (length > 125) {
                throw new IOException("control frame payload exceeds 125 bytes");
            }
        }
        if (length > Integer.MAX_VALUE) {
            throw new IOException("frame payload too large: " + length);
        }
        byte[] maskKey = null;
        if (masked) {
            maskKey = in.readNBytes(4);
            if (maskKey.length != 4) {
                throw new EOFException("truncated mask key");
            }
        }
        byte[] payload = in.readNBytes((int) length);
        if (payload.length != length) {
            throw new EOFException("truncated frame payload");
        }
        if (maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }
        return new Frame(fin, opcode, payload);
    }

    /** Writes one frame to the stream. */
    public static void write(OutputStream out, int opcode, byte[] payload, boolean mask)
            throws IOException {
        out.write(new Frame(true, opcode, payload).encode(mask));
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("unexpected end of stream in frame header");
        }
        return b;
    }
}
