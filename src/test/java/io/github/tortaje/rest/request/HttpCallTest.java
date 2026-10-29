package io.github.tortaje.rest.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpCallTest {

    @Test void withQueryParamUsesQuestionMarkThenAmpersand() {
        HttpCall c = new HttpCall("GET", URI.create("https://h/x"), Map.of(), null)
            .withQueryParam("a", "1").withQueryParam("b", "2");
        assertEquals("https://h/x?a=1&b=2", c.uri().toString());
    }

    @Test void withQueryParamEncodes() {
        HttpCall c = new HttpCall("GET", URI.create("https://h/x"), Map.of(), null).withQueryParam("a b", "c&d");
        assertEquals("https://h/x?a%20b=c%26d", c.uri().toString());
    }

    @Test void withHeaderReplacesIgnoringCase() {
        HttpCall c = new HttpCall("GET", URI.create("https://h/x"), Map.of("Authorization", "old"), null)
            .withHeader("authorization", "x").withHeader("X-B", "y");
        assertEquals(Map.of("authorization", "x", "X-B", "y"), c.headers());
    }

    @Test void headersAreUnmodifiable() {
        HttpCall c = new HttpCall("GET", URI.create("https://h/x"), null, null);
        assertEquals(Map.of(), c.headers());
        assertThrows(UnsupportedOperationException.class, () -> c.headers().put("a", "b"));
    }

    @Test void sensitiveHeadersSurviveWithHeaderAndWithQueryParam() {
        HttpCall c = new HttpCall("GET", URI.create("https://h/x"), Map.of("X-T", "v"), null, Set.of("X-T"))
            .withHeader("Authorization", "a").withQueryParam("k", "v");
        assertEquals(Set.of("X-T"), c.sensitiveHeaders());
        assertEquals(Set.of(), new HttpCall("GET", URI.create("https://h/x"), Map.of(), null).sensitiveHeaders());
    }

    @Test void withQueryParamGoesBeforeFragment() {
        assertEquals("https://h/x?k=v#top", new HttpCall("GET", URI.create("https://h/x#top"), Map.of(), null)
            .withQueryParam("k", "v").uri().toString());
        assertEquals("https://h/x?a=1&k=v#f?z", new HttpCall("GET", URI.create("https://h/x?a=1#f?z"), Map.of(), null)
            .withQueryParam("k", "v").uri().toString());
    }
}
