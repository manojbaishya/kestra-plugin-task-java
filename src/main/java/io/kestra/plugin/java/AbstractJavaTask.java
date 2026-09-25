package io.kestra.plugin.java;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.models.tasks.runners.TargetOS;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunVariables;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.java.bridge.KestraBridge;
import io.kestra.plugin.scripts.exec.AbstractExecScript;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = false)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractJavaTask extends AbstractExecScript implements RunnableTask<ScriptOutput> {
    public static final String DEFAULT_IMAGE = "ghcr.io/manojbaishya/kestra-plugin-task-java:latest";
    protected static final String BRIDGE_RESOURCE = "kestra-java-bridge.jar";
    protected static final String BRIDGE_RELATIVE_PATH = ".kestra/bridge/kestra-java-bridge.jar";
    protected static final String CONTEXT_RELATIVE_PATH = ".kestra/bridge/context.json";
    protected static final String SECRETS_RELATIVE_PATH = ".kestra/bridge/secrets.json";
    protected static final String OUTPUT_RELATIVE_PATH = ".kestra/bridge/output.json";
    private static final String REDACTED = "[REDACTED]";

    @Schema(
        title = "Container image for the Java runtime",
        description = "Image containing Java 25 and JBang. The default image is published by this plugin."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Schema(
        title = "Enable the Kestra bridge",
        description = "Stages the bridge, a context snapshot, an allowlisted secret file, and an output file for the runtime."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> bridgeEnabled = Property.ofValue(true);

    @Schema(
        title = "Explicitly allowlisted secrets",
        description = "Secret values made available to the bridge through KestraBridge.secret. Values are not passed in command arguments or environment variables."
    )
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "main", secret = true)
    private Property<Map<String, String>> secrets;

    @Override
    protected DockerOptions injectDefaults(RunContext runContext, DockerOptions original) throws IllegalVariableEvaluationException {
        var builder = original.toBuilder();
        if (original.getImage() == null) {
            builder.image(runContext.render(this.containerImage).as(String.class).orElse(DEFAULT_IMAGE));
        }
        return builder.build();
    }

    protected PreparedCommands prepareCommands(RunContext runContext) throws Exception {
        CommandsWrapper commands = commands(runContext);
        if (!runContext.render(this.bridgeEnabled).as(Boolean.class).orElse(true)) {
            return new PreparedCommands(commands, List.of(), null);
        }

        TaskRunner<?> taskRunner = commands.getTaskRunner();
        if (taskRunner == null) {
            throw new IllegalStateException("A task runner is required to run Java code");
        }

        List<Path> bridgeFiles = new ArrayList<>();
        try {
            Map<String, String> renderedSecrets = renderedSecrets(runContext);
            Map<String, Object> context = contextVariables(runContext, renderedSecrets);
            Path bridgePath = stageResource(runContext, BRIDGE_RELATIVE_PATH, BRIDGE_RESOURCE);
            bridgeFiles.add(bridgePath);
            Path contextPath = stageJson(runContext, CONTEXT_RELATIVE_PATH, Map.of("version", 1, "variables", context));
            bridgeFiles.add(contextPath);
            Path secretsPath = stageJson(runContext, SECRETS_RELATIVE_PATH, Map.of("version", 1, "secrets", renderedSecrets));
            bridgeFiles.add(secretsPath);
            Path outputPath = stageJson(runContext, OUTPUT_RELATIVE_PATH, Map.of("version", 1, "outputs", Map.of()));
            bridgeFiles.add(outputPath);

            try {
                Files.setPosixFilePermissions(secretsPath, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
            }

            TargetOS targetOS = commands.getTargetOS() == null ? TargetOS.AUTO : commands.getTargetOS();
            Map<String, String> bridgeEnvironment = new LinkedHashMap<>();
            bridgeEnvironment.put(KestraBridge.CONTEXT_ENV, taskRunner.toAbsolutePath(runContext, commands, relativePath(runContext, contextPath), targetOS));
            bridgeEnvironment.put(KestraBridge.SECRETS_ENV, taskRunner.toAbsolutePath(runContext, commands, relativePath(runContext, secretsPath), targetOS));
            bridgeEnvironment.put(KestraBridge.OUTPUT_ENV, taskRunner.toAbsolutePath(runContext, commands, relativePath(runContext, outputPath), targetOS));
            bridgeEnvironment.put("KESTRA_BRIDGE_CLASSPATH", taskRunner.toAbsolutePath(runContext, commands, relativePath(runContext, bridgePath), targetOS));
            commands = commands.addEnv(bridgeEnvironment);

            return new PreparedCommands(commands, List.copyOf(bridgeFiles), outputPath);
        } catch (Exception exception) {
            cleanup(bridgeFiles);
            throw exception;
        }
    }

    protected ScriptOutput runPrepared(PreparedCommands prepared, RunContext runContext) throws Exception {
        try {
            try {
                ScriptOutput output = prepared.commands().run();
                return prepared.outputFile() == null ? output : mergeOutput(output, prepared.outputFile());
            } catch (RunnableTaskException exception) {
                if (prepared.outputFile() != null && exception.getOutput() instanceof ScriptOutput output) {
                    try {
                        throw new RunnableTaskException(exception, mergeOutput(output, prepared.outputFile()));
                    } catch (IOException mergeException) {
                        exception.addSuppressed(mergeException);
                    }
                }
                throw exception;
            }
        } finally {
            cleanup(prepared.bridgeFiles());
        }
    }

    protected Map<String, String> renderedSecrets(RunContext runContext) throws IllegalVariableEvaluationException {
        if (secrets == null) {
            return Map.of();
        }
        Map<String, String> rendered = runContext.render(secrets).asMap(String.class, String.class);
        return rendered == null ? Map.of() : rendered;
    }

    protected Map<String, String> inputFiles(CommandsWrapper commands, RunContext runContext) throws Exception {
        return new LinkedHashMap<>(io.kestra.core.runners.FilesService.inputFiles(
            runContext,
            commands.getTaskRunner().additionalVars(runContext, commands),
            getInputFiles()
        ));
    }

    private Path stageResource(RunContext runContext, String relativePath, String resourceName) throws IOException {
        try (InputStream input = AbstractJavaTask.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("The Kestra Java bridge resource is missing: " + resourceName);
            }
            return runContext.workingDir().createFile(relativePath, input.readAllBytes());
        }
    }

    private Path stageJson(RunContext runContext, String relativePath, Map<String, Object> value) throws IOException {
        return runContext.workingDir().createFile(relativePath, JacksonMapper.ofJson().writeValueAsBytes(value));
    }

    private String relativePath(RunContext runContext, Path path) {
        return runContext.workingDir().path().relativize(path).toString();
    }

    private ScriptOutput mergeOutput(ScriptOutput output, Path outputPath) throws IOException {
        if (!Files.exists(outputPath)) {
            return output;
        }
        Map<String, Object> bridgeOutputs = readBridgeOutputs(outputPath);
        if (bridgeOutputs.isEmpty()) {
            return output;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        if (output.getVars() != null) {
            vars.putAll(output.getVars());
        }
        vars.putAll(bridgeOutputs);
        return ScriptOutput.builder()
            .vars(vars)
            .exitCode(output.getExitCode())
            .outputFiles(output.getOutputFiles())
            .stdOutLineCount(output.getStdOutLineCount())
            .stdErrLineCount(output.getStdErrLineCount())
            .taskRunner(output.getTaskRunner())
            .build();
    }

    private Map<String, Object> readBridgeOutputs(Path outputPath) throws IOException {
        Map<String, Object> payload = JacksonMapper.ofJson().readValue(outputPath.toFile(), JacksonMapper.MAP_TYPE_REFERENCE);
        Object outputs = payload.get("outputs");
        if (outputs == null) {
            return Map.of();
        }
        if (!(outputs instanceof Map<?, ?> outputMap)) {
            throw new IOException("Invalid Kestra bridge output");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        outputMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> contextVariables(RunContext runContext, Map<String, String> renderedSecrets) {
        Map<String, Object> variables = new LinkedHashMap<>(runContext.getVariables());
        variables.remove(RunVariables.ENVS);
        variables.remove(RunVariables.SECRET_CONSUMER_VARIABLE_NAME);

        Set<String> secretValues = new HashSet<>();
        renderedSecrets.values().stream().filter(value -> value != null && !value.isBlank()).forEach(secretValues::add);
        for (String secretInput : Optional.ofNullable(runContext.getSecretInputs()).orElse(List.of())) {
            Object value = valueAtPath(variables, secretInput);
            collectScalarValues(value, secretValues, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) sanitize(variables, secretValues, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (String secretInput : Optional.ofNullable(runContext.getSecretInputs()).orElse(List.of())) {
            removePath(sanitized, secretInput);
        }
        return sanitized;
    }

    private Object sanitize(Object value, Set<String> secretValues, Set<Object> visited) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof String stringValue) {
            return redact(stringValue, secretValues);
        }
        if (value instanceof Map<?, ?> mapValue) {
            if (!visited.add(value)) {
                return "[CYCLE]";
            }
            Map<String, Object> result = new LinkedHashMap<>();
            mapValue.forEach((key, child) -> {
                String name = String.valueOf(key);
                if (!RunVariables.ENVS.equals(name) && !RunVariables.SECRET_CONSUMER_VARIABLE_NAME.equals(name)) {
                    result.put(name, sanitize(child, secretValues, visited));
                }
            });
            visited.remove(value);
            return result;
        }
        if (value instanceof Collection<?> collectionValue) {
            if (!visited.add(value)) {
                return "[CYCLE]";
            }
            List<Object> result = new ArrayList<>();
            for (Object child : collectionValue) {
                result.add(sanitize(child, secretValues, visited));
            }
            visited.remove(value);
            return result;
        }
        if (value.getClass().isArray()) {
            if (!visited.add(value)) {
                return "[CYCLE]";
            }
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(sanitize(Array.get(value, index), secretValues, visited));
            }
            visited.remove(value);
            return result;
        }
        if (value instanceof Enum<?> || value instanceof UUID || value instanceof java.net.URI || value instanceof Path
            || value instanceof Date || value instanceof TemporalAccessor || value instanceof Locale
            || value instanceof TimeZone || value instanceof java.nio.charset.Charset) {
            return redact(String.valueOf(value), secretValues);
        }
        return redact(String.valueOf(value), secretValues);
    }

    private String redact(String value, Set<String> secretValues) {
        String result = value;
        for (String secret : secretValues.stream()
            .filter(secret -> secret != null && !secret.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList()) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    private Object valueAtPath(Map<String, Object> variables, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (variables.containsKey(path)) {
            return variables.get(path);
        }
        String[] parts = path.split("\\.");
        Object current = variables;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private void removePath(Map<String, Object> variables, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (variables.containsKey(path)) {
            variables.remove(path);
            return;
        }
        String[] parts = path.split("\\.");
        removePath(variables, parts, 0);
    }

    @SuppressWarnings("unchecked")
    private boolean removePath(Map<String, Object> variables, String[] parts, int index) {
        if (index >= parts.length || !variables.containsKey(parts[index])) {
            return false;
        }
        if (index == parts.length - 1) {
            variables.remove(parts[index]);
            return true;
        }
        Object child = variables.get(parts[index]);
        if (child instanceof Map<?, ?> childMap) {
            return removePath((Map<String, Object>) childMap, parts, index + 1);
        }
        return false;
    }

    private void collectScalarValues(Object value, Set<String> values, Set<Object> visited) {
        if (value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character
            || value instanceof Enum<?> || value instanceof UUID || value instanceof java.net.URI || value instanceof Path
            || value instanceof Date || value instanceof TemporalAccessor || value instanceof Locale || value instanceof TimeZone
            || value instanceof java.nio.charset.Charset) {
            values.add(String.valueOf(value));
            return;
        }
        if (!visited.add(value)) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> collectScalarValues(child, values, visited));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(child -> collectScalarValues(child, values, visited));
        } else if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                collectScalarValues(Array.get(value, index), values, visited);
            }
        }
        visited.remove(value);
    }

    protected void cleanup(List<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    protected record PreparedCommands(CommandsWrapper commands, List<Path> bridgeFiles, Path outputFile) {
    }
}
