package io.github.tortaje.rest.config;

import io.github.tortaje.rest.RestFixtureException;

/** Strict parsing of configuration values. */
public final class Values {
    private Values() {
    }

    public static int positiveInt(String key, String raw) {
        int v = parseInt(key, raw);
        if (v <= 0) {
            throw invalid(key, raw);
        }
        return v;
    }

    public static long positiveLong(String key, String raw) {
        long v;
        try {
            v = Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            throw invalid(key, raw);
        }
        if (v <= 0) {
            throw invalid(key, raw);
        }
        return v;
    }

    public static int nonNegativeInt(String key, String raw) {
        int v = parseInt(key, raw);
        if (v < 0) {
            throw invalid(key, raw);
        }
        return v;
    }

    public static boolean bool(String key, String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw invalid(key, raw);
    }

    private static int parseInt(String key, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            throw invalid(key, raw);
        }
    }

    private static RestFixtureException invalid(String key, String raw) {
        return new RestFixtureException("Invalid value '" + raw + "' for " + key);
    }
}
