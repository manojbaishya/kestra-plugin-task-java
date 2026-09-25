package io.kestra.plugin.java;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.core.runner.Process;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class JavaScriptTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @EnabledIf("io.kestra.plugin.java.JavaScriptTest#jbangAvailable")
    void runsInlineSource() throws Exception {
        Script task = Script.builder()
            .id("java-script-" + UUID.randomUUID())
            .type(Script.class.getName())
            .taskRunner(Process.instance())
            .script(Property.ofValue("""
                import io.kestra.plugin.java.bridge.KestraBridge;

                class Main {
                    public static void main(String[] args) throws Exception {
                        KestraBridge.output("answer", 42);
                    }
                }
                """))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        ScriptOutput output = task.run(runContext);

        assertThat(output.getExitCode(), is(0));
        assertThat(output.getVars().get("answer"), is(42));
    }

    public static boolean jbangAvailable() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            Path candidate = Paths.get(directory).resolve("jbang");
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
