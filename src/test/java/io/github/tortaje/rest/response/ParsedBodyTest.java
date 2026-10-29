package io.github.tortaje.rest.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tortaje.rest.RestFixtureException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParsedBodyTest {

    @Test
    void jsonIsParsedOnceForSeveralChecks() throws Exception {
        ParsedBody p = new ParsedBody(new RestResponse(200, Map.of(), "{\"a\":1,\"b\":{\"c\":\"x\"}}", Duration.ZERO));
        assertEquals("1", p.json("$.a"));
        java.lang.reflect.Field doc = ParsedBody.class.getDeclaredField("json");
        doc.setAccessible(true);
        Object first = doc.get(p);
        assertEquals("x", p.json("$.b.c"));
        assertSame(first, doc.get(p));
    }

    @Test
    void xmlIsParsedOnceForSeveralChecks() throws Exception {
        ParsedBody p = new ParsedBody(new RestResponse(200, Map.of(), "<a><b>1</b><c>2</c></a>", Duration.ZERO));
        assertEquals("1", p.xpath("/a/b"));
        java.lang.reflect.Field doc = ParsedBody.class.getDeclaredField("xml");
        doc.setAccessible(true);
        Object first = doc.get(p);
        assertEquals("2", p.xpath("/a/c"));
        assertSame(first, doc.get(p));
    }

    @Test
    void truncatedBodyIsReportedInsteadOfParsed() {
        ParsedBody p = new ParsedBody(new RestResponse(200, Map.of(), "{\"a\":", Duration.ZERO, true));
        assertEquals("Response body is truncated: raise max_response_bytes to extract from it",
                assertThrows(RestFixtureException.class, () -> p.json("$.a")).text());
        assertEquals("Response body is truncated: raise max_response_bytes to extract from it",
                assertThrows(RestFixtureException.class, () -> p.xpath("/a")).text());
    }

    @Test
    void jsonLiteralNullIsNotFound() {
        ParsedBody p = new ParsedBody(new RestResponse(200, Map.of(), "null", Duration.ZERO));
        assertEquals("JsonPath '$.a' not found in response",
                assertThrows(RestFixtureException.class, () -> p.json("$.a")).text());
    }
}
