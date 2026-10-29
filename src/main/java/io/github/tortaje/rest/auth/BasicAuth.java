package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** HTTP Basic authentication: {@code Authorization: Basic base64(user:password)}. */
public record BasicAuth(SecretValue user, SecretValue password) implements Authenticator {

    @Override
    public HttpCall apply(HttpCall call) {
        String credentials = user.reveal() + ":" + password.reveal();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return call.withHeader("Authorization", "Basic " + encoded);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
