package io.github.tortaje.rest.telemetry;

import io.github.tortaje.rest.security.Redactor;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.net.URI;

/** Semantic-convention attributes for outgoing HTTP spans. Never carries a query string or userinfo. */
public final class HttpSpans {

    private HttpSpans() {
    }

    public static Attributes request(String method, URI uri, int resendCount) {
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        var b = Attributes.builder()
            .put("http.request.method", method)
            .put("url.full", Redactor.safeUrl(uri))
            .put("server.address", uri.getHost() == null ? "" : uri.getHost())
            .put("server.port", (long) port);
        if (resendCount > 0) {
            b.put("http.request.resend_count", (long) resendCount);
        }
        return b.build();
    }

    public static void response(Span span, int status) {
        span.setAttribute("http.response.status_code", (long) status);
        if (status >= 400) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", String.valueOf(status));
        }
    }
}
