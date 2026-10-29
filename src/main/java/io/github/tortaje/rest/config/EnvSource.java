package io.github.tortaje.rest.config;

/** Injectable access to environment variables. */
@FunctionalInterface
public interface EnvSource {
    String get(String name);

    static EnvSource system() {
        return System::getenv;
    }
}
