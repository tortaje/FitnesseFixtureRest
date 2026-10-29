package io.github.tortaje.rest;

import io.github.tortaje.rest.auth.ApiKeyHeader;
import io.github.tortaje.rest.auth.ApiKeyParam;
import io.github.tortaje.rest.auth.Authenticator;
import io.github.tortaje.rest.auth.BasicAuth;
import io.github.tortaje.rest.auth.BearerAuth;
import io.github.tortaje.rest.auth.ClientCredentials;
import io.github.tortaje.rest.auth.CredentialScope;
import io.github.tortaje.rest.auth.NoAuth;
import io.github.tortaje.rest.config.ConfigLoader;
import io.github.tortaje.rest.config.RestSettings;
import io.github.tortaje.rest.config.Values;
import io.github.tortaje.rest.exec.Executor;
import io.github.tortaje.rest.exec.HttpTransport;
import io.github.tortaje.rest.exec.Sleeper;
import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.request.RequestSpec;
import io.github.tortaje.rest.response.ParsedBody;
import io.github.tortaje.rest.response.PayloadView;
import io.github.tortaje.rest.response.RestResponse;
import io.github.tortaje.rest.secret.SecretValue;
import io.github.tortaje.rest.transport.TransportFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FitNesse Slim script fixture for REST calls. */
public class Rest {

    private static final Logger LOG = Logger.getLogger("io.github.tortaje.rest");

    private final Sleeper sleeper;
    private final Clock clock;
    private final TransportFactory transports = new TransportFactory();
    private final RequestSpec spec = new RequestSpec();

    private RestSettings settings;
    private Supplier<Authenticator> authFactory;
    private Authenticator auth;
    private HttpTransport currentTransport;
    private RestResponse lastResponse;
    private ParsedBody parsedBody;
    private final Set<String> cleartextWarned = new HashSet<>();

    public Rest() {
        this(null);
    }

    public Rest(String env) {
        this(env, ConfigLoader.system(), Sleeper.real(), Clock.systemUTC());
    }

    Rest(String env, ConfigLoader loader, Sleeper sleeper, Clock clock) {
        this.sleeper = sleeper;
        this.clock = clock;
        this.settings = loader.load(loader.environmentName(env));
        this.authFactory = this::defaultAuth;
    }

