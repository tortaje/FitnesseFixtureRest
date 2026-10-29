package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.rest.config.ConfigLoader;
import io.github.tortaje.rest.testsupport.StubServer;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestNoHvaultTest {

    private StubServer stub;

    @BeforeEach
    void start() {
        stub = StubServer.http();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private Rest rest() {
        Properties props = new Properties();
        props.setProperty("rest.base_url", stub.url());
        Map<String, String> envVars = new HashMap<>();
        ConfigLoader loader = new ConfigLoader(envVars::get, props, getClass().getClassLoader(), "none/");
        return new Rest(null, loader, d -> { }, Clock.systemUTC());
    }

    @Test
    void pageWithoutSecretsWorks() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.bearerToken("plain");
        r.get("/x");
        assertEquals(200, r.status());
        assertEquals("Bearer plain", stub.requests("/x").get(0).headers().get("authorization"));
    }

    @Test
    void secretReferenceFailsClearly() {
        stub.reply("GET", "/x", 200, "");
        Rest r = rest();
        r.bearerToken("secret:x");
        RestFixtureException e = assertThrows(RestFixtureException.class, () -> r.get("/x"));
        assertEquals("Secret reference used but fitnesse-hvault is not on the !path", e.text());
    }
}
