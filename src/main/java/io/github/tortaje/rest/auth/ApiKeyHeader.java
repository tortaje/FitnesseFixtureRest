package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;
import java.util.Set;

/** API key sent in a request header. */
public record ApiKeyHeader(String name, SecretValue value) implements Authenticator {

    @Override
    public HttpCall apply(HttpCall call) {
        return call.withHeader(name, value.reveal());
    }

    @Override
    public Set<String> sensitiveHeaders() {
        return Set.of(name);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
