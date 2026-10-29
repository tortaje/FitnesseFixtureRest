package io.github.tortaje.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import fitnesse.junit.FitNesseRunner;
import io.github.tortaje.hvault.registry.SecretRegistry;
import io.github.tortaje.rest.testsupport.StubServer;
import io.github.tortaje.rest.testsupport.TestTls;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.runner.notification.Failure;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

/**
 * Runs the RestSuite wiki pages in-process (FitNesse debug mode) against a stub server. FitNesseRunner overrides
 * run(), so JUnit's @BeforeClass/@AfterClass are never invoked; the stub lifecycle wraps run() instead.
 */
@RunWith(FitnesseSuiteTest.StubbedRunner.class)
@FitNesseRunner.Suite("RestSuite")
@FitNesseRunner.FitnesseDir("src/test/fitnesse")
@FitNesseRunner.OutputDir("target/fitnesse-results")
@FitNesseRunner.PreventSystemExit(false)
public class FitnesseSuiteTest {

    private static final String PACS_008 = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">"
            + "<GrpHdr><MsgId>M1</MsgId></GrpHdr></Document>";

    /** Starts the stubs and test configuration around the wiki run and reports any failure to JUnit. */
    public static class StubbedRunner extends FitNesseRunner {

        public StubbedRunner(Class<?> suiteClass) throws InitializationError {
            super(suiteClass);
        }

        @Override
        public void run(RunNotifier notifier) {
            try {
                startStub();
                super.run(notifier);
                verifyStubs();
            } catch (AssertionError | Exception e) {
                notifier.fireTestFailure(new Failure(getDescription(), e));
            } finally {
                stopStub();
            }
        }
    }

    private static StubServer stub;
    private static StubServer tlsStub;

    static void startStub() {
        stub = StubServer.http();
        stub.reply("POST", "/token", 200, "{\"access_token\":\"A1\",\"expires_in\":3600}");
        stub.reply("POST", "/payments", 201, "{\"id\":\"p-1\"}");
        stub.reply("GET", "/payments/p-1", 200, "{\"status\":\"ACCEPTED\"}");
        stub.reply("GET", "/iso", 200, PACS_008);
        for (String path : new String[] {"/basic", "/apikey-header", "/apikey-param", "/query", "/plain"}) {
            stub.reply("GET", path, 200, "ok");
        }
        tlsStub = StubServer.https(TestTls.server(true));
        tlsStub.reply("GET", "/secure", 200, "ok");
        System.setProperty("rest.base_url", stub.url());
        System.setProperty("rest.token_url", stub.url() + "/token");
        System.setProperty("rest.tls_port", String.valueOf(java.net.URI.create(tlsStub.url()).getPort()));
        System.setProperty("rest.http_port", String.valueOf(java.net.URI.create(stub.url()).getPort()));
        SecretRegistry.put("clientSecret", "e2e");
        SecretRegistry.put("mtls.cert", TestTls.pem("client-rsa.pem"));
        SecretRegistry.put("mtls.key", TestTls.pem("client-rsa-pkcs8.key"));
        SecretRegistry.put("mtls.ca", TestTls.pem("ca.pem"));
    }

    static void verifyStubs() {
        assertEquals(1, stub.requests("/token").size());
        String basic = "Basic " + Base64.getEncoder().encodeToString("user:pwd".getBytes(StandardCharsets.UTF_8));
        assertEquals(basic, stub.requests("/basic").get(0).headerValues("authorization").get(0));
        assertEquals("k1", stub.requests("/apikey-header").get(0).headerValues("x-api-key").get(0));
        assertTrue(stub.requests("/apikey-param").get(0).query().contains("api_key=k2"));
        StubServer.Recorded query = stub.requests("/query").get(0);
        assertTrue(query.query().contains("q=v1"));
        assertEquals("t1", query.headerValues("x-trace").get(0));
        assertEquals(2, stub.requests("/plain").size());
        assertEquals(null, stub.requests("/plain").get(0).query());
        assertEquals("a=1&b=2", stub.requests("/plain").get(1).query());
        assertEquals(2, tlsStub.requests("/secure").size());
    }

    static void stopStub() {
        System.clearProperty("rest.base_url");
        System.clearProperty("rest.token_url");
        System.clearProperty("rest.tls_port");
        System.clearProperty("rest.http_port");
        SecretRegistry.clear();
        if (stub != null) {
            stub.close();
            stub = null;
        }
        if (tlsStub != null) {
            tlsStub.close();
            tlsStub = null;
        }
    }
}
