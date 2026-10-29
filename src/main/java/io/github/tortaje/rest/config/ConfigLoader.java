package io.github.tortaje.rest.config;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.transport.TlsSettings;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Loads {@link RestSettings} from system properties, environment variables and classpath property files. */
public final class ConfigLoader {
    private static final String HEADER_PREFIX = "default_headers.";

    private final EnvSource env;
    private final Properties systemProperties;
    private final ClassLoader loader;
    private final String prefix;

    public ConfigLoader(EnvSource env, Properties systemProperties, ClassLoader loader, String resourcePrefix) {
        this.env = env;
        this.systemProperties = systemProperties;
        this.loader = loader;
        this.prefix = resourcePrefix;
    }

    public static ConfigLoader system() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return new ConfigLoader(EnvSource.system(), System.getProperties(),
                cl != null ? cl : ConfigLoader.class.getClassLoader(), "");
    }

    /** The argument if non-blank, else {@code REST_ENV}, else null. */
    public String environmentName(String constructorArg) {
        if (constructorArg != null && !constructorArg.isBlank()) {
            return constructorArg.trim();
        }
        String fromEnv = env.get("REST_ENV");
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
    }

    public RestSettings load(String envName) {
        Properties common = readFile("fitnesse-rest.properties", false);
        Properties envFile = envName == null ? new Properties()
                : readFile("fitnesse-rest-" + envName + ".properties", true);

        String timeout = scalar("timeout_seconds", envFile, common);
        String retries = scalar("max_retries", envFile, common);
        String delay = scalar("retry_delay_ms", envFile, common);
        String ignore = scalar("ignore_ssl_errors", envFile, common);
        String maxBody = scalar("max_response_bytes", envFile, common);

        TlsSettings tls = TlsSettings.none()
                .withIgnoreSslErrors(ignore != null && Values.bool("ignore_ssl_errors", ignore))
                .withKeystore(scalar("keystore_path", envFile, common),
                        SecretValue.of(scalar("keystore_password", envFile, common)))
                .withTruststore(scalar("truststore_path", envFile, common),
                        SecretValue.of(scalar("truststore_password", envFile, common)));

        return new RestSettings(
                scalar("base_url", envFile, common),
                Duration.ofSeconds(timeout == null ? 30 : Values.positiveInt("timeout_seconds", timeout)),
                retries == null ? 0 : Values.nonNegativeInt("max_retries", retries),
                Duration.ofMillis(delay == null ? 500 : Values.nonNegativeInt("retry_delay_ms", delay)),
                tls,
                scalar("token_url", envFile, common),
                SecretValue.of(scalar("client_id", envFile, common)),
                SecretValue.of(scalar("client_secret", envFile, common)),
                scalar("default_scope", envFile, common),
                headers(common, envFile),
                maxBody == null ? RestSettings.DEFAULT_MAX_RESPONSE_BYTES
                        : Values.positiveLong("max_response_bytes", maxBody));
    }

    private String scalar(String key, Properties envFile, Properties common) {
        String v = nonBlank(systemProperties.getProperty("rest." + key));
        if (v == null) {
            v = nonBlank(env.get("REST_" + key.toUpperCase(java.util.Locale.ROOT)));
        }
        if (v == null) {
            v = nonBlank(envFile.getProperty(key));
        }
        if (v == null) {
            v = nonBlank(common.getProperty(key));
        }
        return v;
    }

    private static String nonBlank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private Map<String, SecretValue> headers(Properties common, Properties envFile) {
        Map<String, SecretValue> result = new LinkedHashMap<>();
        applyHeaders(result, common, HEADER_PREFIX);
        applyHeaders(result, envFile, HEADER_PREFIX);
        applyHeaders(result, systemProperties, "rest." + HEADER_PREFIX);
        return result;
    }

    private static void applyHeaders(Map<String, SecretValue> target, Properties source, String keyPrefix) {
        List<String> keys = new ArrayList<>(source.stringPropertyNames());
        Collections.sort(keys);
        for (String key : keys) {
            if (!key.startsWith(keyPrefix) || key.length() == keyPrefix.length()) {
                continue;
            }
            String name = key.substring(keyPrefix.length());
            target.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            target.put(name, SecretValue.of(source.getProperty(key)));
        }
    }

    private Properties readFile(String fileName, boolean required) {
        Properties props = new Properties();
        try (InputStream in = loader.getResourceAsStream(prefix + fileName)) {
            if (in == null) {
                if (required) {
                    throw new RestFixtureException(
                            "Configuration file '" + fileName + "' not found on the classpath");
                }
                return props;
            }
            props.load(in);
            return props;
        } catch (IllegalArgumentException e) {
            throw new RestFixtureException("Invalid configuration file '" + fileName + "'", e);
        } catch (IOException e) {
            throw new RestFixtureException("Cannot read configuration file '" + fileName + "'", e);
        }
    }
}