    private static final Pattern AUTO_LINK = Pattern.compile(
            "<a\\s[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Turns FitNesse's automatic link markup of a URL cell, {@code <a href="X">X</a>}, back into {@code X} with its
     * HTML entities decoded. Any other value is returned trimmed and otherwise unchanged.
     */
    static String cellUrl(String cell) {
        if (cell == null) {
            return null;
        }
        String trimmed = cell.trim();
        Matcher m = AUTO_LINK.matcher(trimmed);
        if (m.matches() && m.group(2).trim().equals(m.group(3).trim())) {
            return unescapeHtml(m.group(2).trim());
        }
        return trimmed;
    }

    /** Slim leaves HTML-looking cells escaped: decode the entities FitNesse writes into a link, {@code &amp;} last. */
    private static String unescapeHtml(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    // target

    public void baseUrl(String url) {
        settings = settings.withBaseUrl(cellUrl(url));
    }

    public void timeout(String seconds) {
        settings = settings.withTimeout(Duration.ofSeconds(Values.positiveInt("timeout_seconds", seconds)));
    }

    public void maxRetries(String count) {
        settings = settings.withMaxRetries(Values.nonNegativeInt("max_retries", count));
    }

    public void retryDelay(String millis) {
        settings = settings.withRetryDelay(Duration.ofMillis(Values.nonNegativeInt("retry_delay_ms", millis)));
    }

    public void ignoreSslErrors(String flag) {
        settings = settings.withTls(settings.tls().withIgnoreSslErrors(Values.bool("ignore_ssl_errors", flag)));
    }

    // TLS

    public void keystore(String path, String password) {
        requireFile(path, "Keystore");
        settings = settings.withTls(settings.tls().withKeystore(path, SecretValue.of(password)));
    }

    public void truststore(String path, String password) {
        requireFile(path, "Truststore");
        settings = settings.withTls(settings.tls().withTruststore(path, SecretValue.of(password)));
    }

    public void useMtls(String cert, String key, String ca) {
        settings = settings.withTls(settings.tls().withPemMtls(cert, key, ca == null || ca.isBlank() ? null : ca));
    }

    public void trustCa(String ca) {
        settings = settings.withTls(settings.tls().withPemCa(ca));
    }

    private static void requireFile(String path, String kind) {
        if (!Files.exists(Path.of(path))) {
            throw new RestFixtureException(kind + " '" + path + "' not found");
        }
    }

    // auth

    public void basicAuth(String user, String password) {
        useAuth(() -> new BasicAuth(SecretValue.of(user), SecretValue.of(password)));
    }

    public void bearerToken(String token) {
        useAuth(() -> new BearerAuth(SecretValue.of(token)));
    }

    public void apiKeyHeader(String name, String value) {
        useAuth(() -> new ApiKeyHeader(name, SecretValue.of(value)));
    }

    public void apiKeyParam(String name, String value) {
        useAuth(() -> new ApiKeyParam(name, SecretValue.of(value)));
    }

    public void clientCredentials(String id, String secret) {
        settings = settings.withClientCredentials(SecretValue.of(id), SecretValue.of(secret));
        useAuth(this::clientCredentialsAuth);
    }

    public void tokenUrl(String url) {
        settings = settings.withTokenUrl(cellUrl(url));
        useAuth(authFactory);
    }

    public void scope(String scope) {
        settings = settings.withScope(scope);
        useAuth(authFactory);
    }

    public void noAuth() {
        useAuth(() -> NoAuth.INSTANCE);
    }

    private void useAuth(Supplier<Authenticator> factory) {
        authFactory = factory;
        auth = null;
    }

    private Authenticator defaultAuth() {
        return settings.clientId() != null && settings.clientSecret() != null
                ? clientCredentialsAuth() : NoAuth.INSTANCE;
    }

    private Authenticator clientCredentialsAuth() {
        return new ClientCredentials(settings.tokenUrl(), settings.clientId(), settings.clientSecret(),
                settings.scope(), () -> currentTransport, clock);
    }

    // request

    public void header(String name, String value) {
        spec.header(name, SecretValue.of(value));
    }

    public void queryParam(String name, String value) {
        spec.queryParam(name, value);
    }

    public void body(String body) {
        spec.body(body);
    }

    public void bodyFromFile(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new RestFixtureException("Body file '" + path + "' not found");
        }
        try {
            spec.body(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RestFixtureException("Cannot read body file '" + path + "'", e);
        }
    }

    public void reset() {
        spec.reset();
    }

    // actions

    public void get(String endpoint) {
        send("GET", endpoint);
    }

    public void post(String endpoint) {
        send("POST", endpoint);
    }

    public void put(String endpoint) {
        send("PUT", endpoint);
    }

    public void patch(String endpoint) {
        send("PATCH", endpoint);
    }

    public void delete(String endpoint) {
        send("DELETE", endpoint);
    }

    private void send(String method, String endpoint) {
        lastResponse = null;
        try {
            HttpCall call = spec.prepare(method, settings.baseUrl(), cellUrl(endpoint), settings.defaultHeaders());
            if (auth == null) {
                auth = authFactory.get();
            }
            Authenticator scoped = scopedAuth(call);
            currentTransport = new HttpTransport(transports.client(settings.tls(), settings.timeout()),
                    settings.timeout(), scoped.sensitiveHeaders(), settings.maxResponseBytes());
            lastResponse = new Executor(currentTransport, sleeper)
                    .execute(call, scoped, settings.maxRetries(), settings.retryDelay());
            parsedBody = new ParsedBody(lastResponse);
        } catch (IllegalStateException e) {
            throw new RestFixtureException(e.getMessage(), e);
        } finally {
            spec.clearPayload();
        }
    }

    /**
     * The authenticator for this call: none when the target is not the base URL's origin (a credential never leaves
     * for another host), with a WARN; the configured one otherwise, with a WARN once per origin over plain http.
     */
    private Authenticator scopedAuth(HttpCall call) {
        if (auth == NoAuth.INSTANCE) {
            return auth;
        }
        String origin = CredentialScope.origin(call.uri());
        if (!CredentialScope.allows(settings.baseUrl(), call.uri())) {
            LOG.warning("Credentials not sent to " + origin + ": not the base url origin");
            return NoAuth.INSTANCE;
        }
        if (CredentialScope.cleartext(call.uri()) && cleartextWarned.add(origin)) {
            LOG.warning("Credentials sent over plain http to " + origin);
        }
        return auth;
    }

    // response

    public int status() {
        return response().status();
    }

    public String body() {
        return response().body();
    }

    /** The payload as a collapsible block (summary: status, content type, size), indented when JSON or XML. */
    public String prettyBody() {
        return PayloadView.html(response());
    }

    public boolean responseContains(String text) {
        String body = response().body();
        return body != null && body.contains(text);
    }

    public String responseHeader(String name) {
        return response().header(name);
    }

    public String json(String path) {
        response();
        return parsedBody.json(path);
    }

    public String xpath(String path) {
        response();
        return parsedBody.xpath(path);
    }

    private RestResponse response() {
        if (lastResponse == null) {
            throw new RestFixtureException("No response yet: send a request first");
        }
        return lastResponse;
    }
}
