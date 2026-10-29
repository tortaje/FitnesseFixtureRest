package io.github.tortaje.rest.response;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestResponseTest {
    @Test void headerIsCaseInsensitiveAndJoined() {
        RestResponse r = new RestResponse(200, Map.of("set-cookie", List.of("a=1", "b=2")), "", Duration.ZERO);
        assertEquals("a=1, b=2", r.header("Set-Cookie"));
        assertEquals("", r.header("X-None"));
    }

    @Test void nullHeadersAreEmpty() {
        assertEquals("", new RestResponse(200, null, "", Duration.ZERO).header("X"));
    }
}
