package io.github.tortaje.rest.transport;

import io.github.tortaje.rest.RestFixtureException;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.secret.VaultSecrets;
import java.io.File;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Builds {@link HttpClient}s and {@link SSLContext}s from {@link TlsSettings}. */
public final class TransportFactory {

    private static final Logger LOG = Logger.getLogger("io.github.tortaje.rest");

    /** Clients with the JVM's default TLS hold no secret: one per connect timeout, shared by every page. */
    private static final Map<Duration, HttpClient> SHARED = new ConcurrentHashMap<>();

    /**
     * A client with fixture-specific TLS material. The fingerprint is a SHA-256 over the revealed material, kept only
     * in this instance, so a rotated registry PEM or a replaced keystore file builds a new client.
     */
    private record Key(TlsSettings tls, Duration connectTimeout, String fingerprint) {
    }

    private Key lastKey;
    private HttpClient lastClient;

    /**
     * The shared client for default TLS; otherwise this instance's client while settings and material are unchanged.
     * A replaced instance client is closed (Java 21+).
     */
    public synchronized HttpClient client(TlsSettings tls, Duration connectTimeout) {
        if (TlsSettings.none().equals(tls)) {
            return SHARED.computeIfAbsent(connectTimeout, t -> build(tls, t));
        }
        Key key = new Key(tls, connectTimeout, fingerprint(tls));
        if (key.equals(lastKey)) {
            return lastClient;
        }
        HttpClient client = build(tls, connectTimeout);
        close(lastClient);
        lastKey = key;
        lastClient = client;
        return client;
    }

    private static HttpClient build(TlsSettings tls, Duration connectTimeout) {
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .sslContext(sslContext(tls))
            .build();
        if (tls.ignoreSslErrors()) {
            LOG.log(Level.WARNING, "SSL certificate validation is disabled");
        }
        return client;
    }

    /** HttpClient implements AutoCloseable from Java 21; on 17 the client is left to the garbage collector. */
    private static void close(HttpClient client) {
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Closing the previous HTTP client failed", e);
            }
        }
    }

    private static String fingerprint(TlsSettings tls) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            feed(digest, registry(tls.pemCertVar()));
            feed(digest, registry(tls.pemKeyVar()));
            feed(digest, registry(tls.pemCaVar()));
            feed(digest, file(tls.keystorePath()));
            feed(digest, tls.keystorePassword() == null ? null : tls.keystorePassword().reveal());
            feed(digest, file(tls.truststorePath()));
            feed(digest, tls.truststorePassword() == null ? null : tls.truststorePassword().reveal());
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String registry(String variable) {
        return variable == null || variable.isEmpty() ? null : VaultSecrets.get(variable);
    }

    private static String file(String path) {
        if (path == null) {
            return null;
        }
        File f = new File(path);
        return path + "|" + f.lastModified() + "|" + f.length();
    }

    private static void feed(MessageDigest digest, String part) {
        digest.update((part == null ? "\u0000" : part).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
    }

    public static SSLContext sslContext(TlsSettings tls) {
        boolean pem = tls.pemCertVar() != null && !tls.pemCertVar().isEmpty();
        if (tls.ignoreSslErrors() && pem) {
            throw new RestFixtureException("ignore ssl errors cannot be combined with use mtls");
        }
        try {
            if (pem) {
                String ca = tls.pemCaVar() == null || tls.pemCaVar().isEmpty() ? null : tls.pemCaVar();
                return VaultSecrets.mtls(tls.pemCertVar(), tls.pemKeyVar(), ca);
            }
            if (!tls.ignoreSslErrors() && tls.pemCaVar() != null && !tls.pemCaVar().isEmpty()) {
                return VaultSecrets.trustingOnly(tls.pemCaVar());
            }
            boolean hasKeystore = tls.keystorePath() != null;
            boolean hasTruststore = tls.truststorePath() != null;
            if (!tls.ignoreSslErrors() && !hasKeystore && !hasTruststore) {
                return SSLContext.getDefault();
            }
            KeyManager[] keyManagers = null;
            if (hasKeystore) {
                KeyStore ks = load(tls.keystorePath(), tls.keystorePassword(), "Keystore", "keystore");
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, password(tls.keystorePassword()));
                keyManagers = kmf.getKeyManagers();
            }
            TrustManager[] trustManagers = null;
            if (tls.ignoreSslErrors()) {
                trustManagers = new TrustManager[] {new TrustAll()};
            } else if (hasTruststore) {
                KeyStore ts = load(tls.truststorePath(), tls.truststorePassword(), "Truststore", "truststore");
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                trustManagers = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, trustManagers, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new RestFixtureException("Unable to build the TLS context", e);
        }
    }

    private static char[] password(SecretValue p) {
        return p == null ? new char[0] : p.reveal().toCharArray();
    }

    private static KeyStore load(String path, SecretValue password, String capitalKind, String kind) {
        File file = new File(path);
        if (!file.isFile()) {
            throw new RestFixtureException(capitalKind + " '" + path + "' not found");
        }
        try {
            return KeyStore.getInstance(file, password(password));
        } catch (Exception e) {
            throw new RestFixtureException("Unable to load " + kind + " '" + path + "'", e);
        }
    }

    /** Accepts every certificate and host name; only used when ignore ssl errors is requested. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
