package io.github.tortaje.rest.exec;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.security.Redactor;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import javax.net.ssl.SSLException;

/** Turns transport exceptions into short, secret-free fixture messages. */
public final class Failures {

    private Failures() {
    }

    public static String detail(Exception e, Duration timeout) {
        if (e instanceof HttpConnectTimeoutException) {
            return "connection timed out after " + seconds(timeout) + " s";
        }
        if (e instanceof HttpTimeoutException) {
            return "no response after " + seconds(timeout) + " s";
        }
        if (e instanceof ConnectException && !isDns(e)) {
            return "connection refused";
        }
        if (e instanceof SSLException) {
            return "TLS handshake failed (" + rootMessage(e) + ")";
        }
        if (isDns(e)) {
            return "unknown host";
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        return "I/O error (" + e.getClass().getSimpleName() + ")";
    }

    public static RestFixtureException of(String method, URI uri, Exception e, int attempts, Duration timeout) {
        String text = method + " " + Redactor.hostAndPath(uri) + ": " + detail(e, timeout);
        if (attempts > 1) {
            text += " (" + attempts + " attempts)";
        }
        return new RestFixtureException(text, e);
    }

    /** True when the JDK reports a name-resolution failure (often wrapped in a {@code ConnectException}). */
    static boolean isDns(Throwable t) {
        return hasCause(t, UnknownHostException.class, UnresolvedAddressException.class);
    }

    private static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? String.valueOf(d.toSeconds()) : String.valueOf(d.toMillis() / 1000.0);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    @SafeVarargs
    private static boolean hasCause(Throwable t, Class<? extends Throwable>... types) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(c)) {
                    return true;
                }
            }
        }
        return false;
    }
}
