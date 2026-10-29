package io.github.tortaje.rest.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.auth.Authenticator;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import io.github.tortaje.rest.testsupport.FakeTransport;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ExecutorTest {

    private static final Duration DELAY = Duration.ofMillis(500);

    static final class RefreshableAuth implements Authenticator {
        int refreshes;

        @Override
        public HttpCall apply(HttpCall call) {
            return call.withHeader("Authorization", refreshes == 0 ? "Bearer T1" : "Bearer T2");
        }

        @Override
        public boolean canRefresh() {
            return true;
        }

        @Override
        public void refresh() {
            refreshes++;
        }
    }

    private final List<Duration> sleeps = new ArrayList<>();

    private Executor executor(FakeTransport t) {
        return new Executor(t, sleeps::add);
    }

    private static HttpCall call(String method) {
        return new HttpCall(method, URI.create("http://host/path"), Map.of(), null);
    }

    private static final Authenticator NONE = c -> c;

    @Test
    void getRetries503ThenSucceeds() {
        FakeTransport t = new FakeTransport(503, 200);
        RestResponse r = executor(t).execute(call("GET"), NONE, 2, DELAY);
        assertEquals(200, r.status());
        assertEquals(List.of(0, 1), t.resendCounts);
        assertEquals(List.of(DELAY), sleeps);
        assertEquals(List.of(true, true), t.business);
    }

    @Test
    void getStopsAfterMaxRetries() {
        FakeTransport t = new FakeTransport(503, 503, 503);
        assertEquals(503, executor(t).execute(call("GET"), NONE, 2, DELAY).status());
        assertEquals(3, t.calls());
    }

    @Test
    void postIsNotRetriedOn503() {
        FakeTransport t = new FakeTransport(503, 200);
        assertEquals(503, executor(t).execute(call("POST"), NONE, 2, DELAY).status());
        assertEquals(1, t.calls());
    }

    @Test
    void patchIsNotRetriedOnResponseTimeout() {
        FakeTransport t = new FakeTransport(new HttpTimeoutException("t"), 200);
        RestFixtureException e = assertThrows(RestFixtureException.class,
            () -> executor(t).execute(call("PATCH"), NONE, 2, DELAY));
        assertTrue(e.getMessage().contains(": no response after 30 s"), e.getMessage());
        assertEquals(1, t.calls());
    }

    @Test
    void postIsRetriedOnConnectionRefused() {
        FakeTransport t = new FakeTransport(new ConnectException("refused"), 201);
        assertEquals(201, executor(t).execute(call("POST"), NONE, 1, DELAY).status());
        assertEquals(2, t.calls());
    }

    @Test
    void connectionErrorsExhaustedReportAttempts() {
        FakeTransport t = new FakeTransport(new ConnectException("r"), new ConnectException("r"), new ConnectException("r"));
        RestFixtureException e = assertThrows(RestFixtureException.class,
            () -> executor(t).execute(call("GET"), NONE, 2, DELAY));
        assertTrue(e.getMessage().contains("(3 attempts)"), e.getMessage());
        assertEquals(3, t.calls());
    }

    @Test
    void notRetriedOn400And404() {
        FakeTransport t400 = new FakeTransport(400, 200);
        assertEquals(400, executor(t400).execute(call("GET"), NONE, 3, DELAY).status());
        assertEquals(1, t400.calls());
        FakeTransport t404 = new FakeTransport(404, 200);
        assertEquals(404, executor(t404).execute(call("GET"), NONE, 3, DELAY).status());
        assertEquals(1, t404.calls());
    }

    @Test
    void sslErrorIsNotRetried() {
        FakeTransport t = new FakeTransport(new SSLHandshakeException("bad cert"), 200);
        RestFixtureException e = assertThrows(RestFixtureException.class,
            () -> executor(t).execute(call("GET"), NONE, 3, DELAY));
        assertTrue(e.getMessage().contains("TLS handshake failed"), e.getMessage());
        assertEquals(1, t.calls());
    }

    @Test
    void refreshOnce401ThenSucceeds() {
        FakeTransport t = new FakeTransport(401, 200);
        RefreshableAuth auth = new RefreshableAuth();
        assertEquals(200, executor(t).execute(call("GET"), auth, 0, DELAY).status());
        assertEquals(1, auth.refreshes);
        assertEquals(List.of("Bearer T1", "Bearer T2"), t.authHeaders);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void second401IsReturned() {
        FakeTransport t = new FakeTransport(401, 401, 200);
        RefreshableAuth auth = new RefreshableAuth();
        assertEquals(401, executor(t).execute(call("GET"), auth, 0, DELAY).status());
        assertEquals(1, auth.refreshes);
        assertEquals(2, t.calls());
    }

    @Test
    void no401RefreshWithoutCapability() {
        FakeTransport t = new FakeTransport(401, 200);
        assertEquals(401, executor(t).execute(call("GET"), NONE, 0, DELAY).status());
        assertEquals(1, t.calls());
    }

    @Test
    void refreshDoesNotConsumeRetries() {
        FakeTransport t = new FakeTransport(401, 503, 200);
        RefreshableAuth auth = new RefreshableAuth();
        assertEquals(200, executor(t).execute(call("GET"), auth, 1, DELAY).status());
        assertEquals(3, t.calls());
    }

    @Test
    void dnsFailureIsNotRetried() {
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        ConnectException unknown = new ConnectException("x");
        unknown.initCause(new UnknownHostException("no-such-host"));
        for (ConnectException dns : new ConnectException[] {unresolved, unknown}) {
            FakeTransport t = new FakeTransport(dns, 200);
            RestFixtureException e = assertThrows(RestFixtureException.class,
                () -> executor(t).execute(call("GET"), NONE, 3, DELAY));
            assertEquals("GET host/path: unknown host", e.text());
            assertEquals(1, t.calls());
        }
        assertTrue(sleeps.isEmpty());
    }
}
