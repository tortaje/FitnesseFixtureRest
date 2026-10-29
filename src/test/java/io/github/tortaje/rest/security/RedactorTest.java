package io.github.tortaje.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedactorTest {
    @Test void safeUrlDropsQueryUserinfoFragment() {
        assertEquals("https://api.example.com:8443/orders/42",
            Redactor.safeUrl(URI.create("https://u:p@api.example.com:8443/orders/42?api_key=x#f")));
        assertEquals("api.example.com/payments", Redactor.hostAndPath(URI.create("https://api.example.com/payments?a=1")));
    }

    @Test void redactsSensitiveHeadersCaseInsensitively() {
        Map<String,String> in = new LinkedHashMap<>();
        in.put("authorization", "Bearer t"); in.put("X-Api-Key", "k"); in.put("Accept", "application/json");
        assertEquals(List.of("****", "****", "application/json"),
            List.copyOf(Redactor.redactHeaders(in, Set.of("x-api-key")).values()));
    }
}
