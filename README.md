# Kestra Java Plugin

Java and JBang execution tasks for Kestra workflows.

## Tasks

- `io.kestra.plugin.java.Transform` normalizes records inside a flow.
- `io.kestra.plugin.java.Script` runs an inline JBang source file.
- `io.kestra.plugin.java.Commands` runs Java, JBang, or other commands against staged files, JARs, and classes.
- `io.kestra.plugin.java.Trigger` provides the plugin's random polling trigger.

## Java runtime

`Script` and `Commands` use `ghcr.io/manojbaishya/kestra-plugin-task-java:latest` by default. The image contains Java 25 and JBang 0.141.0. Override it when another compatible runtime is required:

```yaml
containerImage: my-registry.example/java-runtime:25
```

Build the runtime image locally with:

```bash
docker build --file Dockerfile.jbang --tag kestra-plugin-task-java-runtime:local .
docker run --rm kestra-plugin-task-java-runtime:local java -version
docker run --rm kestra-plugin-task-java-runtime:local jbang --version
```

## Script

`Script` stages rendered source and the Kestra bridge JAR, then runs the source with JBang.

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

Use `extension` for non-Java JBang sources and `quiet: false` when JBang command output is required. Inherited execution options include `inputFiles`, `beforeCommands`, `interpreter`, `targetOS`, `taskRunner`, and `containerImage`.

## Commands

`Commands` executes each command in order inside the configured runtime. `inputFiles` stages sources, JARs, resources, and classes in the task working directory.

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

## Kestra bridge

Both `Script` and `Commands` provide a small dependency-free API through `io.kestra.plugin.java.bridge.KestraBridge`:

- `context()` returns the sanitized Kestra task context.
- `secret(name)` reads one value from the task's explicit `secrets` allowlist.
- `log(message)` writes a line to task logs.
- `output(key, value)` and `output(map)` write structured output.

Structured bridge values are merged into the task's `vars` output and are available to later tasks through expressions such as `{{ outputs.run.vars.result }}`. Existing script output and bridge output are merged when both are present.

```yaml
inputs:
  - id: apiToken
    type: SECRET

tasks:
  - id: run
    type: io.kestra.plugin.java.Script
    secrets:
      apiToken: "{{ inputs.apiToken }}"
    script: |
      import io.kestra.plugin.java.bridge.KestraBridge;

      class Main {
          public static void main(String[] args) throws Exception {
              KestraBridge.secret("apiToken")
                  .ifPresent(token -> KestraBridge.output("tokenLength", token.length()));
          }
      }
```

Secret values are written only to a permission-restricted bridge file. They are not added to command arguments or environment variables. The context snapshot excludes host environment variables, secret-consumer callbacks, and configured secret inputs; allowlisted secret values are redacted elsewhere in the snapshot. Bridge files are removed after execution. Set `bridgeEnabled: false` when the staged bridge and context files are not needed.

## Transform

`Transform` accepts key-value records, trims top-level string values, and uppercases selected fields.

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

The runnable extract, transform, and load flow is in `src/test/resources/flows/transform.yaml`.

## Development

1. Run `./gradlew clean check` to compile and test the plugin.
2. Run `./gradlew shadowJar` to build the plugin JAR in `build/libs/`.
3. Run `docker compose up` to start Kestra with the built plugin mounted.
4. Open [localhost:8080](http://localhost:8080).

### Plugins folder gotcha

Mounting a host folder onto `/app/plugins/` replaces the container's plugins directory rather than adding to it. Core plugins are compiled into Kestra and are unaffected, but additional plugins bundled in the base image are hidden. Copy any required plugin JAR into `build/libs/` before starting the container.

### JFR startup error

On some hosts, `command: server local` fails with:

```text
Unable to create JFR repository directory using base location (/tmp)
```

`docker-compose.yml` works around this by mounting `/tmp` as `tmpfs`. If you run Kestra without Compose, add an equivalent mount such as `-v /tmp:/tmp` or `--tmpfs /tmp`. Tracked upstream in [kestra-io/kestra#17405](https://github.com/kestra-io/kestra/issues/17405).

## Documentation

- Plugin documentation: `src/main/resources/doc/io.kestra.plugin.java.md`
- [Kestra documentation](https://kestra.io/docs)
- [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)

## License

Apache 2.0 © [Kestra Technologies](https://kestra.io)
