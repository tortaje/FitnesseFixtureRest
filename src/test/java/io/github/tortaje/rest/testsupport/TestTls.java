package io.github.tortaje.rest.testsupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Test TLS material from {@code src/test/resources/pem}. All keystores use the password {@code changeit}. */
public final class TestTls {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private TestTls() {
    }

    /** Server context from server.p12; trusts the test CA so it accepts client certificates signed by it. */
    public static SSLContext server(boolean requireClientCert) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(file("server.p12"))) {
                ks.load(in, PASSWORD);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASSWORD);

            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream in = new ByteArrayInputStream(pem("ca.pem").getBytes(StandardCharsets.UTF_8))) {
                trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(String file) {
        try {
            return Files.readString(file(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path file(String name) {
        return Paths.get("src", "test", "resources", "pem", name).toAbsolutePath();
    }
}
