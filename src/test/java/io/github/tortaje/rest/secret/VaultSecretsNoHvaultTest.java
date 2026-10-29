package io.github.tortaje.rest.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VaultSecretsNoHvaultTest {

    @Test
    void unavailableWithoutJar() {
        assertFalse(VaultSecrets.available());
    }

    @Test
    void referenceFailsWithPathMessage() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> SecretValue.of("secret:x").reveal());
        assertEquals("Secret reference used but fitnesse-hvault is not on the !path", e.getMessage());
    }

    @Test
    void literalWorksWithoutJar() {
        assertEquals("plain", SecretValue.of("plain").reveal());
    }
}
