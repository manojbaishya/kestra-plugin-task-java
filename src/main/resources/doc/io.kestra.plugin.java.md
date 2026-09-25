The Java plugin provides Java-based record transformation and isolated Java/JBang execution tasks for Kestra workflows.

## Runtime

`Script` and `Commands` use the Java runtime image `ghcr.io/manojbaishya/kestra-plugin-task-java:latest` by default. The image contains Java 25 and JBang 0.141.0. Set `containerImage` to use another compatible image. The image is published by the repository's `java-runtime.yml` GitHub Actions workflow.

Both tasks inherit execution properties from the Kestra script runner, including `taskRunner`, `targetOS`, `interpreter`, `inputFiles`, `beforeCommands`, and `outputFiles`.

## Script

`io.kestra.plugin.java.Script` renders an inline source string and executes it with JBang. The default extension is `.java`; set `extension` for another JBang language or source format. `quiet` defaults to `true` and controls the JBang `--quiet` flag.

### Inputs

- `script`: Required rendered JBang source.
- `extension`: Temporary source-file extension; defaults to `.java`.
- `quiet`: Whether to pass `--quiet`; defaults to `true`.
- `containerImage`: Java runtime image; defaults to the GHCR image above.
- `bridgeEnabled`: Whether to stage the bridge and context files; defaults to `true`.
- `secrets`: Optional map of values explicitly exposed through `KestraBridge.secret`.
- `inputFiles`, `beforeCommands`, `interpreter`, `targetOS`, and `taskRunner`: Standard execution options.

### Example

```yaml
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
              KestraBridge.output("flow", KestraBridge.context().get("flow"));
          }
      }

  - id: log
    type: io.kestra.plugin.core.log.Log
    message: "{{ outputs.run.vars.flow.namespace | json }}"
```

## Commands

`io.kestra.plugin.java.Commands` runs commands in order in the configured Java runtime. Use `inputFiles` to stage source files, JARs, classes, and resources before the commands run.

### Example

```yaml
id: java_commands
namespace: company.team

tasks:
  - id: run
    type: io.kestra.plugin.java.Commands
    inputFiles:
      config.properties: |
        mode=production
      App.java: |
        import io.kestra.plugin.java.bridge.KestraBridge;
        import java.nio.file.Files;
        import java.nio.file.Path;

        public class App {
            public static void main(String[] args) throws Exception {
                String mode = Files.readString(Path.of(args[0]))
                    .lines()
                    .findFirst()
                    .orElse("")
                    .replace("mode=", "");
                KestraBridge.output("mode", mode);
            }
        }
    commands:
      - javac -cp "$KESTRA_BRIDGE_CLASSPATH" App.java
      - java -cp ".:$KESTRA_BRIDGE_CLASSPATH" App config.properties

  - id: log
    type: io.kestra.plugin.core.log.Log
    message: "{{ outputs.run.vars.mode | json }}"
```

## Bridge API

The bridge class is staged as `kestra-java-bridge.jar` and is available to inline scripts and staged Java sources through the classpath variable `KESTRA_BRIDGE_CLASSPATH`.

```java
import io.kestra.plugin.java.bridge.KestraBridge;

KestraBridge.log("message");
Map<String, Object> context = KestraBridge.context();
String token = KestraBridge.secret("apiToken").orElse("");
KestraBridge.output("result", "value");
```

`KestraBridge.context()` returns the sanitized task context. `KestraBridge.secret(name)` reads only the named value from the task's `secrets` map. `KestraBridge.output` atomically merges values into the bridge output file. The plugin merges those values into the task's `vars` output, alongside standard script output fields.

Secret values are not passed in command arguments or environment variables. The secret file is created with owner-only permissions when POSIX permissions are supported, and all bridge files are cleaned up after execution. Context snapshots omit worker environment variables, secret-consumer callbacks, and configured secret-input paths. Explicit allowlisted secret values are redacted wherever they occur in serializable context values. The bridge can be disabled with `bridgeEnabled: false` when none of these files or APIs are needed.

## Transform

`io.kestra.plugin.java.Transform` accepts a list of key-value records, trims top-level string values, and uppercases the fields selected by `uppercaseFields`. The `records` property can be an inline YAML list or an expression that renders a JSON array.

### Inputs

- `records`: Required list of records to transform.
- `uppercaseFields`: Optional top-level field names whose string values are converted to uppercase.
- `trimStrings`: Whether top-level string values are trimmed; defaults to `true`.

### Outputs

- `records`: The transformed records.
- `recordCount`: The number of transformed records.

### Example

```yaml
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
```

### Extract, transform, and load

```yaml
id: java_etl
namespace: company.team

tasks:
  - id: extract
    type: io.kestra.plugin.core.debug.Return
    format: |
      [
        {"name": " alice ", "email": " alice@example.com "},
        {"name": "bob ", "email": " bob@example.com "}
      ]
  - id: transform
    type: io.kestra.plugin.java.Transform
    records: "{{ outputs.extract.value }}"
    uppercaseFields:
      - name
  - id: load
    type: io.kestra.plugin.core.log.Log
    message: "{{ outputs.transform.records | json }}"
```

The core `Return` and `Log` tasks make the example runnable without external services. Replace them with source and destination connectors for a production ETL pipeline.

## Trigger

`io.kestra.plugin.java.Trigger` is a polling trigger that creates an execution randomly according to its `interval` and `min` probability properties. Its output contains the generated `random` value.
