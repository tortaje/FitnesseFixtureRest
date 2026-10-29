package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.otel.OtelFixtures;
import io.github.tortaje.rest.testsupport.LogCapture;
import io.github.tortaje.rest.testsupport.StubServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.github.tortaje.rest.exec.HttpTransport;
import io.github.tortaje.rest.request.HttpCall;
import io.opentelemetry.sdk.trace.data.EventData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestSpanTest {

    private static final String SECRET = "S3CR3T-MARK";
    private static InMemorySpanExporter spans;

    private StubServer stub;

    @BeforeAll
    static void registerSdk() {
        spans = InMemorySpanExporter.create();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        // first OtelFixtures use: fitnesse-otel warns that it reuses the instance set above; keep it off the console
        try (LogCapture otelLog = LogCapture.on("io.github.tortaje.otel")) {
            OtelFixtures.tracer();
            assertTrue(otelLog.text().contains("already set"), otelLog.text());
        }
    }

    @BeforeEach
    void start() {
        spans.reset();
        stub = StubServer.http();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private Rest rest() {
        Rest r = new Rest();
        r.baseUrl(stub.url());
        r.retryDelay("0");
        return r;
    }

    private static String str(SpanData s, String key) {
        return s.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static Long num(SpanData s, String key) {
        return s.getAttributes().get(AttributeKey.longKey(key));
    }

    @Test
    void oneClientSpanPerAttempt() {
        stub.reply("GET", "/x", 503, "");
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest();
        r.maxRetries("1");
        r.get("/x");
        assertEquals(200, r.status());

        List<SpanData> items = spans.getFinishedSpanItems();
        assertEquals(2, items.size());
        SpanData first = items.get(0);
        SpanData second = items.get(1);
        for (SpanData s : items) {
            assertEquals(SpanKind.CLIENT, s.getKind());
            assertEquals("GET", s.getName());
        }
        assertEquals(StatusCode.ERROR, first.getStatus().getStatusCode());
        assertEquals("503", str(first, "error.type"));
        assertEquals(503L, num(first, "http.response.status_code"));
        assertNull(num(first, "http.request.resend_count"));
        assertEquals(1L, num(second, "http.request.resend_count"));
        assertEquals(StatusCode.UNSET, second.getStatus().getStatusCode());
        assertEquals(200L, num(second, "http.response.status_code"));
    }

    @Test
    void urlFullHasNoQuery() {
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest();
        r.queryParam("token", "abc");
        r.get("/x");

        SpanData s = spans.getFinishedSpanItems().get(0);
        assertEquals(stub.url() + "/x", str(s, "url.full"));
        assertEquals("127.0.0.1", str(s, "server.address"));
        assertNotNull(num(s, "server.port"));
        assertEquals((long) java.net.URI.create(stub.url()).getPort(), num(s, "server.port"));
    }

    @Test
    void traceparentOnBusinessOnly() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"A1\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest();
        r.tokenUrl(stub.url() + "/token");
        r.clientCredentials("id", SECRET);
        r.get("/x");

        assertTrue(stub.requests("/x").get(0).headers().containsKey("traceparent"));
        assertFalse(stub.requests("/token").get(0).headers().containsKey("traceparent"));
        assertTrue(spans.getFinishedSpanItems().stream().anyMatch(s ->
                "POST".equals(s.getName()) && str(s, "url.full").endsWith("/token")));
    }

    @Test
    void exceptionRecorded() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Rest r = new Rest();
        r.maxRetries("0");
        assertThrows(RestFixtureException.class, () -> r.get("http://127.0.0.1:" + port + "/x"));

        SpanData s = spans.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, s.getStatus().getStatusCode());
        assertEquals("java.net.ConnectException", str(s, "error.type"));
        assertTrue(s.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())));
    }

    @Test
    void noSecretInAttributes() {
        stub.reply("POST", "/token", 200, "{\"access_token\":\"A1\",\"expires_in\":3600}");
        stub.reply("GET", "/x", 200, "ok");
        Rest r = rest();
        r.tokenUrl(stub.url() + "/token");
        r.clientCredentials("id", SECRET);
        r.get("/x");
        Rest k = rest();
        k.apiKeyParam("api_key", SECRET);
        k.get("/x");
        Rest bad = new Rest();
        bad.baseUrl("http://my_svc:8080");
        bad.apiKeyParam("api_key", SECRET);
        assertThrows(RestFixtureException.class, () -> bad.get("/x"));
        HttpTransport direct = new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), Set.of());
        HttpCall badCall = new HttpCall("GET", URI.create("http://my_svc:8080/x?api_key=" + SECRET), Map.of(), null);
        assertThrows(RestFixtureException.class, () -> direct.send(badCall, 0, true));

        List<SpanData> items = spans.getFinishedSpanItems();
        assertTrue(items.size() >= 4);
        assertTrue(items.stream().anyMatch(s -> !s.getEvents().isEmpty()), "no span event scanned");
        for (SpanData s : items) {
            assertFalse(s.getName().contains(SECRET));
            s.getAttributes().forEach((key, value) -> assertFalse(String.valueOf(value).contains(SECRET),
                    key + " leaks the secret"));
            for (EventData e : s.getEvents()) {
                assertFalse(e.getName().contains(SECRET));
                e.getAttributes().forEach((key, value) -> assertFalse(String.valueOf(value).contains(SECRET),
                        "event " + e.getName() + " " + key + " leaks the secret"));
            }
            assertFalse(String.valueOf(s.getStatus().getDescription()).contains(SECRET), "status leaks the secret");
        }
    }
}
