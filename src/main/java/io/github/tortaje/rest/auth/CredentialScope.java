package io.github.tortaje.rest.auth;

import java.net.URI;
import java.util.Locale;

/**
 * Decides where credentials may go: only to the origin (scheme, host, port) of the base URL, so a token cannot be
 * sent to another host named by an absolute endpoint or by a URL taken from a response.
 */
public final class CredentialScope {

    private CredentialScope() {
    }

    /**
     * True when {@code target} may receive credentials: it has the base URL's origin, or no base URL is set (every
     * endpoint is then absolute and written by the page author). An unparseable base URL allows nothing.
     */
    public static boolean allows(String baseUrl, URI target) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        URI base;
        try {
            base = new URI(baseUrl.trim());
        } catch (Exception e) {
            return false;
        }
        return sameOrigin(base, target);
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null && a.getHost() != null && b.getHost() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    /** True for {@code http} to a host other than the loopback interface: credentials would travel in clear. */
    public static boolean cleartext(URI target) {
        return "http".equalsIgnoreCase(target.getScheme()) && !loopback(target.getHost());
    }

    /** {@code scheme://host:port} of {@code uri}, safe for messages (no userinfo, path or query). */
    public static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + ":" + port(uri);
    }

    static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1");
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
