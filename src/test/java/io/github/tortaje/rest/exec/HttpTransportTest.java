package io.github.tortaje.rest.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import io.github.tortaje.rest.testsupport.LogCapture;
import io.github.tortaje.rest.testsupport.StubServer;
import io.github.tortaje.rest.testsupport.StubServer.Recorded;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

    @Test
    void sendsAndReturnsResponse() throws Exception {
        try (StubServer s = StubServer.http()) {
            s.reply("POST", "/x", 201, "{\"id\":1}", "Content-Type", "application/json");
            HttpCall call = new HttpCall("POST", URI.create(s.url() + "/x?a=1"), Map.of("X-A", "1"), "{}");
            RestResponse r = transport().send(call, 0, true);
            assertEquals(201, r.status());
            assertEquals("{\"id\":1}", r.body());
            assertEquals("application/json", r.header("content-type"));
            Recorded rec = s.requests("/x").get(0);
            assertEquals("a=1", rec.query());
            assertEquals("{}", rec.body());
            assertEquals("1", rec.headers().get("x-a"));
        }
    }

    @Test
    void restrictedHeaderGivesFixtureMessage() {
        HttpCall call = new HttpCall("GET", URI.create("http://127.0.0.1:1/x"), Map.of("Host", "x"), null);
        assertEquals("Header 'Host' cannot be set",
            assertThrows(RestFixtureException.class, () -> transport().send(call, 0, true)).text());
    }

    @Test
    void unparseableHostGivesFixtureMessageWithoutQueryOrCause() {
        HttpCall call = new HttpCall("GET", URI.create("http://my_svc:8080/x?api_key=K-SECRET"), Map.of(), null);
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> transport().send(call, 0, true));
        assertEquals("GET /x: invalid URL", e.text());
        assertNull(e.getCause());
    }

    @Test
    void logsSafeUrlAndRedactedHeaders() throws Exception {
        try (StubServer s = StubServer.http(); LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            s.reply("GET", "/x", 200, "");
            transport().send(new HttpCall("GET", URI.create(s.url() + "/x?api_key=K"),
                Map.of("Authorization", "Bearer T"), null), 0, true);
            String all = logs.text();
            assertTrue(all.contains("GET " + s.url() + "/x -> 200"), all);
            assertTrue(all.contains("headers {Authorization=****}"), all);
            assertFalse(all.contains("api_key"));
            assertFalse(all.contains("Bearer T"));
        }
    }

    @Test
    void bodyIsLoggedOnlyForBusinessCalls() throws Exception {
        try (StubServer s = StubServer.http(); LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            s.reply("POST", "/x", 200, "");
            HttpCall call = new HttpCall("POST", URI.create(s.url() + "/x"), Map.of(), "BODY-MARK");
            transport().send(call, 0, false);
            assertFalse(logs.text().contains("BODY-MARK"), logs.text());
            transport().send(call, 0, true);
            assertTrue(logs.text().contains("body BODY-MARK"), logs.text());
        }
    }

    private HttpTransport transport() {
        return new HttpTransport(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            Duration.ofSeconds(5), Set.of());
    }

    @Test
    void bodyAboveTheLimitIsTruncatedAndFlagged() throws Exception {
        try (StubServer s = StubServer.http(); LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            s.reply("GET", "/big", 200, "0123456789ABCDEF");
            HttpTransport t = new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), Set.of(), 10);
            RestResponse r = t.send(new HttpCall("GET", URI.create(s.url() + "/big"), Map.of(), null), 0, true);
            assertEquals(200, r.status());
            assertEquals("0123456789", r.body());
            assertTrue(r.truncated());
            assertTrue(logs.text().contains("body truncated at 10 bytes"), logs.text());
        }
    }

    @Test
    void bodyAtTheLimitIsComplete() throws Exception {
        try (StubServer s = StubServer.http()) {
            s.reply("GET", "/ok", 200, "0123456789");
            HttpTransport t = new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), Set.of(), 10);
            RestResponse r = t.send(new HttpCall("GET", URI.create(s.url() + "/ok"), Map.of(), null), 0, true);
            assertEquals("0123456789", r.body());
            assertFalse(r.truncated());
        }
    }

    @Test
    void bodyIsDecodedWithTheResponseCharset() throws Exception {
        try (StubServer s = StubServer.http()) {
            s.replyBytes("GET", "/latin", 200, "caf\u00e9".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                    "Content-Type", "text/plain; charset=ISO-8859-1");
            RestResponse r = transport().send(new HttpCall("GET", URI.create(s.url() + "/latin"), Map.of(), null), 0, true);
            assertEquals("caf\u00e9", r.body());
        }
    }
}
