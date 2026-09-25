package io.kestra.plugin.java.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class KestraBridge {
    public static final String CONTEXT_ENV = "KESTRA_CONTEXT_FILE";
    public static final String SECRETS_ENV = "KESTRA_SECRETS_FILE";
    public static final String OUTPUT_ENV = "KESTRA_OUTPUT_FILE";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object OUTPUT_LOCK = new Object();

    private KestraBridge() {
    }

    public static Map<String, Object> context() throws IOException {
        return readSection(CONTEXT_ENV, "variables");
    }

    public static Optional<String> secret(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Object value = readSection(SECRETS_ENV, "secrets").get(name);
        return Optional.ofNullable(value).map(String::valueOf);
    }

    public static void log(String message) {
        System.out.println(message == null ? "" : message);
    }

    public static void output(String key, Object value) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The output key must not be blank");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        output(values);
    }

    public static void output(Map<String, ?> values) throws IOException {
        if (values == null || values.isEmpty()) {
            return;
        }
        String outputPath = System.getenv(OUTPUT_ENV);
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalStateException("The Kestra output file is not configured");
        }

        Path path = Path.of(outputPath).toAbsolutePath();
        synchronized (OUTPUT_LOCK) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 1);
            Map<String, Object> outputs = new LinkedHashMap<>(readSection(OUTPUT_ENV, "outputs"));
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    outputs.put(key, value);
                }
            });
            payload.put("outputs", outputs);
            writeAtomically(path, payload);
        }
    }

    private static Map<String, Object> readSection(String environmentName, String sectionName) throws IOException {
        String fileName = System.getenv(environmentName);
        if (fileName == null || fileName.isBlank() || !Files.exists(Path.of(fileName))) {
            return Collections.emptyMap();
        }
        Map<String, Object> payload = MAPPER.readValue(Path.of(fileName).toFile(), MAP_TYPE);
        Object section = payload.get(sectionName);
        if (section == null) {
            return Collections.emptyMap();
        }
        if (!(section instanceof Map<?, ?> sectionMap)) {
            throw new IOException("Invalid Kestra bridge section: " + sectionName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        sectionMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static void writeAtomically(Path path, Map<String, Object> payload) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".kestra-output-", ".tmp");
        try {
            MAPPER.writeValue(temporary.toFile(), payload);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
