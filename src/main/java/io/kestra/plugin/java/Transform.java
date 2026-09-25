package io.kestra.plugin.java;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Transform records with Java",
    description = "Transform a list of records by trimming top-level string values and uppercasing selected fields."
)
@Plugin(
    examples = {
        @Example(
            title = "Normalize contact records",
            full = true,
            code = """
                id: normalize_contacts
                namespace: company.team
                tasks:
                  - id: transform
                    type: io.kestra.plugin.java.Transform
                    records:
                      - name: " alice "
                        email: " alice@example.com "
                    uppercaseFields:
                      - name
                """
        )
    }
)
public class Transform extends Task implements RunnableTask<Transform.Output> {
    @NotNull
    @Schema(
        title = "Records to transform",
        description = "A list of key-value records. Expressions that produce JSON arrays are supported."
    )
    private Property<List<Map<String, Object>>> records;

    @Schema(
        title = "Fields to uppercase",
        description = "Top-level field names whose string values should be converted to uppercase."
    )
    @Builder.Default
    private Property<List<String>> uppercaseFields = Property.ofValue(List.of());

    @Schema(
        title = "Trim string values",
        description = "Whether to remove leading and trailing whitespace from top-level string values."
    )
    @Builder.Default
    private Property<Boolean> trimStrings = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (records == null) {
            throw new IllegalArgumentException("'records' is required");
        }

        List<Map<String, Object>> renderedRecords = runContext.render(records).asList(Map.class);
        Set<String> renderedUppercaseFields = runContext.render(uppercaseFields).asList(String.class).stream()
            .filter(field -> field != null && !field.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        boolean shouldTrimStrings = runContext.render(trimStrings).as(Boolean.class).orElse(true);

        List<Map<String, Object>> transformedRecords = renderedRecords.stream()
            .map(record -> transformRecord(record, renderedUppercaseFields, shouldTrimStrings))
            .toList();

        return Output.builder()
            .records(transformedRecords)
            .recordCount(transformedRecords.size())
            .build();
    }

    private Map<String, Object> transformRecord(
        Map<String, Object> record,
        Set<String> uppercaseFields,
        boolean shouldTrimStrings
    ) {
        if (record == null) {
            throw new IllegalArgumentException("'records' cannot contain null entries");
        }

        Map<String, Object> transformed = new LinkedHashMap<>();
        record.forEach((field, value) ->
            transformed.put(field, transformValue(field, value, uppercaseFields, shouldTrimStrings))
        );
        return transformed;
    }

    private Object transformValue(
        String field,
        Object value,
        Set<String> uppercaseFields,
        boolean shouldTrimStrings
    ) {
        if (!(value instanceof String stringValue)) {
            return value;
        }

        String transformed = shouldTrimStrings ? stringValue.trim() : stringValue;
        return uppercaseFields.contains(field) ? transformed.toUpperCase(Locale.ROOT) : transformed;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The transformed records")
        private List<Map<String, Object>> records;

        @Schema(title = "The number of transformed records")
        private int recordCount;
    }
}
