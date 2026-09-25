package io.kestra.plugin.java;

import java.util.List;

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
    title = "Run Java commands",
    description = "Runs JBang, Java, or other commands against staged source files, JARs, and classes in an isolated Java runtime."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a staged JAR",
            full = true,
            code = """
                id: java_commands
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.java.Commands
                    inputFiles:
                      app.jar: "https://example.com/app.jar"
                    commands:
                      - java -jar app.jar
                """
        )
    }
)
public class Commands extends AbstractJavaTask {
    @NotNull
    @Schema(
        title = "Commands to execute",
        description = "Commands are executed in order inside the configured Java runtime. Use inputFiles to stage source files, JARs, and classes."
    )
    @PluginProperty(group = "main")
    private Property<List<String>> commands;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        if (commands == null) {
            throw new IllegalArgumentException("'commands' is required");
        }

        PreparedCommands prepared = prepareCommands(runContext);
        try {
            TargetOS targetOS = runContext.render(getTargetOS()).as(TargetOS.class).orElse(TargetOS.AUTO);
            CommandsWrapper commandsWrapper = prepared.commands()
                .withInterpreter(getInterpreter())
                .withBeforeCommands(getBeforeCommands())
                .withBeforeCommandsWithOptions(true)
                .withTargetOS(targetOS)
                .withCommands(commands);

            return runPrepared(new PreparedCommands(commandsWrapper, prepared.bridgeFiles(), prepared.outputFile()), runContext);
        } catch (Exception exception) {
            cleanup(prepared.bridgeFiles());
            throw exception;
        }
    }
}
