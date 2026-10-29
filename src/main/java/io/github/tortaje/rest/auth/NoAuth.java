package io.github.tortaje.rest.auth;

import io.github.tortaje.rest.request.HttpCall;

/** Leaves the call unchanged. */
public enum NoAuth implements Authenticator {
    INSTANCE;

    @Override
    public HttpCall apply(HttpCall call) {
        return call;
    }
}
