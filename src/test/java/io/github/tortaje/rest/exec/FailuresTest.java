package io.github.tortaje.rest.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class FailuresTest {

    private static final URI U = URI.create("https://api.uat.example.com/payments?k=v");

    @Test
    void messages() {
        assertEquals("POST api.uat.example.com/payments: no response after 30 s (3 attempts)",
            Failures.of("POST", U, new HttpTimeoutException("x"), 3, Duration.ofSeconds(30)).text());
        assertEquals("GET api.uat.example.com/payments: connection timed out after 30 s",
            Failures.of("GET", U, new HttpConnectTimeoutException("x"), 1, Duration.ofSeconds(30)).text());
        assertEquals("GET api.uat.example.com/payments: TLS handshake failed (PKIX path building failed)",
            Failures.of("GET", U, new SSLHandshakeException("PKIX path building failed"), 1,
                Duration.ofSeconds(30)).text());
    }

    @Test
    void otherDetails() {
        Duration t = Duration.ofSeconds(5);
        assertEquals("connection refused", Failures.detail(new ConnectException("boom"), t));
        assertEquals("unknown host",
            Failures.detail(new java.io.IOException("x", new UnknownHostException("h")), t));
        assertEquals("I/O error (EOFException)", Failures.detail(new java.io.EOFException(), t));
        assertEquals("interrupted", Failures.detail(new InterruptedException(), t));
        assertEquals(true, Thread.interrupted());
    }
}
