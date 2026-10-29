package io.github.tortaje.rest.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestSpecTest {

    @Test void joinsBaseAndEndpointWithOneSlash() {
        RequestSpec s = new RequestSpec();
        assertEquals("https://h/api/x", s.prepare("GET", "https://h/api/", "/x", Map.of()).uri().toString());
        assertEquals("https://h/api/x", s.prepare("GET", "https://h/api", "x", Map.of()).uri().toString());
        assertEquals("http://other/y", s.prepare("GET", "https://h", "http://other/y", Map.of()).uri().toString());
    }

    @Test void encodesQueryAndAppendsToExistingQuery() {
        RequestSpec s = new RequestSpec();
        s.queryParam("q", "a b&é");
        s.queryParam("q", "2");
        assertEquals("https://h/x?a=1&q=a%20b%26%C3%A9&q=2",
            s.prepare("GET", "https://h", "/x?a=1", Map.of()).uri().toString());
    }

    @Test void wikiHeaderOverridesDefaultIgnoringCase() {
        RequestSpec s = new RequestSpec();
        s.header("accept", SecretValue.of("application/xml"));
        HttpCall c = s.prepare("GET", "https://h", "/x", Map.of("Accept", SecretValue.of("application/json"), "X-App", SecretValue.of("f")));
        assertEquals(Map.of("accept", "application/xml", "X-App", "f"), c.headers());
    }

    @Test void secretReferenceHeadersAreRevealedAndMarkedSensitive() {
        SecretRegistry.put("tok", "T-VALUE");
        try {
            RequestSpec s = new RequestSpec();
            s.header("X-Vault-Token", SecretValue.of("secret:tok"));
            s.header("x-default", SecretValue.of("wiki-literal"));
            HttpCall c = s.prepare("GET", "https://h", "/x",
                Map.of("X-Cfg", SecretValue.of("secret:tok"), "X-Default", SecretValue.of("secret:tok")));
            assertEquals("T-VALUE", c.headers().get("X-Vault-Token"));
            assertEquals("T-VALUE", c.headers().get("X-Cfg"));
            assertEquals("wiki-literal", c.headers().get("x-default"));
            assertEquals(Set.of("X-Vault-Token", "X-Cfg"), c.sensitiveHeaders());
        } finally {
            SecretRegistry.clear();
        }
    }

    @Test void clearPayloadKeepsHeaders() {
        RequestSpec s = new RequestSpec();
        s.header("X-A", SecretValue.of("1"));
        s.body("{}");
        s.queryParam("p", "1");
        s.clearPayload();
        HttpCall c = s.prepare("POST", "https://h", "/x", Map.of());
        assertNull(c.body());
        assertNull(c.uri().getQuery());
        assertEquals("1", c.headers().get("X-A"));
    }

    @Test void resetAlsoClearsHeaders() {
        RequestSpec s = new RequestSpec();
        s.header("X-A", SecretValue.of("1"));
        s.reset();
        assertEquals(Map.of(), s.prepare("GET", "https://h", "/x", Map.of()).headers());
    }

    @Test void missingBaseUrl() {
        assertEquals("No base url: set it with |base url| or base_url",
            assertThrows(RestFixtureException.class, () -> new RequestSpec().prepare("GET", null, "/x", Map.of())).text());
        assertEquals("No base url: set it with |base url| or base_url",
            assertThrows(RestFixtureException.class, () -> new RequestSpec().prepare("GET", " ", "/x", Map.of())).text());
    }

    @Test void invalidUrlHidesQuery() {
        assertEquals("Invalid URL for endpoint '/a b'",
            assertThrows(RestFixtureException.class,
                () -> new RequestSpec().prepare("GET", "https://h", "/a b?k=secret", Map.of())).text());
    }

    @Test void urlWithoutUsableHostIsRejectedWithoutQuery() {
        for (String base : new String[] {"http://my_svc:8080", "localhost:8080", "http:/api.example.com"}) {
            RequestSpec s = new RequestSpec();
            s.queryParam("api_key", "K-SECRET");
            RestFixtureException e = assertThrows(RestFixtureException.class,
                () -> s.prepare("GET", base, "/x?a=1", Map.of()), base);
            assertEquals("Invalid URL for endpoint '/x'", e.text(), base);
        }
    }

    @Test void absoluteEndpointWithUnderscoreHostIsRejected() {
        assertEquals("Invalid URL for endpoint 'http://my_svc/x'",
            assertThrows(RestFixtureException.class,
                () -> new RequestSpec().prepare("GET", null, "http://my_svc/x?k=secret", Map.of())).text());
    }

    @Test void queryParamsGoBeforeFragment() {
        RequestSpec s = new RequestSpec();
        s.queryParam("k", "v");
        assertEquals("https://h/x?k=v#top", s.prepare("GET", "https://h", "/x#top", Map.of()).uri().toString());
        assertEquals("https://h/x?a=1&k=v#f?z", s.prepare("GET", "https://h", "/x?a=1#f?z", Map.of()).uri().toString());
        assertEquals("https://h/x?k=v#a?b", s.prepare("GET", "https://h", "/x#a?b", Map.of()).uri().toString());
    }

    @Test void doesNotDoubleEncode() {
        assertEquals("https://h/a%20b",
            new RequestSpec().prepare("GET", "https://h", "/a%20b", Map.of()).uri().toString());
    }

    @Test
    void invalidUrlMessageHidesUserinfo() {
        RestFixtureException e = assertThrows(RestFixtureException.class,
            () -> new RequestSpec().prepare("GET", null, "https://svc:P4ssw0rd@api.example.com:80a/x?k=v", Map.of()));
        assertEquals("Invalid URL for endpoint 'https://api.example.com:80a/x'", e.text());
    }
}
