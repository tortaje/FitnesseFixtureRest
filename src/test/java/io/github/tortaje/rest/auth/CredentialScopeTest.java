package io.github.tortaje.rest.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CredentialScopeTest {

    @Test
    void sameOriginIsAllowedIgnoringCaseAndDefaultPort() {
        assertTrue(CredentialScope.allows("https://API.example.com", URI.create("https://api.example.com:443/x?a=1")));
        assertTrue(CredentialScope.allows("http://h:8080/base/", URI.create("http://h:8080/other")));
    }

    @Test
    void otherHostSchemeOrPortIsRefused() {
        assertFalse(CredentialScope.allows("https://api.example.com", URI.create("https://evil.example.com/x")));
        assertFalse(CredentialScope.allows("https://api.example.com", URI.create("http://api.example.com/x")));
        assertFalse(CredentialScope.allows("https://api.example.com", URI.create("https://api.example.com:8443/x")));
    }

    @Test
    void noBaseUrlAllowsAndBadBaseUrlRefuses() {
        assertTrue(CredentialScope.allows(null, URI.create("https://any.example.com/x")));
        assertTrue(CredentialScope.allows(" ", URI.create("https://any.example.com/x")));
        assertFalse(CredentialScope.allows("http://bad host", URI.create("https://any.example.com/x")));
    }

    @Test
    void cleartextOnlyForHttpToNonLoopback() {
        assertTrue(CredentialScope.cleartext(URI.create("http://api.example.com/x")));
        assertFalse(CredentialScope.cleartext(URI.create("https://api.example.com/x")));
        assertFalse(CredentialScope.cleartext(URI.create("http://127.0.0.1:9081/x")));
        assertFalse(CredentialScope.cleartext(URI.create("http://localhost/x")));
        assertFalse(CredentialScope.cleartext(URI.create("http://[::1]:80/x")));
    }

    @Test
    void originHasNoUserinfoPathOrQuery() {
        assertEquals("https://h:443", CredentialScope.origin(URI.create("https://u:p@h/x?k=v")));
    }
}
