package io.github.tortaje.rest.request;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A fully prepared HTTP request; {@code body} may be null. {@code sensitiveHeaders} names the headers whose value
 * came from a {@code secret:} reference, masked in logs. Immutable.
 */
public record HttpCall(String method, URI uri, Map<String, String> headers, String body,
        Set<String> sensitiveHeaders) {

    public HttpCall {
        headers = headers == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        sensitiveHeaders = sensitiveHeaders == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(sensitiveHeaders));
    }

    public HttpCall(String method, URI uri, Map<String, String> headers, String body) {
        this(method, uri, headers, body, Set.of());
    }

    /** Replaces a header with the same name (ignoring case), otherwise appends it. */
    public HttpCall withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(k -> k.equalsIgnoreCase(name));
        copy.put(name, value);
        return new HttpCall(method, uri, copy, body, sensitiveHeaders);
    }

    /** Appends an encoded {@code name=value} to the query string. */
    public HttpCall withQueryParam(String name, String value) {
        URI next = URI.create(appendQuery(uri.toString(), encode(name) + "=" + encode(value)));
        return new HttpCall(method, next, headers, body, sensitiveHeaders);
    }

    /** Appends already-encoded query text before any fragment. */
    static String appendQuery(String url, String query) {
        int hash = url.indexOf('#');
        String base = hash >= 0 ? url.substring(0, hash) : url;
        String fragment = hash >= 0 ? url.substring(hash) : "";
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + query + fragment;
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
