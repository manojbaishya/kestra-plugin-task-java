package io.kestra.plugin.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.TargetOS;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an inline Java script",
    description = "Runs an inline JBang source file in an isolated Java runtime and returns the bridge output."
)
@Plugin(
    examples = {
        @Example(
            title = "Read context and return structured output",
            full = true,
            code = """
                id: java_script
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.java.Script
                    script: |
                      import io.kestra.plugin.java.bridge.KestraBridge;

                      class Main {
                          public static void main(String[] args) throws Exception {
                              KestraBridge.log("running Java");
                              KestraBridge.output("contextKeys", KestraBridge.context().keySet());
                          }
                      }
                """
        )
    }
)
public class Script extends AbstractJavaTask {
    @NotNull
    @Schema(
        title = "Inline JBang source",
        description = "Source rendered by Kestra and executed with JBang. Dependency declarations can be added with JBang directives."
    )
    @PluginProperty(group = "main")
    private Property<String> script;

    @NotNull
    @Builder.Default
    @Schema(
        title = "Script extension",
        description = "Extension used for the temporary source file, such as .java, .jsh, .kt, .groovy, or .md."
    )
    @PluginProperty(group = "advanced")
    private Property<String> extension = Property.ofValue(".java");

    @NotNull
    @Builder.Default
    @Schema(
        title = "Quiet JBang execution",
        description = "Whether to pass --quiet to JBang."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> quiet = Property.ofValue(true);

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        if (script == null) {
            throw new IllegalArgumentException("'script' is required");
        }

        PreparedCommands prepared = prepareCommands(runContext);
        Path sourcePath = null;
        try {
            CommandsWrapper commands = prepared.commands();
            Map<String, String> inputFiles = new LinkedHashMap<>(inputFiles(commands, runContext));
            sourcePath = runContext.workingDir().createTempFile(runContext.render(extension).as(String.class).orElseThrow());
            String sourceRelativePath = runContext.workingDir().path().relativize(sourcePath).toString();
            String source = commands.render(
                runContext,
                runContext.render(script).as(String.class).orElseThrow(),
                new ArrayList<>()
            );
            inputFiles.put(sourceRelativePath, source);
            commands = commands.withInputFiles(inputFiles);

            TargetOS targetOS = runContext.render(getTargetOS()).as(TargetOS.class).orElse(TargetOS.AUTO);
            String bridgeOption = prepared.outputFile() == null ? "" : "--cp \"$KESTRA_BRIDGE_CLASSPATH\"";
            String sourceAbsolutePath = commands.getTaskRunner().toAbsolutePath(runContext, commands, sourceRelativePath, targetOS);
            String command = String.join(" ", "jbang", runContext.render(quiet).as(Boolean.class).orElse(true) ? "--quiet" : "", bridgeOption, sourceAbsolutePath).trim();
            commands = commands
                .withTargetOS(targetOS)
                .withInterpreter(getInterpreter())
                .withBeforeCommands(getBeforeCommands())
                .withBeforeCommandsWithOptions(true)
                .withCommands(Property.ofValue(List.of(command)));

            List<Path> stagedFiles = new ArrayList<>(prepared.bridgeFiles());
            stagedFiles.add(sourcePath);
            return runPrepared(new PreparedCommands(commands, stagedFiles, prepared.outputFile()), runContext);
        } catch (Exception exception) {
            cleanup(prepared.bridgeFiles());
            if (sourcePath != null) {
                cleanup(List.of(sourcePath));
            }
            throw exception;
        }
    }
}
