package io.github.tortaje.rest.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Scriptable HTTP(S) stub server for tests. Unscripted paths answer 404. */
public final class StubServer implements AutoCloseable {

    public record Recorded(String method, String path, String query, Map<String, String> headers, String body,
            Map<String, List<String>> allHeaders) {

        /** Every received value of the header (case-insensitive name). */
        public List<String> headerValues(String name) {
            return allHeaders.getOrDefault(name.toLowerCase(), List.of());
        }
    }

    private record Reply(int status, byte[] body, String[] headerPairs) {
    }

    private final HttpServer server;
    private final String scheme;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, List<Reply>> replies = new ConcurrentHashMap<>();
    private final Map<String, Integer> served = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private volatile Consumer<Recorded> listener = r -> { };

    private StubServer(HttpServer server, String scheme) {
        this.server = server;
        this.scheme = scheme;
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static StubServer http() {
        return http(0);
    }

    /** HTTP stub on a fixed port (0 = any free port). */
    public static StubServer http(int port) {
        try {
            return new StubServer(HttpServer.create(loopback(port), 0), "http");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static StubServer https(SSLContext serverContext) {
        return https(serverContext, false);
    }

    public static StubServer https(SSLContext serverContext, boolean needClientAuth) {
        return https(serverContext, needClientAuth, 0);
    }

    /** HTTPS stub on a fixed port (0 = any free port). */
    public static StubServer https(SSLContext serverContext, boolean needClientAuth, int port) {
        try {
            HttpsServer s = HttpsServer.create(loopback(port), 0);
            s.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters p = getSSLContext().getDefaultSSLParameters();
                    p.setNeedClientAuth(needClientAuth);
                    params.setSSLParameters(p);
                }
            });
            return new StubServer(s, "https");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InetSocketAddress loopback(int port) throws IOException {
        return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
    }

    public String url() {
        return scheme + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queues a reply; the last reply of a path repeats. */
    public void reply(String method, String path, int status, String body, String... headerPairs) {
        replies.computeIfAbsent(key(method, path), k -> new CopyOnWriteArrayList<>())
            .add(new Reply(status, body.getBytes(StandardCharsets.UTF_8), headerPairs));
    }

    /** Called with every request received, after it is recorded. */
    public void onRequest(Consumer<Recorded> listener) {
        this.listener = listener;
    }

    /** Queues a reply with a raw body (e.g. not UTF-8). */
    public void replyBytes(String method, String path, int status, byte[] body, String... headerPairs) {
        replies.computeIfAbsent(key(method, path), k -> new CopyOnWriteArrayList<>())
            .add(new Reply(status, body, headerPairs));
    }

    public void delay(String path, Duration d) {
        delays.put(path, d);
    }

    public List<Recorded> requests(String path) {
        List<Recorded> out = new ArrayList<>();
        for (Recorded r : recorded) {
            if (r.path().equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Map<String, String> headers = new TreeMap<>();
        Map<String, List<String>> all = new TreeMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            headers.put(k.toLowerCase(), v.get(0));
            all.put(k.toLowerCase(), new ArrayList<>(v));
        });
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Recorded request = new Recorded(method, path, ex.getRequestURI().getRawQuery(), headers, body, all);
        recorded.add(request);
        listener.accept(request);

        Duration delay = delays.get(path);
        if (delay != null) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.close();
                return;
            }
        }
        Reply reply = next(key(method, path));
        byte[] out = reply.body();
        String[] h = reply.headerPairs();
        for (int i = 0; i + 1 < h.length; i += 2) {
            ex.getResponseHeaders().add(h[i], h[i + 1]);
        }
        try {
            ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                ex.getResponseBody().write(out);
            }
        } catch (IOException ignored) {
            // client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    private Reply next(String key) {
        List<Reply> list = replies.get(key);
        if (list == null || list.isEmpty()) {
            return new Reply(404, new byte[0], new String[0]);
        }
        int i = served.merge(key, 1, Integer::sum) - 1;
        return list.get(Math.min(i, list.size() - 1));
    }
}
