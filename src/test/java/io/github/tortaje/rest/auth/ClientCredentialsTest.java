package io.github.tortaje.rest.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.testsupport.FakeTransport;
import io.github.tortaje.rest.testsupport.FakeTransport.Reply;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientCredentialsTest {

    private static final String URL = "https://idp.example.com/oauth/token";

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    @AfterEach
    void clean() {
        SecretRegistry.clear();
    }

    private ClientCredentials auth(FakeTransport t, String scope) {
        return new ClientCredentials(URL, SecretValue.of("my-id"), SecretValue.of("my-secret"), scope, () -> t, clock);
    }

    private static HttpCall call() {
        return new HttpCall("GET", URI.create("http://host/path"), Map.of(), null);
    }

    private static Reply token(String name, String extra) {
        return new Reply(200, "{\"access_token\":\"" + name + "\"" + extra + "}");
    }

    private static String failure(ClientCredentials a) {
        return assertThrows(RestFixtureException.class, () -> a.apply(call())).getMessage();
    }

    @Test
    void fetchesOnceAndReusesToken() {
        FakeTransport t = new FakeTransport(token("A1", ",\"expires_in\":3600"));
        ClientCredentials a = auth(t, null);
        assertEquals("Bearer A1", a.apply(call()).headers().get("Authorization"));
        assertEquals("Bearer A1", a.apply(call()).headers().get("Authorization"));
        assertEquals(1, t.calls());
        assertEquals(java.util.List.of(0), t.resendCounts);
    }

    @Test
    void sendsFormWithEncodedSecret() {
        SecretRegistry.put("cs", "p&ss w");
        FakeTransport t = new FakeTransport(token("A1", ""));
        ClientCredentials a = new ClientCredentials(URL, SecretValue.of("my-id"), SecretValue.of("secret:cs"),
            "payments.write", () -> t, clock);
        a.apply(call());
        String body = t.bodies.get(0);
        assertTrue(body.startsWith("grant_type=client_credentials&client_id=my-id&"), body);
        assertTrue(body.contains("client_secret=p%26ss+w"), body);
        assertTrue(body.contains("scope=payments.write"), body);
        assertEquals(URI.create(URL), t.uris.get(0));
    }

    @Test
    void renewsThirtySecondsBeforeExpiry() {
        FakeTransport t = new FakeTransport(token("A1", ",\"expires_in\":3600"), token("A2", ",\"expires_in\":3600"));
        ClientCredentials a = auth(t, null);
        a.apply(call());
        clock.advance(3569);
        assertEquals("Bearer A1", a.apply(call()).headers().get("Authorization"));
        assertEquals(1, t.calls());
        clock.advance(2);
        assertEquals("Bearer A2", a.apply(call()).headers().get("Authorization"));
        assertEquals(2, t.calls());
    }

    @Test
    void expiresInAsString() {
        FakeTransport t = new FakeTransport(token("A1", ",\"expires_in\":\"3600\""), token("A2", ""));
        ClientCredentials a = auth(t, null);
        a.apply(call());
        clock.advance(3569);
        a.apply(call());
        assertEquals(1, t.calls());
        clock.advance(2);
        assertEquals("Bearer A2", a.apply(call()).headers().get("Authorization"));
    }

    @Test
    void refreshForcesNewToken() {
        FakeTransport t = new FakeTransport(token("A1", ""), token("A2", ""));
        ClientCredentials a = auth(t, null);
        assertTrue(a.canRefresh());
        a.apply(call());
        a.refresh();
        assertEquals("Bearer A2", a.apply(call()).headers().get("Authorization"));
        assertEquals(2, t.calls());
    }

    @Test
    void idpErrorWithOauthError() {
        FakeTransport t = new FakeTransport(new Reply(400, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret xyz\"}"));
        String text = failure(auth(t, null));
        assertTrue(text.contains("Token request to idp.example.com failed: HTTP 400, error=invalid_client"), text);
        assertFalse(text.contains("xyz"), text);
        assertFalse(text.contains("my-secret"), text);
    }

    @Test
    void idpHtmlErrorPage() {
        FakeTransport t = new FakeTransport(new Reply(502, "<html><body>Bad gateway</body></html>"));
        String text = failure(auth(t, null));
        assertTrue(text.contains("Token request to idp.example.com failed: HTTP 502"), text);
        assertFalse(text.contains("error="), text);
        assertFalse(text.contains("html"), text);
    }

    @Test
    void missingAccessToken() {
        FakeTransport t = new FakeTransport(new Reply(200, "{\"token_type\":\"bearer\"}"));
        assertTrue(failure(auth(t, null)).contains("Token request to idp.example.com failed: no access_token in response"));
    }

    @Test
    void missingTokenUrl() {
        ClientCredentials a = new ClientCredentials(null, SecretValue.of("i"), SecretValue.of("s"), null,
            () -> new FakeTransport(), clock);
        assertTrue(failure(a).contains("No token url: set it with |token url| or token_url"));
    }

    @Test
    void tokenCallIsNotBusiness() {
        FakeTransport t = new FakeTransport(token("A1", ""));
        auth(t, null).apply(call());
        assertEquals(java.util.List.of(false), t.business);
    }

    @Test
    void nonStringErrorIsOmitted() {
        String expected = "Token request to idp.example.com failed: HTTP 400";
        for (String body : new String[] {"{\"error\":400}", "{\"error\":true}", "{\"error\":null}", "{}",
            "{\"error\":\"\"}", "{\"error\":{\"detail\":\"client secret abc\"}}"}) {
            String text = failure(auth(new FakeTransport(new Reply(400, body)), null));
            assertTrue(text.endsWith(expected + ">>") && !text.contains("error="), body + " -> " + text);
            assertFalse(text.contains("abc"), text);
        }
    }

    @Test
    void nonStringAccessTokenIsRejected() {
        FakeTransport t = new FakeTransport(new Reply(200, "{\"access_token\":12345}"));
        assertTrue(failure(auth(t, null)).contains("no access_token in response"));
    }

    @Test
    void absurdExpiresInIsIgnored() {
        FakeTransport t = new FakeTransport(token("A1", ",\"expires_in\":1e300"), token("A2", ""));
        ClientCredentials a = auth(t, null);
        a.apply(call());
        clock.advance(1_000_000);
        assertEquals("Bearer A1", a.apply(call()).headers().get("Authorization"));
        assertEquals(1, t.calls());
    }

    @Test
    void transportFailureIsReported() {
        FakeTransport t = new FakeTransport(new java.net.ConnectException("refused"));
        assertTrue(failure(auth(t, null)).contains("Token request to idp.example.com failed: connection refused"));
    }
}
