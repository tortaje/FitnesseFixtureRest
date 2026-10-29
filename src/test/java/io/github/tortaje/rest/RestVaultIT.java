package io.github.tortaje.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import fitnesse.junit.FitNesseRunner;
import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.testsupport.StubServer;
import io.github.tortaje.rest.testsupport.TestTls;
import io.github.tortaje.rest.testsupport.VaultScenario;
import java.net.URI;
import java.util.List;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.testcontainers.vault.VaultContainer;

/**
 * Runs the RestVaultSuite wiki pages in-process against a real Vault (Testcontainers) and stub endpoints: the
 * HVault fixture reads the secrets, the Rest fixture uses them through {@code secret:} references. Needs Docker;
 * runs with {@code mvn verify -Pit}.
 */
@RunWith(RestVaultIT.VaultRunner.class)
@FitNesseRunner.Suite("RestVaultSuite")
@FitNesseRunner.FitnesseDir("src/test/fitnesse")
@FitNesseRunner.OutputDir("target/fitnesse-results")
@FitNesseRunner.PreventSystemExit(false)
public class RestVaultIT {

    private static final String ROOT_TOKEN = "it-root-token";

    /** Starts Vault and the stubs around the wiki run and reports any failure to JUnit. */
    public static class VaultRunner extends FitNesseRunner {

        public VaultRunner(Class<?> suiteClass) throws InitializationError {
            super(suiteClass);
        }

        @Override
        public void run(RunNotifier notifier) {
            try {
                start();
                super.run(notifier);
                verify();
            } catch (AssertionError | Exception e) {
                notifier.fireTestFailure(new Failure(getDescription(), e));
            } finally {
                stop();
            }
        }
    }

    private static VaultContainer<?> vault;
    private static StubServer api;
    private static StubServer tlsApi;

    static void start() throws Exception {
        vault = new VaultContainer<>("hashicorp/vault:1.18.3").withVaultToken(ROOT_TOKEN);
        vault.start();
        VaultScenario.provision(vault.getHttpHostAddress(), ROOT_TOKEN);
        api = StubServer.http();
        tlsApi = StubServer.https(TestTls.server(true));
        VaultScenario.script(api, tlsApi);

        System.setProperty("hvault.addr", vault.getHttpHostAddress());
        System.setProperty("hvault.token", ROOT_TOKEN);
        System.setProperty("rest.base_url", api.url());
        System.setProperty("rest.token_url", api.url() + "/token");
        System.setProperty("rest.tls_port", String.valueOf(URI.create(tlsApi.url()).getPort()));
    }

    /** Checks that the values stored in Vault are the ones that reached the endpoints. */
    static void verify() {
        List<StubServer.Recorded> tokens = api.requests("/token");
        assertEquals("one token per Rest instance plus one 401 refresh", 4, tokens.size());
        for (StubServer.Recorded t : tokens) {
            assertTrue(t.body().contains("client_id=" + VaultScenario.CLIENT_ID));
            assertTrue(t.body().contains("client_secret=" + VaultScenario.CLIENT_SECRET));
        }
        assertEquals("Bearer T1", api.requests("/orders").get(0).headerValues("authorization").get(0));
        assertTrue(api.requests("/orders").get(0).body().contains("\"sku\": \"A1\""));
        StubServer.Recorded iso = api.requests("/payments/iso").get(0);
        assertEquals("application/xml", iso.headerValues("content-type").get(0));
        assertTrue(iso.body().contains("<EndToEndId>E2E-42</EndToEndId>"));
        assertEquals(2, api.requests("/flaky").size());
        assertEquals(VaultScenario.API_KEY, api.requests("/flaky").get(1).headerValues("x-api-key").get(0));
        assertEquals(2, api.requests("/protected").size());
        assertEquals(1, tlsApi.requests("/secure").size());
        assertTrue("SuiteTearDown must clear the registry", !SecretRegistry.contains("clientSecret"));
    }

    static void stop() {
        for (String p : new String[] {"hvault.addr", "hvault.token", "rest.base_url", "rest.token_url",
                "rest.tls_port"}) {
            System.clearProperty(p);
        }
        SecretRegistry.clear();
        if (api != null) {
            api.close();
            api = null;
        }
        if (tlsApi != null) {
            tlsApi.close();
            tlsApi = null;
        }
        if (vault != null) {
            vault.stop();
            vault = null;
        }
    }
}
