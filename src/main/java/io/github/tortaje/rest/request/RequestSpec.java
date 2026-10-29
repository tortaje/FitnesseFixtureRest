package io.github.tortaje.rest.request;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The wiki-side request under construction: headers, query parameters and body. */
public final class RequestSpec {

    private static final Pattern ABSOLUTE = Pattern.compile("(?i)^https?://");

    private final Map<String, SecretValue> headers = new LinkedHashMap<>();
    private final List<String[]> queryParams = new ArrayList<>();
    private String body;

    public void header(String name, SecretValue value) {
        headers.keySet().removeIf(k -> k.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    public void queryParam(String name, String value) {
        queryParams.add(new String[] {name, value});
    }

    public void body(String body) {
        this.body = body;
    }

    /** Clears the body and the query parameters, keeping the headers. */
    public void clearPayload() {
        body = null;
        queryParams.clear();
    }

    /** Clears everything. */
    public void reset() {
        clearPayload();
        headers.clear();
    }

    public HttpCall prepare(String method, String baseUrl, String endpoint, Map<String, SecretValue> defaultHeaders) {
        String url = absoluteUrl(baseUrl, endpoint);
        String full = url;
        for (String[] param : queryParams) {
            full = HttpCall.appendQuery(full, HttpCall.encode(param[0]) + "=" + HttpCall.encode(param[1]));
        }
        URI uri;
        try {
            uri = new URI(full);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw invalidUrl(endpoint);
        }
        // java.net.http only accepts http/https with a parseable host; reject anything else here, before an
        // authenticator appends a secret and the JDK echoes the whole URI in its exception
        if (uri.getHost() == null || uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw invalidUrl(endpoint);
        }
        Map<String, SecretValue> merged = new LinkedHashMap<>();
        if (defaultHeaders != null) {
            merged.putAll(defaultHeaders);
        }
        headers.forEach((name, value) -> {
            merged.keySet().removeIf(k -> k.equalsIgnoreCase(name));
            merged.put(name, value);
        });
        Map<String, String> revealed = new LinkedHashMap<>();
        Set<String> sensitive = new LinkedHashSet<>();
        merged.forEach((name, value) -> {
            revealed.put(name, value.reveal());
            if (value.isReference()) {
                sensitive.add(name);
            }
        });
        return new HttpCall(method, uri, revealed, body, sensitive);
    }

    private static final Pattern USERINFO = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@");

    /** The endpoint without query string or userinfo: both can carry credentials. */
    private static RestFixtureException invalidUrl(String endpoint) {
        int q = endpoint.indexOf('?');
        String shown = USERINFO.matcher(q >= 0 ? endpoint.substring(0, q) : endpoint).replaceFirst("$1");
        return new RestFixtureException("Invalid URL for endpoint '" + shown + "'");
    }

    private static String absoluteUrl(String baseUrl, String endpoint) {
        if (ABSOLUTE.matcher(endpoint).lookingAt()) {
            return endpoint;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RestFixtureException("No base url: set it with |base url| or base_url");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        return base + "/" + path;
    }
}
