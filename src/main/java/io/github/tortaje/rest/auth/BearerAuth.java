package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;

/** Bearer token authentication: {@code Authorization: Bearer <token>}. */
public record BearerAuth(SecretValue token) implements Authenticator {

    @Override
    public HttpCall apply(HttpCall call) {
        return call.withHeader("Authorization", "Bearer " + token.reveal());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
