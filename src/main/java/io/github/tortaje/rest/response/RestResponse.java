package io.github.tortaje.rest.response;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of an HTTP response as seen by the fixture; {@code truncated} when the body exceeded the
 * configured maximum size and only its beginning was kept.
 */
public record RestResponse(int status, Map<String, List<String>> headers, String body, Duration duration,
        boolean truncated) {

    public RestResponse(int status, Map<String, List<String>> headers, String body, Duration duration) {
        this(status, headers, body, duration, false);
    }

    /** Values of the header (case-insensitive name) joined with {@code ", "}; empty when absent. */
    public String header(String name) {
        if (headers == null || name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null) {
                for (String v : e.getValue()) {
                    if (out.length() > 0) {
                        out.append(", ");
                    }
                    out.append(v);
                }
            }
        }
        return out.toString();
    }
}
