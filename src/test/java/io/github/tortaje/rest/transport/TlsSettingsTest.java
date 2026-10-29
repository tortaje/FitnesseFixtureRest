package io.github.tortaje.rest.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.tortaje.rest.secret.SecretValue;
import org.junit.jupiter.api.Test;

class TlsSettingsTest {
    @Test
    void pemMtlsClearsKeystore() {
        TlsSettings t = TlsSettings.none().withKeystore("a.p12", SecretValue.of("pwd")).withPemMtls("c", "k", null);
        assertNull(t.keystorePath());
        assertNull(t.keystorePassword());
        assertEquals("c", t.pemCertVar());
        assertEquals("k", t.pemKeyVar());
    }

    @Test
    void pemCaClearsOthers() {
        TlsSettings t = TlsSettings.none().withKeystore("a.p12", SecretValue.of("pwd"))
                .withTruststore("t.p12", SecretValue.of("pwd")).withPemCa("ca");
        assertNull(t.keystorePath());
        assertNull(t.keystorePassword());
        assertNull(t.truststorePath());
        assertNull(t.truststorePassword());
        assertNull(t.pemCertVar());
        assertNull(t.pemKeyVar());
        assertEquals("ca", t.pemCaVar());
    }

    @Test
    void fileStoresClearPem() {
        TlsSettings t = TlsSettings.none().withPemCa("ca").withTruststore("t.p12", SecretValue.of("pwd"));
        assertNull(t.pemCaVar());
        assertEquals("t.p12", t.truststorePath());
    }
}
