package io.github.tortaje.rest.secret;

import io.github.tortaje.rest.RestFixtureException;
import java.util.Objects;

/** A literal or a {@code secret:<variable>} reference, resolved from the hvault registry only when revealed. */
public final class SecretValue {

    private static final String PREFIX = "secret:";

    private final String raw;

    private SecretValue(String raw) {
        this.raw = raw;
    }

    public static SecretValue of(String raw) {
        return raw == null ? null : new SecretValue(raw);
    }

    public boolean isReference() {
        return raw.startsWith(PREFIX);
    }

    /** The secret text; never put it in a message, log or wiki cell. */
    public String reveal() {
        if (!isReference()) {
            return raw;
        }
        String variable = raw.substring(PREFIX.length());
        if (variable.isEmpty()) {
            throw new RestFixtureException("Empty secret reference");
        }
        return VaultSecrets.get(variable);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SecretValue other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }

    @Override
    public String toString() {
        return isReference() ? raw : "****";
    }
}
