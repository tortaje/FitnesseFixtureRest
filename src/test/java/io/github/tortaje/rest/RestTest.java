package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.config.ConfigLoader;
import io.github.tortaje.rest.testsupport.LogCapture;
import io.github.tortaje.rest.testsupport.StubServer;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestTest {

    private StubServer stub;

    @BeforeEach
    void start() {
        stub = StubServer.http();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private Rest rest(String env, String... extra) {
        Properties props = new Properties();
        props.setProperty("rest.base_url", stub.url());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            props.setProperty(extra[i], extra[i + 1]);
        }
        Map<String, String> envVars = new HashMap<>();
        ConfigLoader loader = new ConfigLoader(envVars::get, props, getClass().getClassLoader(), "config-test/");
        return new Rest(env, loader, d -> { }, Clock.systemUTC());
    }

    private Rest restNoRetry(io.github.tortaje.rest.exec.Sleeper sleeper) {
        Properties props = new Properties();
        props.setProperty("rest.base_url", stub.url());
        ConfigLoader loader = new ConfigLoader(new HashMap<String, String>()::get, props,
                getClass().getClassLoader(), "none/");
        return new Rest(null, loader, sleeper, Clock.systemUTC());
    }

    private Rest rest() {
        return rest(null);
    }

    @Test
    void postThenGetWithExtractedId() {
        stub.reply("POST", "/payments", 201, "{\"id\":\"p-1\"}");
        stub.reply("GET", "/payments/p-1", 200, "{\"status\":\"ACCEPTED\"}");
        Rest r = rest();
        r.body("{\"amount\":1}");
        r.post("/payments");
        assertEquals(201, r.status());
        String id = r.json("$.id");
        assertEquals("p-1", id);
        r.get("/payments/" + id);
        assertEquals("ACCEPTED", r.json("$.status"));
    }

    @Test
    void bodyAndQueryClearedHeadersKept() {
        stub.reply("POST", "/x", 200, "");
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.header("X-A", "1");
        r.body("payload");
        r.queryParam("q", "v");
        r.post("/x");
        r.get("/x");
        StubServer.Recorded post = stub.requests("/x").get(0);
        StubServer.Recorded get = stub.requests("/x").get(1);
        assertEquals("payload", post.body());
        assertEquals("q=v", post.query());
        assertEquals("1", get.headers().get("x-a"));
        assertEquals("", get.body());
        assertEquals(null, get.query());
    }

    @Test
    void resetClearsHeaders() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.header("X-A", "1");
        r.reset();
        r.get("/x");
        assertFalse(stub.requests("/x").get(0).headers().containsKey("x-a"));
    }

    @Test
    void defaultHeadersSentAndOverridden() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.header("x-app", "wiki");
        r.get("/x");
        assertEquals(java.util.List.of("wiki"), stub.requests("/x").get(0).headerValues("x-app"));
    }

    @Test
    void defaultHeaderFromConfigIsSent() {
        stub.reply("GET", "/x", 200, "");
        rest().get("/x");
        assertEquals("fitnesse", stub.requests("/x").get(0).headers().get("x-app"));
    }

    @Test
    void defaultHeaderSecretReferenceIsResolvedAndMasked() {
        stub.reply("GET", "/x", 200, "");
        SecretRegistry.put("hdr", "HDR-SECRET-VALUE");
        try (LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            rest(null, "rest.default_headers.X-Token", "secret:hdr").get("/x");
            assertEquals("HDR-SECRET-VALUE", stub.requests("/x").get(0).headers().get("x-token"));
            assertTrue(logs.text().contains("X-Token=****"), logs.text());
            assertFalse(logs.text().contains("HDR-SECRET-VALUE"), logs.text());
        } finally {
            SecretRegistry.clear();
        }
    }

    @Test
    void clientCredentialsFromConfigOneTokenPerInstance() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"tok\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "");
        Rest r = rest(null, "rest.token_url", stub.url() + "/token", "rest.client_id", "id",
                "rest.client_secret", "s");
        r.get("/x");
        r.get("/x");
        r.get("/x");
        assertEquals(1, stub.requests("/token").size());
        assertEquals("Bearer tok", stub.requests("/x").get(2).headers().get("authorization"));
        Rest other = rest(null, "rest.token_url", stub.url() + "/token", "rest.client_id", "id",
                "rest.client_secret", "s");
        other.get("/x");
        assertEquals(2, stub.requests("/token").size());
    }

    @Test
    void tokenUrlRowDropsCachedToken() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"tok\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "");
        Rest r = rest(null, "rest.token_url", stub.url() + "/token", "rest.client_id", "id",
                "rest.client_secret", "s");
        r.get("/x");
        r.tokenUrl(stub.url() + "/token");
        r.get("/x");
        assertEquals(2, stub.requests("/token").size());
    }

    @Test
    void clientCredentialsRowUsesTokenUrlAndScope() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"tok\"}");
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.tokenUrl(stub.url() + "/token");
        r.scope("read");
        r.clientCredentials("id", "sec");
        r.get("/x");
        assertTrue(stub.requests("/token").get(0).body().contains("scope=read"));
        assertEquals("Bearer tok", stub.requests("/x").get(0).headers().get("authorization"));
    }

    @Test
    void retriesFromWikiRows() {
        stub.reply("GET", "/x", 503, "");
        stub.reply("GET", "/x", 200, "ok");
        Rest none = restNoRetry(d -> { });
        none.maxRetries("0");
        none.get("/x");
        assertEquals(503, none.status());

        stub.reply("GET", "/y", 503, "");
        stub.reply("GET", "/y", 200, "ok");
        java.util.List<java.time.Duration> slept = new java.util.ArrayList<>();
        Rest r = restNoRetry(slept::add);
        r.maxRetries("1");
        r.retryDelay("250");
        r.get("/y");
        assertEquals(200, r.status());
        assertEquals(java.util.List.of(java.time.Duration.ofMillis(250)), slept);
    }

    @Test
    void scopeRowDropsCachedToken() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"tok\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "");
        Rest r = rest(null, "rest.token_url", stub.url() + "/token", "rest.client_id", "id",
                "rest.client_secret", "s");
        r.get("/x");
        r.scope("x");
        r.get("/x");
        assertEquals(2, stub.requests("/token").size());
    }

    @Test
    void missingRegistryVariableIsFixtureException() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.bearerToken("secret:missing");
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> r.get("/x"));
        assertEquals("Registry variable 'missing' missing", e.text());
    }

    @Test
    void staleResponseClearedAfterFailure() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.get("/x");
        assertEquals(200, r.status());
        r.baseUrl("http://127.0.0.1:1");
        r.timeout("2");
        r.maxRetries("0");
        assertThrows(RestFixtureException.class, () -> r.get("/x"));
        RestFixtureException e = assertThrows(RestFixtureException.class, r::status);
        assertEquals("No response yet: send a request first", e.text());
    }

    @Test
    void responseHeaderAndContains() {
        stub.reply("GET", "/x", 200, "hello world", "X-Id", "42");
        Rest r = rest();
        r.get("/x");
        assertEquals("42", r.responseHeader("x-id"));
        assertTrue(r.responseContains("world"));
        assertFalse(r.responseContains("nope"));
        assertEquals("hello world", r.body());
    }

    @Test
    void invalidTimeoutRow() {
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> rest().timeout("0"));
        assertEquals("Invalid value '0' for timeout_seconds", e.text());
    }

    @Test
    void bodyFromFileMissing() {
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> rest().bodyFromFile("nope.xml"));
        assertEquals("Body file 'nope.xml' not found", e.text());
    }

    @Test
    void missingKeystoreAndTruststore() {
        Rest r = rest();
        assertEquals("Keystore 'nope.p12' not found",
                assertThrows(RestFixtureException.class, () -> r.keystore("nope.p12", "x")).text());
        assertEquals("Truststore 'nope.p12' not found",
                assertThrows(RestFixtureException.class, () -> r.truststore("nope.p12", "x")).text());
    }

    @Test
    void xpathOnXmlResponse() {
        stub.reply("GET", "/x", 200, "<a><b>7</b></a>");
        Rest r = rest();
        r.get("/x");
        assertEquals("7", r.xpath("/a/b"));
    }

    @Test
    void apiKeyAndBasicAuthRows() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.apiKeyHeader("X-Key", "k1");
        r.get("/x");
        assertEquals("k1", stub.requests("/x").get(0).headers().get("x-key"));
        r.basicAuth("u", "p");
        r.get("/x");
        assertEquals("Basic dTpw", stub.requests("/x").get(1).headers().get("authorization"));
        r.noAuth();
        r.get("/x");
        assertFalse(stub.requests("/x").get(2).headers().containsKey("authorization"));
    }

    private static String link(String url) {
        return "<a href=\"" + url + "\">" + url + "</a>";
    }

    @Test
    void autoLinkedBaseUrlWithRelativeEndpoint() {
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest(null);
        r.baseUrl(link(stub.url()));
        r.get("/x");
        assertEquals(200, r.status());
    }

    @Test
    void autoLinkedAbsoluteEndpointWithAttributes() {
        stub.reply("GET", "/abs", 200, "ok");
        Rest r = rest(null);
        r.get(" <A rel=\"nofollow\" HREF='" + stub.url() + "/abs' target=\"_blank\">" + stub.url() + "/abs</A> ");
        assertEquals(200, r.status());
    }

    @Test
    void autoLinkedTokenUrlIsStripped() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"A\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest(null);
        r.tokenUrl(link(stub.url() + "/token"));
        r.clientCredentials("id", "s");
        r.get("/x");
        assertEquals(200, r.status());
        assertEquals(1, stub.requests("/token").size());
    }

    @Test
    void cellUrlLeavesOtherValuesUnchanged() {
        assertEquals("http://h/x", Rest.cellUrl("http://h/x"));
        assertEquals("/payments/p-1", Rest.cellUrl("  /payments/p-1 "));
        assertEquals("a <a b", Rest.cellUrl("a <a b"));
        assertEquals("<a>not a link</a>", Rest.cellUrl("<a>not a link</a>"));
        assertEquals("<a href=\"u\">other</a>", Rest.cellUrl("<a href=\"u\">other</a>"));
        assertEquals(null, Rest.cellUrl(null));
        assertEquals("http://h/x", Rest.cellUrl("<a href=\"http://h/x\">http://h/x</a>"));
    }

    @Test
    void cellUrlDecodesHtmlEntitiesOfAutoLink() {
        assertEquals("http://h/x?a=1&b=2",
                Rest.cellUrl("<a href=\"http://h/x?a=1&amp;b=2\">http://h/x?a=1&amp;b=2</a>"));
        assertEquals("http://h/x?q=<v>&r=&lt;",
                Rest.cellUrl("<a href=\"http://h/x?q=&lt;v&gt;&amp;r=&amp;lt;\">http://h/x?q=&lt;v&gt;&amp;r=&amp;lt;</a>"));
        assertEquals("http://h/x?a=1&amp;b=2", Rest.cellUrl("http://h/x?a=1&amp;b=2"));
    }

    @Test
    void credentialsAreNotSentToAnotherOrigin() {
        try (StubServer other = StubServer.http(); LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            other.reply("GET", "/x", 200, "ok");
            Rest r = rest();
            r.bearerToken("T-123");
            r.get(other.url() + "/x");
            assertEquals(200, r.status());
            assertTrue(other.requests("/x").get(0).headerValues("authorization").isEmpty());
            assertTrue(logs.text().contains("Credentials not sent to " + other.url() + ": not the base url origin"));
        }
    }

    @Test
    void credentialsAreSentToAnAbsoluteEndpointOfTheBaseOrigin() {
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest();
        r.bearerToken("T-123");
        r.get(stub.url() + "/x");
        assertEquals("Bearer T-123", stub.requests("/x").get(0).headerValues("authorization").get(0));
    }
}
