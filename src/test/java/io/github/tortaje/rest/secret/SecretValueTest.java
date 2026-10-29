package io.github.tortaje.rest.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.RestFixtureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SecretValueTest {

    @AfterEach
    void clear() {
        SecretRegistry.clear();
    }

    @Test
    void literalIsReturnedAsIsAndMasked() {
        SecretValue v = SecretValue.of("s3cr3t");
        assertEquals("s3cr3t", v.reveal());
        assertFalse(v.isReference());
        assertEquals("****", v.toString());
    }

    @Test
    void referenceIsResolvedLateFromRegistry() {
        SecretValue v = SecretValue.of("secret:clientSecret");
        SecretRegistry.put("clientSecret", "v1");
        assertEquals("v1", v.reveal());
        SecretRegistry.put("clientSecret", "v2");
        assertEquals("v2", v.reveal());
        assertEquals("secret:clientSecret", v.toString());
    }

    @Test
    void missingVariableRelaysRegistryMessage() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> SecretValue.of("secret:nope").reveal());
        assertEquals("Registry variable 'nope' missing", e.getMessage());
    }

    @Test
    void emptyReferenceIsRejected() {
        RestFixtureException e =
                assertThrows(RestFixtureException.class, () -> SecretValue.of("secret:").reveal());
        assertEquals("Empty secret reference", e.text());
    }
}
