package io.github.tortaje.rest.config;

import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.transport.TlsSettings;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable effective configuration of a fixture instance. */
public record RestSettings(String baseUrl, Duration timeout, int maxRetries, Duration retryDelay,
        TlsSettings tls, String tokenUrl, SecretValue clientId, SecretValue clientSecret, String scope,
        Map<String, SecretValue> defaultHeaders, long maxResponseBytes) {

    /** Response bodies larger than this are truncated (16 MiB unless configured). */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

    public RestSettings {
        defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders));
    }

    public RestSettings(String baseUrl, Duration timeout, int maxRetries, Duration retryDelay, TlsSettings tls,
            String tokenUrl, SecretValue clientId, SecretValue clientSecret, String scope,
            Map<String, SecretValue> defaultHeaders) {
        this(baseUrl, timeout, maxRetries, retryDelay, tls, tokenUrl, clientId, clientSecret, scope, defaultHeaders,
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    public RestSettings withBaseUrl(String v) {
        return new RestSettings(v, timeout, maxRetries, retryDelay, tls, tokenUrl, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withTimeout(Duration v) {
        return new RestSettings(baseUrl, v, maxRetries, retryDelay, tls, tokenUrl, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withMaxRetries(int v) {
        return new RestSettings(baseUrl, timeout, v, retryDelay, tls, tokenUrl, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withRetryDelay(Duration v) {
        return new RestSettings(baseUrl, timeout, maxRetries, v, tls, tokenUrl, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withTls(TlsSettings v) {
        return new RestSettings(baseUrl, timeout, maxRetries, retryDelay, v, tokenUrl, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withTokenUrl(String v) {
        return new RestSettings(baseUrl, timeout, maxRetries, retryDelay, tls, v, clientId, clientSecret, scope,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withScope(String v) {
        return new RestSettings(baseUrl, timeout, maxRetries, retryDelay, tls, tokenUrl, clientId, clientSecret, v,
                defaultHeaders, maxResponseBytes);
    }

    public RestSettings withClientCredentials(SecretValue id, SecretValue secret) {
        return new RestSettings(baseUrl, timeout, maxRetries, retryDelay, tls, tokenUrl, id, secret, scope,
                defaultHeaders, maxResponseBytes);
    }
}
