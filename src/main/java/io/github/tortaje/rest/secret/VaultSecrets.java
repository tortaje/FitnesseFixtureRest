package io.github.tortaje.rest.secret;

import javax.net.ssl.SSLContext;

/** The only class of the REST fixture that references fitnesse-hvault, which is optional at runtime. */
public final class VaultSecrets {

    private static final String REGISTRY = "io.github.tortaje.hvault.registry.SecretRegistry";

    private VaultSecrets() {
    }

    public static boolean available() {
        try {
            Class.forName(REGISTRY, false, VaultSecrets.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static String get(String variable) {
        requireAvailable();
        return io.github.tortaje.hvault.registry.SecretRegistry.get(variable);
    }

    /** Mutual-TLS context from registry PEM values; {@code caVar} may be null (JVM truststore). */
    public static SSLContext mtls(String certVar, String keyVar, String caVar) {
        requireAvailable();
        return io.github.tortaje.hvault.registry.MtlsContexts.fromRegistry(certVar, keyVar, caVar);
    }

    /** TLS context trusting only the PEM CA stored in the registry variable {@code caVar}. */
    public static SSLContext trustingOnly(String caVar) {
        requireAvailable();
        return io.github.tortaje.hvault.registry.MtlsContexts.trustingOnly(get(caVar));
    }

    private static void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException("Secret reference used but fitnesse-hvault is not on the !path");
        }
    }
}
