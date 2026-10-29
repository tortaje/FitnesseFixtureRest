package io.github.tortaje.rest.response;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Renders a response payload for a FitNesse cell: a collapsible block whose summary shows status, content type and
 * size, and whose body is the payload indented (JSON, XML) and HTML-escaped.
 */
public final class PayloadView {

    /** Characters shown at most; the rest is announced, not rendered, to keep pages light. */
    static final int MAX_SHOWN = 64 * 1024;

    private PayloadView() {
    }

    /**
     * The cell HTML. FitNesse renders a returned value as HTML only when it starts with one of its known tags,
     * which {@code details} is not: the block is wrapped in a {@code div}.
     */
    public static String html(RestResponse response) {
        String body = response.body() == null ? "" : response.body();
        String type = contentType(response);
        String shown = pretty(body, type);
        if (shown.length() > MAX_SHOWN) {
            shown = shown.substring(0, MAX_SHOWN) + "\n… " + (shown.length() - MAX_SHOWN) + " more characters";
        }
        return "<div class=\"rest-payload\"><details><summary>" + escape(summary(response, type, body))
                + "</summary><pre>" + escape(shown) + "</pre></details></div>";
    }

    /** The payload indented when it is valid JSON or XML, unchanged otherwise. */
    static String pretty(String body, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String start = body.stripLeading();
        boolean json = type.contains("json") || (!type.contains("xml") && (start.startsWith("{") || start.startsWith("[")));
        boolean xml = !json && (type.contains("xml") || start.startsWith("<"));
        try {
            if (json) {
                JsonExtractor.parse(body);
                return indentJson(body);
            }
            if (xml) {
                return indentXml(XmlExtractor.parse(body));
            }
        } catch (RuntimeException e) {
            return body; // display only: an invalid payload is shown as received
        }
        return body;
    }

    private static String summary(RestResponse response, String type, String body) {
        StringBuilder s = new StringBuilder().append(response.status());
        if (!type.isEmpty()) {
            s.append(" · ").append(type);
        }
        s.append(" · ").append(size(body.getBytes(StandardCharsets.UTF_8).length));
        if (response.truncated()) {
            s.append(" · truncated");
        }
        return s.toString();
    }

    private static String contentType(RestResponse response) {
        String header = response.header("Content-Type");
        int semicolon = header.indexOf(';');
        return (semicolon >= 0 ? header.substring(0, semicolon) : header).trim();
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Re-indents valid JSON text by two spaces, leaving strings (and their escapes) untouched. */
    private static String indentJson(String json) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    out.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    int next = nextNonSpace(json, i + 1);
                    char close = c == '{' ? '}' : ']';
                    if (next < json.length() && json.charAt(next) == close) {
                        out.append(c).append(close);
                        i = next;
                    } else {
                        out.append(c);
                        newline(out, ++depth);
                    }
                }
                case '}', ']' -> {
                    newline(out, --depth);
                    out.append(c);
                }
                case ',' -> {
                    out.append(c);
                    newline(out, depth);
                }
                case ':' -> out.append(": ");
                default -> {
                    if (!Character.isWhitespace(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static int nextNonSpace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n').append("  ".repeat(Math.max(depth, 0)));
    }

    private static String indentXml(Document doc) {
        removeBlankText(doc);
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = factory.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString().strip();
        } catch (Exception e) {
            throw new IllegalStateException("XML formatting failed", e);
        }
    }

    /** Whitespace-only text nodes from the original layout would otherwise become blank lines. */
    private static void removeBlankText(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else {
                removeBlankText(child);
            }
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
