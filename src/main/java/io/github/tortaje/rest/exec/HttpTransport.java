package io.github.tortaje.rest.exec;

import io.github.tortaje.otel.OtelFixtures;
import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.config.RestSettings;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import io.github.tortaje.rest.security.Redactor;
import io.github.tortaje.rest.telemetry.HttpSpans;
import io.github.tortaje.rest.telemetry.Traced;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link Transport} over {@code java.net.http}, one CLIENT span per call. */
public final class HttpTransport implements Transport {

    private static final Logger LOG = Logger.getLogger("io.github.tortaje.rest");
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([^;\"\\s]+)");

    private final HttpClient client;
    private final Duration timeout;
    private final Set<String> sensitiveHeaders;
    private final long maxBodyBytes;

    public HttpTransport(HttpClient client, Duration timeout, Set<String> sensitiveHeaders) {
        this(client, timeout, sensitiveHeaders, RestSettings.DEFAULT_MAX_RESPONSE_BYTES);
    }

    public HttpTransport(HttpClient client, Duration timeout, Set<String> sensitiveHeaders, long maxBodyBytes) {
        this.client = client;
        this.timeout = timeout;
        this.sensitiveHeaders = sensitiveHeaders;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public RestResponse send(HttpCall call, int resendCount, boolean business)
            throws IOException, InterruptedException {
        try {
            return Traced.call(call.method(), HttpSpans.request(call.method(), call.uri(), resendCount),
                () -> doSend(call, business));
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // unreachable in practice: Traced.call declares "throws Exception"
            throw new IllegalStateException(e);
        }
    }

    private RestResponse doSend(HttpCall call, boolean business) throws IOException, InterruptedException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(call.uri()).timeout(timeout);
        } catch (IllegalArgumentException e) {
            // the JDK message holds the full URI, query included: never chain it
            throw new RestFixtureException(call.method() + " " + Redactor.hostAndPath(call.uri()) + ": invalid URL");
        }
        builder.method(call.method(), call.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> h : call.headers().entrySet()) {
            try {
                builder.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                throw new RestFixtureException("Header '" + h.getKey() + "' cannot be set");
            }
        }
        if (business) {
            OtelFixtures.inject(builder::header);
        }
        String safeUrl = Redactor.safeUrl(call.uri());
        if (LOG.isLoggable(Level.FINE)) {
            Set<String> masked = new HashSet<>(sensitiveHeaders);
            masked.addAll(call.sensitiveHeaders());
            LOG.fine(call.method() + " " + safeUrl + " headers " + Redactor.redactHeaders(call.headers(), masked));
            if (business && call.body() != null) {
                LOG.fine(call.method() + " " + safeUrl + " body " + call.body());
            }
        }
        long start = System.nanoTime();
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        boolean truncated;
        try (InputStream in = response.body()) {
            // read one byte past the limit to know whether the body was cut; closing early abandons the rest
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBodyBytes + 1));
            truncated = bytes.length > maxBodyBytes;
        }
        if (truncated) {
            bytes = Arrays.copyOf(bytes, (int) maxBodyBytes);
        }
        String body = new String(bytes, charset(response.headers()));
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        HttpSpans.response(Span.current(), response.statusCode());
        LOG.info(call.method() + " " + safeUrl + " -> " + response.statusCode() + " (" + duration.toMillis() + " ms)");
        if (truncated) {
            LOG.warning(call.method() + " " + safeUrl + ": body truncated at " + maxBodyBytes
                    + " bytes (max_response_bytes)");
        }
        return new RestResponse(response.statusCode(), response.headers().map(), body, duration, truncated);
    }

    /** The {@code charset} parameter of Content-Type, UTF-8 when absent or unknown. */
    private static Charset charset(HttpHeaders headers) {
        Matcher m = CHARSET.matcher(headers.firstValue("Content-Type").orElse(""));
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
