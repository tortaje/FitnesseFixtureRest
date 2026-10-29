package io.github.tortaje.rest.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadViewTest {

    private static RestResponse response(int status, String contentType, String body, boolean truncated) {
        Map<String, List<String>> headers = contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType));
        return new RestResponse(status, headers, body, Duration.ZERO, truncated);
    }

    @Test
    void collapsibleBlockThatFitNesseRendersAsHtml() {
        String html = PayloadView.html(response(201, "application/json; charset=UTF-8", "{\"id\":1}", false));
        assertTrue(html.startsWith("<div class=\"rest-payload\"><details><summary>"), html);
        assertTrue(html.endsWith("</pre></details></div>"), html);
        assertTrue(html.contains("<summary>201 · application/json · 8 B</summary>"), html);
    }

    @Test
    void jsonIsIndentedAndStringsAreKept() {
        String body = "{\"a\":{\"b\":[1,2]},\"s\":\"x{,}:\\\"y\",\"e\":{},\"l\":[]}";
        assertEquals("{\n"
                + "  \"a\": {\n"
                + "    \"b\": [\n"
                + "      1,\n"
                + "      2\n"
                + "    ]\n"
                + "  },\n"
                + "  \"s\": \"x{,}:\\\"y\",\n"
                + "  \"e\": {},\n"
                + "  \"l\": []\n"
                + "}", PayloadView.pretty(body, "application/json"));
    }

    @Test
    void xmlIsIndented() {
        String body = "<Document><GrpHdr><MsgId>M1</MsgId></GrpHdr>\n   <Sts>ACSC</Sts></Document>";
        assertEquals("<Document>\n"
                + "  <GrpHdr>\n"
                + "    <MsgId>M1</MsgId>\n"
                + "  </GrpHdr>\n"
                + "  <Sts>ACSC</Sts>\n"
                + "</Document>", PayloadView.pretty(body, "application/xml"));
    }

    @Test
    void formatIsGuessedWithoutContentType() {
        assertEquals("[\n  1\n]", PayloadView.pretty("[1]", ""));
        assertEquals("<a>\n  <b>x</b>\n</a>", PayloadView.pretty("<a><b>x</b></a>", ""));
        assertEquals("plain text", PayloadView.pretty("plain text", "text/plain"));
    }

    @Test
    void invalidPayloadIsShownRaw() {
        assertEquals("{\"a\":", PayloadView.pretty("{\"a\":", "application/json"));
        assertEquals("<a><b></a>", PayloadView.pretty("<a><b></a>", "application/xml"));
    }

    @Test
    void contentIsHtmlEscaped() {
        String html = PayloadView.html(response(200, "text/html", "<script>alert('x')</script> & \"q\"", false));
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;q&quot;"), html);
    }

    @Test
    void xmlTagsStayVisible() {
        String html = PayloadView.html(response(201, "application/xml", "<Sts>ACSC</Sts>", false));
        assertTrue(html.contains("&lt;Sts&gt;ACSC&lt;/Sts&gt;"), html);
    }

    @Test
    void summaryShowsSizeMissingTypeAndTruncation() {
        assertTrue(PayloadView.html(response(200, null, "x".repeat(1536), false)).contains("<summary>200 · 1.5 KB</summary>"));
        assertTrue(PayloadView.html(response(204, null, "", false)).contains("<summary>204 · 0 B</summary>"));
        assertTrue(PayloadView.html(response(200, "text/plain", "abc", true))
                .contains("<summary>200 · text/plain · 3 B · truncated</summary>"));
    }

    @Test
    void longPayloadIsCutForDisplay() {
        String html = PayloadView.html(response(200, "text/plain", "y".repeat(PayloadView.MAX_SHOWN + 10), false));
        assertTrue(html.contains("y".repeat(PayloadView.MAX_SHOWN) + "\n… 10 more characters</pre>"), html);
    }
}
