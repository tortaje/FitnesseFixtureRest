package io.github.tortaje.rest.transport;

import io.github.tortaje.rest.secret.SecretValue;

/** TLS configuration: file keystores/truststores or PEM material held in Vault-backed variables. */
public record TlsSettings(boolean ignoreSslErrors, String keystorePath, SecretValue keystorePassword,
        String truststorePath, SecretValue truststorePassword, String pemCertVar, String pemKeyVar,
        String pemCaVar) {

    public static TlsSettings none() {
        return new TlsSettings(false, null, null, null, null, null, null, null);
    }

    public TlsSettings withIgnoreSslErrors(boolean ignore) {
        return new TlsSettings(ignore, keystorePath, keystorePassword, truststorePath, truststorePassword,
                pemCertVar, pemKeyVar, pemCaVar);
    }

    public TlsSettings withKeystore(String path, SecretValue password) {
        return new TlsSettings(ignoreSslErrors, path, password, truststorePath, truststorePassword,
                null, null, null);
    }

    public TlsSettings withTruststore(String path, SecretValue password) {
        return new TlsSettings(ignoreSslErrors, keystorePath, keystorePassword, path, password,
                null, null, null);
    }

    public TlsSettings withPemMtls(String cert, String key, String caOrNull) {
        return new TlsSettings(ignoreSslErrors, null, null, null, null, cert, key, caOrNull);
    }

    public TlsSettings withPemCa(String ca) {
        return new TlsSettings(ignoreSslErrors, null, null, null, null, null, null, ca);
    }
}
