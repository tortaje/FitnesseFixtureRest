package io.github.tortaje.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PackagedJarIT {

    private static Path packagedJar() throws IOException {
        try (Stream<Path> files = Files.list(Paths.get("target"))) {
            List<Path> jars = files
                    .filter(p -> p.getFileName().toString().matches("fitnesse-rest-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .filter(p -> !p.getFileName().toString().startsWith("original-"))
                    .collect(Collectors.toList());
            assertEquals(1, jars.size(), "expected exactly one packaged jar but found " + jars);
            return jars.get(0);
        }
    }

    @Test
    void jarContents() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(packagedJar().toFile())) {
            jar.stream().filter(e -> !e.isDirectory()).forEach(e -> names.add(e.getName()));
        }
        for (String prefix : List.of("io/opentelemetry/", "io/github/tortaje/hvault/", "io/github/tortaje/otel/",
                "com/jayway/", "net/minidev/", "org/objectweb/", "org/slf4j/")) {
            assertTrue(names.stream().noneMatch(n -> n.startsWith(prefix)), prefix + " must not appear unrelocated");
        }
        assertTrue(names.contains("io/github/tortaje/rest/Rest.class"), "Rest.class missing");
        assertTrue(names.contains("META-INF/MANIFEST.MF"), "MANIFEST.MF missing");
        assertTrue(names.contains("META-INF/LICENSE.txt"), "slf4j LICENSE.txt missing");
        assertTrue(names.contains("META-INF/THIRD-PARTY-LICENSES.txt"), "THIRD-PARTY-LICENSES.txt missing");
        assertTrue(names.contains("io/github/tortaje/rest/shaded/com/jayway/jsonpath/JsonPath.class"),
                "relocated JsonPath missing");
        assertTrue(names.contains("io/github/tortaje/rest/shaded/org/slf4j/nop/NOPServiceProvider.class"),
                "relocated NOPServiceProvider missing");
        String service = "META-INF/services/io.github.tortaje.rest.shaded.org.slf4j.spi.SLF4JServiceProvider";
        assertTrue(names.contains(service), service + " missing");
        try (JarFile jar = new JarFile(packagedJar().toFile())) {
            String licenses = new String(
                    jar.getInputStream(jar.getEntry("META-INF/THIRD-PARTY-LICENSES.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String artifact : List.of("com.jayway.jsonpath:json-path", "net.minidev:json-smart",
                    "net.minidev:accessors-smart", "org.ow2.asm:asm", "org.slf4j:slf4j-api", "org.slf4j:slf4j-nop")) {
                assertTrue(licenses.contains(artifact), "license listing misses " + artifact);
            }
            assertTrue(licenses.contains("Apache License"), "Apache text missing");
            assertTrue(licenses.contains("Neither the name of the copyright holders"), "BSD text missing");
            assertTrue(licenses.contains("Permission is hereby granted"), "MIT text missing");
            String content = new String(jar.getInputStream(jar.getEntry(service)).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("io.github.tortaje.rest.shaded.org.slf4j.nop.NOPServiceProvider"),
                    "service file must name the relocated provider: " + content);
        }
    }

    @Test
    void shadedJsonPathWorks() throws Exception {
        URL url = packagedJar().toUri().toURL();
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {url}, ClassLoader.getPlatformClassLoader())) {
            Class<?> extractor = loader.loadClass("io.github.tortaje.rest.response.JsonExtractor");
            Object value = extractor.getMethod("extract", String.class, String.class).invoke(null, "{\"a\":1}", "$.a");
            assertEquals("1", value);
        } finally {
            System.setErr(originalErr);
        }
        String err = captured.toString(StandardCharsets.UTF_8);
        assertFalse(err.contains("SLF4J"), "unexpected SLF4J output: " + err);
    }
}
