package com.subhanmalik.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandshakeTest {

    @Test
    void rfc6455ExampleAcceptKey() {
        // The worked example from RFC 6455, section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketConnection.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void acceptKeyIsDeterministicAndBase64() {
        String key = "x3JJHMbDL1EzLkh9GBhXDw==";
        assertEquals(WebSocketConnection.computeAcceptKey(key),
                WebSocketConnection.computeAcceptKey(key));
        assertEquals(28, WebSocketConnection.computeAcceptKey(key).length());
    }

    @Test
    void validUpgradeRequestParsesHeaders() throws IOException {
        String request = "GET /chat HTTP/1.1\r\n"
                + "Host: localhost:8080\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        Map<String, String> headers = WebSocketConnection.readHttpRequest(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)));
        assertEquals("websocket", headers.get("upgrade"));
        assertEquals("Upgrade", headers.get("connection"));
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", headers.get("sec-websocket-key"));
    }

    @Test
    void nonGetRequestRejected() {
        String request = "POST /chat HTTP/1.1\r\n\r\n";
        assertThrows(IOException.class, () -> WebSocketConnection.readHttpRequest(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void truncatedHeadersRejected() {
        String request = "GET /chat HTTP/1.1\r\nHost: localhost\r\n";
        assertThrows(IOException.class, () -> WebSocketConnection.readHttpRequest(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII))));
    }
}
