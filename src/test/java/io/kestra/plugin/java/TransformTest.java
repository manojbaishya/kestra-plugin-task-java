package io.kestra.plugin.java;

import java.util.List;
import java.util.Map;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

@KestraTest
class TransformTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        String rawRecords = """
            [
              {"name": " alice ", "email": " alice@example.com ", "age": 37},
              {"name": "bob ", "email": " bob@example.com "}
            ]
            """;
        RunContext runContext = runContextFactory.of(Map.of("rawRecords", rawRecords));

        Transform task = Transform.builder()
            .records(Property.ofExpression("{{ rawRecords }}"))
            .uppercaseFields(Property.ofValue(List.of("name")))
            .build();

        Transform.Output output = task.run(runContext);

        assertThat(output.getRecordCount(), is(2));
        assertThat(output.getRecords(), contains(
            Map.of("name", "ALICE", "email", "alice@example.com", "age", 37),
            Map.of("name", "BOB", "email", "bob@example.com")
        ));
    }

    @Test
    void preservesWhitespaceWhenTrimmingIsDisabled() throws Exception {
        Transform task = Transform.builder()
            .records(Property.ofValue(List.of(Map.of("name", " alice "))))
            .uppercaseFields(Property.ofValue(List.of("name")))
            .trimStrings(Property.ofValue(false))
            .build();

        Transform.Output output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getRecords(), contains(Map.of("name", " ALICE ")));
    }
}
