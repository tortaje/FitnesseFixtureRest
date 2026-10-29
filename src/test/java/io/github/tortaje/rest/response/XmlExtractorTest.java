package io.github.tortaje.rest.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tortaje.rest.RestFixtureException;
import org.junit.jupiter.api.Test;

class XmlExtractorTest {
    @Test void xpathIgnoresNamespaces() {
        String iso = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><GrpHdr><MsgId>M1</MsgId></GrpHdr></Document>";
        assertEquals("M1", XmlExtractor.extract(iso, "/Document/GrpHdr/MsgId"));
    }

    @Test void xpathRejectsDoctype() {
        String xxe = "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>";
        assertEquals("Response body is not valid XML", assertThrows(RestFixtureException.class, () -> XmlExtractor.extract(xxe, "/a")).text());
    }

    @Test void xpathNotFound() {
        assertEquals("XPath '/a/b' not found in response", assertThrows(RestFixtureException.class, () -> XmlExtractor.extract("<a/>", "/a/b")).text());
    }

    @Test void xpathInvalidAndEmpty() {
        assertEquals("Invalid XPath '/a['", assertThrows(RestFixtureException.class, () -> XmlExtractor.extract("<a/>", "/a[")).text());
        assertEquals("Response body is empty", assertThrows(RestFixtureException.class, () -> XmlExtractor.extract(" ", "/a")).text());
    }
}
