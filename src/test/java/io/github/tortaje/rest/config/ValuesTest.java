package io.github.tortaje.rest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tortaje.rest.RestFixtureException;
import org.junit.jupiter.api.Test;

class ValuesTest {
    @Test
    void positiveIntRejectsZero() {
        assertEquals("Invalid value '0' for timeout_seconds",
                assertThrows(RestFixtureException.class, () -> Values.positiveInt("timeout_seconds", "0")).text());
        assertEquals(5, Values.positiveInt("timeout_seconds", "5"));
    }

    @Test
    void nonNegativeIntRejectsNegative() {
        assertEquals("Invalid value '-1' for max_retries",
                assertThrows(RestFixtureException.class, () -> Values.nonNegativeInt("max_retries", "-1")).text());
        assertEquals(0, Values.nonNegativeInt("max_retries", "0"));
    }

    @Test
    void boolAcceptsOnlyTrueFalse() {
        assertThrows(RestFixtureException.class, () -> Values.bool("ignore_ssl_errors", "yes"));
        assertTrue(Values.bool("ignore_ssl_errors", "TRUE"));
        assertFalse(Values.bool("ignore_ssl_errors", "False"));
    }

    @Test
    void notANumber() {
        assertEquals("Invalid value 'abc' for max_retries",
                assertThrows(RestFixtureException.class, () -> Values.nonNegativeInt("max_retries", "abc")).text());
    }
}
