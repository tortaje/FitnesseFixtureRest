package io.github.tortaje.rest.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Secrets and stub replies of the RestVaultSuite pages, shared by RestVaultIT and the manual FitNesse launcher. */
public final class VaultScenario {

    public static final String CLIENT_ID = "rest-it-client";
    public static final String CLIENT_SECRET = "vaultClientSecret42";
    public static final String API_KEY = "vault-api-key-7";

    private static final String ORDER = "{\"id\":\"o-42\",\"status\":\"CREATED\","
            + "\"customer\":{\"name\":\"ACME\",\"country\":\"FR\"},"
            + "\"lines\":[{\"sku\":\"A1\",\"qty\":2},{\"sku\":\"B7\",\"qty\":1}],"
            + "\"total\":{\"amount\":129.5,\"currency\":\"EUR\"}}";
    private static final String PACS_002 = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10\">"
            + "<FIToFIPmtStsRpt><GrpHdr><MsgId>STS-001</MsgId></GrpHdr>"
            + "<TxInfAndSts><OrgnlEndToEndId>E2E-42</OrgnlEndToEndId><TxSts>ACSC</TxSts></TxInfAndSts>"
            + "</FIToFIPmtStsRpt></Document>";

    private VaultScenario() {
    }

    /** Writes the OAuth client, the API key and the mTLS PEMs under {@code secret/fitnesse/*} (KV v2). */
    public static void provision(String vaultAddr, String token) throws Exception {
        write(vaultAddr, token, "fitnesse/oauth",
                "\"client_id\":" + json(CLIENT_ID) + ",\"client_secret\":" + json(CLIENT_SECRET));
        write(vaultAddr, token, "fitnesse/api", "\"key\":" + json(API_KEY));
        write(vaultAddr, token, "fitnesse/mtls", "\"cert\":" + json(TestTls.pem("client-rsa.pem"))
                + ",\"key\":" + json(TestTls.pem("client-rsa-pkcs8.key"))
                + ",\"ca\":" + json(TestTls.pem("ca.pem")));
    }

    /** Scripts the IdP, JSON, XML and resilience endpoints on {@code api} and the mTLS endpoint on {@code tlsApi}. */
    public static void script(StubServer api, StubServer tlsApi) {
        api.reply("POST", "/token", 200, "{\"access_token\":\"T1\",\"expires_in\":3600}");
        api.reply("POST", "/orders", 201, "{\"id\":\"o-42\",\"status\":\"CREATED\"}",
                "Content-Type", "application/json");
        api.reply("GET", "/orders/o-42", 200, ORDER, "Content-Type", "application/json");
        api.reply("PUT", "/orders/o-42", 200, "{\"id\":\"o-42\",\"status\":\"UPDATED\"}",
                "Content-Type", "application/json");
        api.reply("DELETE", "/orders/o-42", 204, "");
        api.reply("POST", "/payments/iso", 201, PACS_002, "Content-Type", "application/xml");
        api.reply("GET", "/flaky", 503, "busy");
        api.reply("GET", "/flaky", 200, "{\"ok\":true}");
        api.reply("GET", "/protected", 401, "");
        api.reply("GET", "/protected", 200, "{\"ok\":true}");
        tlsApi.reply("GET", "/secure", 200, "{\"mtls\":\"ok\"}");
    }

    private static void write(String vaultAddr, String token, String path, String fields) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(vaultAddr + "/v1/secret/data/" + path))
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"data\":{" + fields + "}}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Vault write " + path + " -> HTTP " + response.statusCode());
        }
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")
                + "\"";
    }
}
