package io.github.tortaje.rest.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tortaje.rest.RestFixtureException;
import org.junit.jupiter.api.Test;

class JsonExtractorTest {
    @Test void jsonScalarsObjectsAndNull() {
        String b = "{\"id\":42,\"ok\":true,\"amt\":1.5,\"s\":\"ACCEPTED\",\"o\":{\"a\":1},\"l\":[1,2],\"n\":null}";
        assertEquals("42", JsonExtractor.extract(b, "$.id"));
        assertEquals("true", JsonExtractor.extract(b, "$.ok"));
        assertEquals("1.5", JsonExtractor.extract(b, "$.amt"));
        assertEquals("ACCEPTED", JsonExtractor.extract(b, "$.s"));
        assertEquals("{\"a\":1}", JsonExtractor.extract(b, "$.o"));
        assertEquals("[1,2]", JsonExtractor.extract(b, "$.l"));
        assertEquals("null", JsonExtractor.extract(b, "$.n"));
    }

    @Test void jsonErrors() {
        assertEquals("JsonPath '$.x' not found in response", assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("{}", "$.x")).text());
        assertEquals("JsonPath '$.l[?(@ > 5)]' not found in response", assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("{\"l\":[1]}", "$.l[?(@ > 5)]")).text());
        assertEquals("Response body is not valid JSON", assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("<a/>", "$.x")).text());
        assertEquals("Response body is empty", assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("", "$.x")).text());
        assertEquals("Invalid JsonPath '$['", assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("{}", "$[")).text());
    }

    @Test void jsonNullDocumentIsNotFound() {
        assertEquals("JsonPath '$.a' not found in response",
            assertThrows(RestFixtureException.class, () -> JsonExtractor.extract("null", "$.a")).text());
        assertEquals("JsonPath '$' not found in response",
            assertThrows(RestFixtureException.class, () -> JsonExtractor.extract(" null ", "$")).text());
    }
}
