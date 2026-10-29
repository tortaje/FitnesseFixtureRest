package io.github.tortaje.rest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.transport.TlsSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
    ConfigLoader loader(Map<String, String> env, Properties sys) {
        return new ConfigLoader(env::get, sys, getClass().getClassLoader(), "config-test/");
    }

    @Test
    void defaultsWithoutAnyFile() {
        RestSettings s = new ConfigLoader(n -> null, new Properties(), getClass().getClassLoader(), "none/").load(null);
        assertEquals(Duration.ofSeconds(30), s.timeout());
        assertEquals(0, s.maxRetries());
        assertEquals(Duration.ofMillis(500), s.retryDelay());
        assertNull(s.baseUrl());
        assertEquals(TlsSettings.none(), s.tls());
    }

    @Test
    void envFileOverridesCommonFile() {
        RestSettings s = loader(Map.of(), new Properties()).load("uat");
        assertEquals("https://uat.example.com", s.baseUrl());
        assertEquals(Duration.ofSeconds(10), s.timeout());
        assertEquals("secret:clientSecret", s.clientSecret().toString());
        assertEquals(Map.of("x-app", SecretValue.of("uat"), "Accept", SecretValue.of("application/json")),
                s.defaultHeaders());
    }

    @Test
    void precedenceSystemPropertyThenEnvVariable() {
        Properties sys = new Properties();
        sys.setProperty("rest.base_url", "https://sys.example.com");
        assertEquals("https://sys.example.com",
                loader(Map.of("REST_BASE_URL", "https://env.example.com"), sys).load("uat").baseUrl());
        assertEquals("https://env.example.com",
                loader(Map.of("REST_BASE_URL", "https://env.example.com"), new Properties()).load("uat").baseUrl());
    }

    @Test
    void missingEnvironmentFile() {
        RestFixtureException e = assertThrows(RestFixtureException.class,
                () -> loader(Map.of(), new Properties()).load("prod"));
        assertEquals("Configuration file 'fitnesse-rest-prod.properties' not found on the classpath", e.text());
    }

    @Test
    void invalidNumber() {
        Properties sys = new Properties();
        sys.setProperty("rest.timeout_seconds", "abc");
        assertEquals("Invalid value 'abc' for timeout_seconds",
                assertThrows(RestFixtureException.class, () -> loader(Map.of(), sys).load(null)).text());
    }

    @Test
    void environmentNameFromArgumentThenRestEnv() {
        assertEquals("uat", loader(Map.of("REST_ENV", "int"), new Properties()).environmentName("uat"));
        assertEquals("int", loader(Map.of("REST_ENV", "int"), new Properties()).environmentName(""));
        assertNull(loader(Map.of(), new Properties()).environmentName(null));
    }

    @Test
    void tlsAndScopeKeysAndSystemHeader() {
        Properties sys = new Properties();
        sys.setProperty("rest.ignore_ssl_errors", "true");
        sys.setProperty("rest.keystore_path", "k.p12");
        sys.setProperty("rest.keystore_password", "pw");
        sys.setProperty("rest.default_scope", "read");
        sys.setProperty("rest.default_headers.ACCEPT", "text/plain");
        RestSettings s = loader(Map.of(), sys).load("uat");
        assertEquals(true, s.tls().ignoreSslErrors());
        assertEquals("k.p12", s.tls().keystorePath());
        assertEquals("pw", s.tls().keystorePassword().reveal());
        assertEquals("read", s.scope());
        assertEquals(SecretValue.of("text/plain"), s.defaultHeaders().get("ACCEPT"));
        assertEquals(2, s.defaultHeaders().size());
    }

    @Test
    void defaultHeaderSecretReferenceIsKeptUnresolved() {
        Properties sys = new Properties();
        sys.setProperty("rest.default_headers.X-Token", "secret:hdr");
        SecretValue v = loader(Map.of(), sys).load(null).defaultHeaders().get("X-Token");
        assertEquals(SecretValue.of("secret:hdr"), v);
        assertTrue(v.isReference());
    }

    @Test
    void maxResponseBytesDefaultsTo16MiBAndIsConfigurable() {
        assertEquals(16L * 1024 * 1024,
            new ConfigLoader(n -> null, new Properties(), getClass().getClassLoader(), "none/").load(null).maxResponseBytes());
        Properties sys = new Properties();
        sys.setProperty("rest.max_response_bytes", "1024");
        assertEquals(1024L, new ConfigLoader(n -> null, sys, getClass().getClassLoader(), "none/").load(null).maxResponseBytes());
        sys.setProperty("rest.max_response_bytes", "0");
        assertEquals("Invalid value '0' for max_response_bytes", assertThrows(RestFixtureException.class,
            () -> new ConfigLoader(n -> null, sys, getClass().getClassLoader(), "none/").load(null)).text());
    }
}
