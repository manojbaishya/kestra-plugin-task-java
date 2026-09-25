package io.kestra.plugin.java;

import java.util.List;
import java.util.Map;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@KestraTest(startRunner = true)
class TransformRunnerTest {
    @Test
    @ExecuteFlow("flows/transform.yaml")
    void flow(Execution execution) {
        assertThat(execution.getTaskRunList(), hasSize(3));

        var transformOutputs = execution.getTaskRunList().get(1).getOutputs();
        assertThat(transformOutputs.get("recordCount"), is(2));

        List<?> records = (List<?>) transformOutputs.get("records");
        assertThat(records, hasSize(2));
        assertThat(records.get(0), is(Map.of("name", "ALICE", "email", "alice@example.com", "age", 37)));
    }
}
