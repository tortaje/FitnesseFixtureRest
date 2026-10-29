package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;

/** API key sent as a query parameter. */
public record ApiKeyParam(String name, SecretValue value) implements Authenticator {

    @Override
    public HttpCall apply(HttpCall call) {
        return call.withQueryParam(name, value.reveal());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
