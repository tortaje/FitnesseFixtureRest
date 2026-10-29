package io.github.tortaje.rest.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.testsupport.LogCapture;
import io.github.tortaje.rest.testsupport.StubServer;
import io.github.tortaje.rest.testsupport.TestTls;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransportFactoryTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final SecretValue PWD = SecretValue.of("changeit");

    private final TransportFactory factory = new TransportFactory();

    @BeforeEach
    void clean() {
        SecretRegistry.clear();
    }

    @AfterEach
    void cleanAfter() {
        SecretRegistry.clear();
    }

    private int get(StubServer s, TlsSettings tls) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.url() + "/ok")).GET().build();
        return factory.client(tls, T).send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private StubServer server(boolean mtls) {
        StubServer s = StubServer.https(TestTls.server(mtls), mtls);
        s.reply("GET", "/ok", 200, "ok");
        return s;
    }

    @Test
    void untrustedServerFailsByDefault() {
        try (StubServer s = server(false)) {
            Exception e = assertThrows(Exception.class, () -> get(s, TlsSettings.none()));
            assertTrue(e instanceof SSLHandshakeException || e.getCause() instanceof SSLHandshakeException, e.toString());
        }
    }

    @Test
    void truststoreFileTrustsServer() throws Exception {
        try (StubServer s = server(false)) {
            assertEquals(200, get(s, TlsSettings.none()
                .withTruststore(TestTls.file("truststore.p12").toString(), PWD)));
        }
    }

    @Test
    void keystoreP12AndJksForMutualTls() throws Exception {
        try (StubServer s = server(true)) {
            String trust = TestTls.file("truststore.p12").toString();
            for (String ks : new String[] {"client.p12", "client.jks"}) {
                TlsSettings tls = TlsSettings.none()
                    .withTruststore(trust, PWD).withKeystore(TestTls.file(ks).toString(), PWD);
                assertEquals(200, get(s, tls), ks);
            }
        }
    }

    @Test
    void pemFromRegistryForMutualTls() throws Exception {
        SecretRegistry.put("mtls.cert", TestTls.pem("client-rsa.pem"));
        SecretRegistry.put("mtls.key", TestTls.pem("client-rsa-pkcs8.key"));
        SecretRegistry.put("mtls.ca", TestTls.pem("ca.pem"));
        try (StubServer s = server(true)) {
            assertEquals(200, get(s, TlsSettings.none().withPemMtls("mtls.cert", "mtls.key", "mtls.ca")));
        }
    }

    @Test
    void trustCaFromRegistry() throws Exception {
        SecretRegistry.put("mtls.ca", TestTls.pem("ca.pem"));
        try (StubServer s = server(false)) {
            assertEquals(200, get(s, TlsSettings.none().withPemCa("mtls.ca")));
        }
    }

    @Test
    void ignoreSslErrorsTrustsAnyServerAndWarns() throws Exception {
        try (StubServer s = server(false); LogCapture log = LogCapture.on("io.github.tortaje.rest")) {
            assertEquals(200, get(s, TlsSettings.none().withIgnoreSslErrors(true)));
            assertTrue(log.text().contains("SSL certificate validation is disabled"), log.text());
        }
    }

    @Test
    void clientIsReusedForSameSettings() {
        TlsSettings t = TlsSettings.none();
        HttpClient c = factory.client(t, T);
        assertSame(c, factory.client(t, T));
        assertNotSame(c, factory.client(t, Duration.ofSeconds(6)));
    }

    @Test
    void missingKeystoreFile() {
        assertEquals("Keystore 'nope.p12' not found", assertThrows(RestFixtureException.class,
            () -> TransportFactory.sslContext(TlsSettings.none().withKeystore("nope.p12", SecretValue.of("x")))).text());
    }

    @Test
    void wrongKeystorePassword() {
        String path = TestTls.file("client.p12").toString();
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> TransportFactory
            .sslContext(TlsSettings.none().withKeystore(path, SecretValue.of("bad-pwd"))));
        assertEquals("Unable to load keystore '" + path + "'", e.text());
        assertFalse(e.getMessage().contains("bad-pwd"));
    }

    @Test
    void ignoreWithPemIsRejected() {
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> TransportFactory
            .sslContext(TlsSettings.none().withPemMtls("c", "k", null).withIgnoreSslErrors(true)));
        assertEquals("ignore ssl errors cannot be combined with use mtls", e.text());
    }

    @Test
    void defaultTlsClientIsSharedAcrossInstances() {
        assertSame(new TransportFactory().client(TlsSettings.none(), T),
                new TransportFactory().client(TlsSettings.none(), T));
    }

    @Test
    void rotatedRegistryMaterialBuildsANewClient() {
        SecretRegistry.put("mtls.ca", TestTls.pem("ca.pem"));
        TlsSettings t = TlsSettings.none().withPemCa("mtls.ca");
        HttpClient first = factory.client(t, T);
        assertSame(first, factory.client(t, T));
        SecretRegistry.put("mtls.ca", TestTls.pem("other-ca.pem"));
        assertNotSame(first, factory.client(t, T));
    }

    @Test
    void replacedInstanceClientIsClosed() throws Exception {
        TlsSettings t = TlsSettings.none().withTruststore(TestTls.file("truststore.p12").toString(), PWD);
        HttpClient first = factory.client(t, T);
        factory.client(t, Duration.ofSeconds(6));
        // HttpClient is AutoCloseable from Java 21; the build targets 17, so check through reflection
        java.lang.reflect.Method terminated = HttpClient.class.getMethod("isTerminated");
        assertTrue((Boolean) terminated.invoke(first));
    }
}
