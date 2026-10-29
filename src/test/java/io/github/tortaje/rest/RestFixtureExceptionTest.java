package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RestFixtureExceptionTest {
    @Test void messageUsesSlimFormat() {
        RestFixtureException e = new RestFixtureException("boom");
        assertEquals("message:<<boom>>", e.getMessage());
        assertEquals("boom", e.text());
    }

    @Test void keepsCause() {
        Throwable cause = new IllegalStateException("x");
        RestFixtureException e = new RestFixtureException("boom", cause);
        assertEquals("message:<<boom>>", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
