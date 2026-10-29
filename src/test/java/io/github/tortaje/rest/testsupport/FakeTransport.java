package io.github.tortaje.rest.testsupport;

import io.github.tortaje.rest.exec.Transport;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Scripted results: Integer = status, {@link Reply} = status and body, Exception = thrown. */
public final class FakeTransport implements Transport {

    public record Reply(int status, String body) {
    }

    private final List<Object> script;
    public final List<Integer> resendCounts = new ArrayList<>();
    public final List<Boolean> business = new ArrayList<>();
    public final List<String> authHeaders = new ArrayList<>();
    public final List<String> bodies = new ArrayList<>();
    public final List<URI> uris = new ArrayList<>();

    public FakeTransport(Object... script) {
        this.script = List.of(script);
    }

    public int calls() {
        return resendCounts.size();
    }

    @Override
    public RestResponse send(HttpCall call, int resendCount, boolean biz) throws IOException, InterruptedException {
        Object next = script.get(resendCounts.size());
        resendCounts.add(resendCount);
        business.add(biz);
        authHeaders.add(call.headers().get("Authorization"));
        bodies.add(call.body());
        uris.add(call.uri());
        if (next instanceof IOException e) {
            throw e;
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        if (next instanceof Reply r) {
            return new RestResponse(r.status(), Map.of(), r.body(), Duration.ZERO);
        }
        return new RestResponse((Integer) next, Map.of(), "", Duration.ZERO);
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(30);
    }
}
