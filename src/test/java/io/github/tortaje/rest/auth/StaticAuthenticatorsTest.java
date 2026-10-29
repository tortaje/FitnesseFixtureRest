package io.github.tortaje.rest.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.secret.SecretValue;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StaticAuthenticatorsTest {

    @AfterEach
    void clear() {
        SecretRegistry.clear();
    }

    private final HttpCall base = new HttpCall("GET", URI.create("https://h/x"), Map.of(), null);

    @Test
    void basicEncodesUtf8() {
        assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("usér:pa:ss".getBytes(UTF_8)),
                new BasicAuth(SecretValue.of("usér"), SecretValue.of("pa:ss"))
                        .apply(base)
                        .headers()
                        .get("Authorization"));
    }

    @Test
    void bearerResolvesSecretAtApply() {
        SecretRegistry.put("tok", "T1");
        assertEquals(
                "Bearer T1",
                new BearerAuth(SecretValue.of("secret:tok")).apply(base).headers().get("Authorization"));
    }

    @Test
    void apiKeyHeaderAndParam() {
        assertEquals(
                "K",
                new ApiKeyHeader("X-API-Key", SecretValue.of("K")).apply(base).headers().get("X-API-Key"));
        assertEquals(
                "api_key=K",
                new ApiKeyParam("api_key", SecretValue.of("K")).apply(base).uri().getRawQuery());
        assertEquals(
                Set.of("X-API-Key"),
                new ApiKeyHeader("X-API-Key", SecretValue.of("K")).sensitiveHeaders());
    }

    @Test
    void toStringHidesValues() {
        assertFalse(new BasicAuth(SecretValue.of("u"), SecretValue.of("pwd")).toString().contains("pwd"));
    }

    @Test
    void noAuthLeavesCallUnchanged() {
        assertSame(base, NoAuth.INSTANCE.apply(base));
    }
}
