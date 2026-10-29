package io.github.tortaje.rest;

/** Failure reported to FitNesse as a Slim exception: the message is wrapped as {@code message:<<text>>}. */
public class RestFixtureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String text;

    public RestFixtureException(String text) {
        super("message:<<" + text + ">>");
        this.text = text;
    }

    public RestFixtureException(String text, Throwable cause) {
        super("message:<<" + text + ">>", cause);
        this.text = text;
    }

    /** The message text without the Slim wrapper. */
    public String text() {
        return text;
    }
}
