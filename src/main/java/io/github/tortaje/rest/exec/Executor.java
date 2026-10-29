package io.github.tortaje.rest.exec;

import io.github.tortaje.rest.auth.Authenticator;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;

/** Applies the retry matrix (spec 6.1) and a single 401 token refresh around a {@link Transport}. */
public final class Executor {

    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT", "DELETE");
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    private final Transport transport;
    private final Sleeper sleeper;

    public Executor(Transport transport, Sleeper sleeper) {
        this.transport = transport;
        this.sleeper = sleeper;
    }

    public RestResponse execute(HttpCall call, Authenticator auth, int maxRetries, Duration retryDelay) {
        boolean idempotent = IDEMPOTENT.contains(call.method());
        int attempt = 0;
        int retriesLeft = maxRetries;
        boolean refreshed = false;
        while (true) {
            HttpCall request = auth.apply(call);
            int resend = attempt;
            attempt++;
            RestResponse r;
            try {
                r = transport.send(request, resend, true);
            } catch (HttpConnectTimeoutException | ConnectException e) {
                // DNS failures arrive as ConnectException but are not transient (spec 6.1): stop at once
                if (!Failures.isDns(e) && retriesLeft-- > 0 && pause(retryDelay, call, attempt)) {
                    continue;
                }
                throw Failures.of(call.method(), call.uri(), e, attempt, transport.timeout());
            } catch (HttpTimeoutException e) {
                if (idempotent && retriesLeft-- > 0 && pause(retryDelay, call, attempt)) {
                    continue;
                }
                throw Failures.of(call.method(), call.uri(), e, attempt, transport.timeout());
            } catch (IOException | InterruptedException e) {
                throw Failures.of(call.method(), call.uri(), e, attempt, transport.timeout());
            }
            if (RETRYABLE_STATUS.contains(r.status()) && idempotent && retriesLeft-- > 0
                    && pause(retryDelay, call, attempt)) {
                continue;
            }
            if (r.status() == 401 && !refreshed && auth.canRefresh()) {
                refreshed = true;
                auth.refresh();
                continue;
            }
            return r;
        }
    }

    /** Sleeps before a retry; an interrupted sleep ends the loop with a failure. */
    private boolean pause(Duration delay, HttpCall call, int attempts) {
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            throw Failures.of(call.method(), call.uri(), e, attempts, transport.timeout());
        }
    }
}
