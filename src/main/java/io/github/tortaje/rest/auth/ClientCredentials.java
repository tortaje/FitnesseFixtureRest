package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.exec.Failures;
import io.github.tortaje.rest.exec.Transport;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import io.github.tortaje.rest.secret.SecretValue;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/** OAuth2 client-credentials grant with a per-instance token cache (never static). */
public final class ClientCredentials implements Authenticator {

    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    private final String tokenUrl;
    private final SecretValue clientId;
    private final SecretValue clientSecret;
    private final String scope;
    private final Supplier<Transport> transport;
    private final Clock clock;

    private String token;
    private Instant validUntil;

    public ClientCredentials(String tokenUrl, SecretValue clientId, SecretValue clientSecret, String scope,
                             Supplier<Transport> transport, Clock clock) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public synchronized HttpCall apply(HttpCall call) {
        if (token == null || (validUntil != null && !clock.instant().isBefore(validUntil))) {
            fetch();
        }
        return call.withHeader("Authorization", "Bearer " + token);
    }

    @Override
    public boolean canRefresh() {
        return true;
    }

    @Override
    public synchronized void refresh() {
        fetch();
    }

    private void fetch() {
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new RestFixtureException("No token url: set it with |token url| or token_url");
        }
        String host;
        URI uri;
        try {
            uri = URI.create(tokenUrl);
            host = uri.getHost();
        } catch (IllegalArgumentException e) {
            throw new RestFixtureException("Invalid token url");
        }
        if (host == null) {
            throw new RestFixtureException("Invalid token url");
        }
        String prefix = "Token request to " + host + " failed: ";
        HttpCall call = new HttpCall("POST", uri,
            Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), form());
        Transport t = transport.get();
        RestResponse response;
        try {
            response = t.send(call, 0, false);
        } catch (IOException | InterruptedException e) {
            throw new RestFixtureException(prefix + Failures.detail(e, t.timeout()), e);
        }
        if (response.status() < 200 || response.status() > 299) {
            String error = oauthError(response.body());
            throw new RestFixtureException(prefix + "HTTP " + response.status()
                + (error == null ? "" : ", error=" + error));
        }
        String accessToken = string(response.body(), "$.access_token");
        if (accessToken == null) {
            throw new RestFixtureException(prefix + "no access_token in response");
        }
        Instant expiry = null;
        Long expiresIn = expiresIn(response.body());
        if (expiresIn != null) {
            expiry = clock.instant().plusSeconds(expiresIn).minus(SAFETY_MARGIN);
        }
        token = accessToken;
        validUntil = expiry;
    }

    private String form() {
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        body.append("&client_id=").append(enc(clientId.reveal()));
        body.append("&client_secret=").append(enc(clientSecret.reveal()));
        if (scope != null && !scope.isBlank()) {
            body.append("&scope=").append(enc(scope));
        }
        return body.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** The OAuth {@code error} code, only when it is a non-blank JSON string; never the description or the body. */
    private static String oauthError(String body) {
        return string(body, "$.error");
    }

    private static final long MAX_EXPIRES_IN = Duration.ofDays(3650).toSeconds();

    private static Long expiresIn(String body) {
        Object raw = read(body, "$.expires_in");
        double value;
        try {
            if (raw instanceof Number n) {
                value = n.doubleValue();
            } else if (raw instanceof String str) {
                value = Double.parseDouble(str.trim());
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(value) || value > MAX_EXPIRES_IN) {
            return null;
        }
        return (long) value;
    }

    private static String string(String body, String path) {
        return read(body, path) instanceof String s && !s.isBlank() ? s : null;
    }

    private static Object read(String body, String path) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonPath.parse(body).read(path);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
