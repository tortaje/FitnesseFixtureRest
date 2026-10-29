package io.github.tortaje.rest.testsupport;

import java.util.concurrent.CountDownLatch;

/**
 * Provisions a running Vault and serves the RestVaultSuite stub endpoints on fixed ports until killed, so the
 * suite can be run by hand from the FitNesse UI (see scripts/fitnesse-ui.sh).
 *
 * <p>Arguments: {@code <vaultAddr> <vaultToken> <httpPort> <tlsPort>}.
 */
public final class ManualStubs {

    private ManualStubs() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: ManualStubs <vaultAddr> <vaultToken> <httpPort> <tlsPort>");
        }
        VaultScenario.provision(args[0], args[1]);
        StubServer api = StubServer.http(Integer.parseInt(args[2]));
        StubServer tlsApi = StubServer.https(TestTls.server(true), true, Integer.parseInt(args[3]));
        VaultScenario.script(api, tlsApi);
        api.onRequest(ManualStubs::log);
        tlsApi.onRequest(ManualStubs::log);
        System.out.println("Vault provisioned at " + args[0]);
        System.out.println("API stub  " + api.url());
        System.out.println("mTLS stub " + tlsApi.url());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            tlsApi.close();
        }));
        new CountDownLatch(1).await();
    }

    /** One line per request, with the W3C trace context it carried (to match the fixture's traces in Tempo). */
    private static void log(StubServer.Recorded r) {
        String traceparent = r.headers().getOrDefault("traceparent", "-");
        System.out.println(r.method() + " " + r.path() + " traceparent=" + traceparent);
        System.out.flush();
    }
}
