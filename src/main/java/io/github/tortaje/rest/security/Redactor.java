package io.github.tortaje.rest.security;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strips secrets from URLs and header maps before they reach logs, spans or messages. */
public final class Redactor {
    public static final Set<String> SENSITIVE_HEADERS =
        Set.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie");

    private static final String MASK = "****";

    private Redactor() {
    }

    /** scheme://host[:port]/path, without userinfo, query or fragment. */
    public static String safeUrl(URI uri) {
        return uri.getScheme() + "://" + hostAndPath(uri);
    }

    /** host[:port]/path, without scheme, userinfo, query or fragment. */
    public static String hostAndPath(URI uri) {
        StringBuilder sb = new StringBuilder();
        if (uri.getHost() != null) {
            sb.append(uri.getHost());
        }
        if (uri.getPort() >= 0) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        return sb.toString();
    }

    /** Copy of {@code headers} (order kept) with the values of sensitive names replaced by {@code ****}. */
    public static Map<String, String> redactHeaders(Map<String, String> headers, Set<String> extraSensitive) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.put(e.getKey(), isSensitive(e.getKey(), extraSensitive) ? MASK : e.getValue());
        }
        return out;
    }

    private static boolean isSensitive(String name, Set<String> extra) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_HEADERS) {
            if (s.toLowerCase(Locale.ROOT).equals(n)) {
                return true;
            }
        }
        if (extra != null) {
            for (String s : extra) {
                if (s.toLowerCase(Locale.ROOT).equals(n)) {
                    return true;
                }
            }
        }
        return false;
    }
}
