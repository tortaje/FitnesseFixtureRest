package io.github.tortaje.rest.response;

import io.github.tortaje.rest.RestFixtureException;
import org.w3c.dom.Document;

/** A response whose body is parsed at most once as JSON and once as XML, whatever the number of checks. */
public final class ParsedBody {

    private final RestResponse response;
    private boolean jsonParsed;
    private Object json;
    private Document xml;

    public ParsedBody(RestResponse response) {
        this.response = response;
    }

    public String json(String jsonPath) {
        if (!jsonParsed) {
            requireComplete();
            json = JsonExtractor.parse(response.body());
            jsonParsed = true;
        }
        return JsonExtractor.extract(json, jsonPath);
    }

    public String xpath(String xpath) {
        if (xml == null) {
            requireComplete();
            xml = XmlExtractor.parse(response.body());
        }
        return XmlExtractor.extract(xml, xpath);
    }

    /** A truncated body would fail to parse with a misleading message: say what happened instead. */
    private void requireComplete() {
        if (response.truncated()) {
            throw new RestFixtureException("Response body is truncated: raise max_response_bytes to extract from it");
        }
    }
}
