package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;
import java.util.Set;

/** Adds credentials to a prepared call. Secrets are revealed inside {@link #apply}, never stored. */
public interface Authenticator {

    HttpCall apply(HttpCall call);

    default boolean canRefresh() {
        return false;
    }

    default void refresh() {
    }

    /** Header names whose values must be masked in reports and messages. */
    default Set<String> sensitiveHeaders() {
        return Set.of();
    }
}
