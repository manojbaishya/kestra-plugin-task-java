package io.kestra.plugin.java;

import java.util.Map;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest(startRunner = true)
class JavaCommandsRunnerTest {
    @Test
    @ExecuteFlow("flows/commands.yaml")
    void mergesBridgeOutput(Execution execution) {
        assertThat(((Map<?, ?>) execution.getTaskRunList().get(0).getOutputs().get("vars")).get("answer"), is(42));
    }

    @Test
    @ExecuteFlow("flows/bridge.yaml")
    void providesContextAndAllowlistedSecrets(Execution execution) {
        Map<?, ?> vars = (Map<?, ?>) execution.getTaskRunList().get(0).getOutputs().get("vars");
        assertThat(vars.get("contextSecret"), is("[REDACTED]"));
        assertThat(vars.get("secretPresent"), is(true));
    }

    @Test
    @ExecuteFlow("flows/commands-failure.yaml")
    void preservesBridgeOutputOnFailure(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.FAILED));
        Map<?, ?> vars = (Map<?, ?>) execution.getTaskRunList().get(0).getOutputs().get("vars");
        assertThat(vars.get("failureValue"), is(7));
    }

    @Test
    @ExecuteFlow("flows/commands-invalid-output.yaml")
    void rejectsMalformedBridgeOutput(Execution execution) {
        assertThat(execution.getState().getCurrent(), is(State.Type.FAILED));
    }
}
