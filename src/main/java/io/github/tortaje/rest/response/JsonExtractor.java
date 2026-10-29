package io.github.tortaje.rest.response;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import io.github.tortaje.rest.RestFixtureException;
import java.util.List;
import java.util.Map;

/** Evaluates a JsonPath against a response body and renders the result as text. */
public final class JsonExtractor {
    private static final Configuration CONFIG = Configuration.defaultConfiguration();

    private JsonExtractor() {
    }

    public static String extract(String body, String jsonPath) {
        return extract(parse(body), jsonPath);
    }

    /** Parses a JSON body once; {@code null} is the JSON literal null. */
    public static Object parse(String body) {
        if (body == null || body.isBlank()) {
            throw new RestFixtureException("Response body is empty");
        }
        Object doc;
        try {
            doc = CONFIG.jsonProvider().parse(body);
        } catch (InvalidJsonException e) {
            throw notJson(e);
        }
        // the permissive parser turns arbitrary text (e.g. XML) into a String: only a quoted string is JSON
        if (doc instanceof String && !body.stripLeading().startsWith("\"")) {
            throw notJson(null);
        }
        return doc;
    }

    /** Evaluates {@code jsonPath} against a document returned by {@link #parse(String)}. */
    public static String extract(Object doc, String jsonPath) {
        JsonPath path;
        try {
            path = JsonPath.compile(jsonPath);
        } catch (InvalidPathException e) {
            throw new RestFixtureException("Invalid JsonPath '" + jsonPath + "'", e);
        }
        if (doc == null) {
            // the body is the JSON literal null: nothing can match
            throw notFound(jsonPath);
        }
        Object result;
        try {
            result = path.read(doc, CONFIG);
        } catch (PathNotFoundException e) {
            throw notFound(jsonPath);
        } catch (InvalidPathException e) {
            throw new RestFixtureException("Invalid JsonPath '" + jsonPath + "'", e);
        }
        if (result instanceof List<?> list && list.isEmpty() && !path.isDefinite()) {
            throw notFound(jsonPath);
        }
        if (result == null) {
            return "null";
        }
        if (result instanceof Map || result instanceof List) {
            return CONFIG.jsonProvider().toJson(result);
        }
        return String.valueOf(result);
    }

    private static RestFixtureException notFound(String jsonPath) {
        return new RestFixtureException("JsonPath '" + jsonPath + "' not found in response");
    }

    private static RestFixtureException notJson(Exception cause) {
        return new RestFixtureException("Response body is not valid JSON", cause);
    }
}
