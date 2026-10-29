package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.testsupport.LogCapture;
import io.github.tortaje.rest.testsupport.StubServer;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SecretLeakTest {

    private static final String MARK = "S3CR3T-MARK-42";

    enum Mode { BASIC_USER, BASIC_PASSWORD, BEARER, API_KEY_HEADER, API_KEY_PARAM, CC_ID, CC_SECRET }

    enum Source { REGISTRY, LITERAL }

    enum Outcome { OK_200, UNAUTHORIZED_401, CLOSED_PORT }

    private StubServer stub;

    @BeforeEach
    void start() {
        stub = StubServer.http();
        SecretRegistry.put("leak", MARK);
    }

    @AfterEach
    void stop() {
        stub.close();
        SecretRegistry.clear();
    }

    static Stream<Arguments> combinations() {
        List<Arguments> all = new ArrayList<>();
        for (Mode m : Mode.values()) {
            for (Source s : Source.values()) {
                for (Outcome o : Outcome.values()) {
                    all.add(Arguments.of(m, s, o));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("combinations")
    void noMarkerAnywhere(Mode mode, Source source, Outcome outcome) throws Exception {
        String base = stub.url();
        if (outcome == Outcome.CLOSED_PORT) {
            try (ServerSocket s = new ServerSocket(0)) {
                base = "http://127.0.0.1:" + s.getLocalPort();
            }
        }
        if (outcome == Outcome.OK_200) {
            stub.reply("GET", "/data", 200, "{\"ok\":true}", "X-Plain", "value");
            stub.reply("POST", "/token", 200, "{\"access_token\":\"tok\",\"expires_in\":3600}");
        } else if (outcome == Outcome.UNAUTHORIZED_401) {
            stub.reply("GET", "/data", 401, "denied");
            stub.reply("POST", "/token", 200, "{\"access_token\":\"A1\",\"expires_in\":3600}");
            stub.reply("POST", "/token", 400, "{\"error\":\"x\"}");
        }
        String secret = source == Source.REGISTRY ? "secret:leak" : MARK;

        List<String> seen = new ArrayList<>();
        boolean clientCredentials = mode == Mode.CC_ID || mode == Mode.CC_SECRET;
        RuntimeException thrown = null;
        boolean headersLogged = false;
        try (LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            Rest rest = new Rest();
            rest.baseUrl(base);
            rest.tokenUrl(base + "/token");
            switch (mode) {
                case BASIC_USER -> rest.basicAuth(secret, "pw");
                case BASIC_PASSWORD -> rest.basicAuth("user", secret);
                case BEARER -> rest.bearerToken(secret);
                case API_KEY_HEADER -> rest.apiKeyHeader("X-Api-Key", secret);
                case API_KEY_PARAM -> rest.apiKeyParam("api_key", secret);
                case CC_ID -> rest.clientCredentials(secret, "other");
                case CC_SECRET -> rest.clientCredentials("other", secret);
            }
            try {
                rest.get("/data");
                seen.add(String.valueOf(rest.status()));
                seen.add(rest.body());
                seen.add(rest.responseHeader("X-Plain"));
            } catch (RuntimeException e) {
                thrown = e;
                for (Throwable t = e; t != null; t = t.getCause()) {
                    seen.add(t.getMessage());
                    seen.add(t.toString());
                }
            }
            SimpleFormatter f = new SimpleFormatter();
            for (LogRecord r : logs.records()) {
                seen.add(f.formatMessage(r));
                seen.add(r.getMessage());
                headersLogged |= f.formatMessage(r).contains("headers");
                if (r.getParameters() != null) {
                    for (Object p : r.getParameters()) {
                        seen.add(String.valueOf(p));
                    }
                }
                if (r.getThrown() != null) {
                    for (Throwable t = r.getThrown(); t != null; t = t.getCause()) {
                        seen.add(t.getMessage());
                    }
                }
            }
        }

        for (String s : seen) {
            assertFalse(s != null && s.contains(MARK), "marker leaked in: " + s);
        }
        if (outcome == Outcome.CLOSED_PORT || (outcome == Outcome.UNAUTHORIZED_401 && clientCredentials)) {
            assertTrue(thrown instanceof RestFixtureException, "expected RestFixtureException but got " + thrown);
        }
        if (outcome == Outcome.UNAUTHORIZED_401 && clientCredentials) {
            assertEquals(2, stub.requests("/token").size(), "token fetch + refresh");
            assertEquals(1, stub.requests("/data").size());
        }
        if (outcome == Outcome.OK_200) {
            assertTrue(headersLogged, "log capture saw no headers record");
            assertTrue(markerReachedStub(mode), "marker never sent to the stub: test would be vacuous");
        }
    }

    @org.junit.jupiter.api.Test
    void unparseableHostWithApiKeyParamDoesNotLeak() {
        List<String> seen = new ArrayList<>();
        RuntimeException thrown = null;
        try (LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            Rest rest = new Rest();
            rest.baseUrl("http://my_svc:8080");
            rest.apiKeyParam("api_key", "secret:leak");
            try {
                rest.get("/data");
            } catch (RuntimeException e) {
                thrown = e;
                for (Throwable t = e; t != null; t = t.getCause()) {
                    seen.add(t.getMessage());
                    seen.add(t.toString());
                }
            }
            seen.add(logs.text());
        }
        assertTrue(thrown instanceof RestFixtureException, "expected RestFixtureException but got " + thrown);
        assertEquals("Invalid URL for endpoint '/data'", ((RestFixtureException) thrown).text());
        for (String s : seen) {
            assertFalse(s != null && s.contains(MARK), "marker leaked in: " + s);
        }
    }

    @org.junit.jupiter.api.Test
    void wikiHeaderFromRegistryIsMaskedInFineLog() {
        stub.reply("GET", "/data", 200, "ok");
        String logged;
        try (LogCapture logs = LogCapture.on("io.github.tortaje.rest")) {
            Rest rest = new Rest();
            rest.baseUrl(stub.url());
            rest.header("X-Vault-Token", "secret:leak");
            rest.header("X-Plain", "visible");
            rest.get("/data");
            logged = logs.text();
        }
        assertEquals(MARK, stub.requests("/data").get(0).headers().get("x-vault-token"));
        assertTrue(logged.contains("X-Vault-Token=****"), logged);
        assertTrue(logged.contains("X-Plain=visible"), logged);
        assertFalse(logged.contains(MARK), logged);
    }

    private boolean markerReachedStub(Mode mode) {
        return switch (mode) {
            case BASIC_USER, BASIC_PASSWORD -> {
                String auth = stub.requests("/data").get(0).headers().get("authorization");
                yield auth != null && new String(Base64.getDecoder().decode(auth.substring("Basic ".length())),
                        StandardCharsets.UTF_8).contains(MARK);
            }
            case BEARER -> ("Bearer " + MARK).equals(stub.requests("/data").get(0).headers().get("authorization"));
            case API_KEY_HEADER -> MARK.equals(stub.requests("/data").get(0).headers().get("x-api-key"));
            case API_KEY_PARAM -> {
                String q = stub.requests("/data").get(0).query();
                yield q != null && q.contains(MARK);
            }
            case CC_ID, CC_SECRET -> stub.requests("/token").get(0).body().contains(MARK);
        };
    }
}
