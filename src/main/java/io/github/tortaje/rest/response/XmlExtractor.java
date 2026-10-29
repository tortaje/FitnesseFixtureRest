package io.github.tortaje.rest.response;

import io.github.tortaje.rest.RestFixtureException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Evaluates an XPath (namespace-unaware) against a response body and returns the first node's text. */
public final class XmlExtractor {

    /** Builders and XPath objects are not thread-safe; factories are costly to look up: one of each per thread. */
    private static final ThreadLocal<DocumentBuilder> BUILDER = ThreadLocal.withInitial(XmlExtractor::newBuilder);
    private static final ThreadLocal<XPath> XPATH = ThreadLocal.withInitial(XmlExtractor::newXPath);

    private XmlExtractor() {
    }

    public static String extract(String body, String xpath) {
        return extract(parse(body), xpath);
    }

    /** Parses an XML body once (DTDs, external entities and XInclude disabled). */
    public static Document parse(String body) {
        if (body == null || body.isBlank()) {
            throw new RestFixtureException("Response body is empty");
        }
        DocumentBuilder builder = BUILDER.get();
        builder.reset();
        builder.setErrorHandler(null); // default handler prints to stderr; parse errors still throw
        try {
            return builder.parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            throw new RestFixtureException("Response body is not valid XML", e);
        }
    }

    /** Evaluates {@code xpath} against a document returned by {@link #parse(String)}. */
    public static String extract(Document doc, String xpath) {
        XPath evaluator = XPATH.get();
        evaluator.reset();
        NodeList nodes;
        try {
            nodes = (NodeList) evaluator.evaluate(xpath, doc, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new RestFixtureException("Invalid XPath '" + xpath + "'", e);
        }
        if (nodes.getLength() == 0) {
            throw new RestFixtureException("XPath '" + xpath + "' not found in response");
        }
        return nodes.item(0).getTextContent();
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser configuration failed", e);
        }
    }

    private static XPath newXPath() {
        try {
            XPathFactory xf = XPathFactory.newInstance();
            xf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return xf.newXPath();
        } catch (XPathFactoryConfigurationException e) {
            throw new IllegalStateException("XPath configuration failed", e);
        }
    }
}
