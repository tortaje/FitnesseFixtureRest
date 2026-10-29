package io.github.tortaje.rest.exec;

import io.github.tortaje.rest.request.HttpCall;
import io.github.tortaje.rest.response.RestResponse;
import java.io.IOException;
import java.time.Duration;

/** Sends one prepared HTTP call. {@code business} marks the real call (vs. an auth/token call). */
public interface Transport {

    RestResponse send(HttpCall call, int resendCount, boolean business) throws IOException, InterruptedException;

    Duration timeout();
}
