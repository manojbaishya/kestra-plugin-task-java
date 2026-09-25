# Kestra Java Plugin

## What

- Provides Java-based ETL components under `io.kestra.plugin.java`.
- Includes the `Transform` runnable task.
- This is a single-module Gradle plugin built for Java 25+ and published as a Kestra plugin jar.

## Why

- Teams can normalize extracted records inside a flow without building a separate runner.
- The task keeps transformation behavior in a tested, publishable Java component.
- A runnable extract → transform → load example provides a concrete integration starting point.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `java`
- `src/main/resources/metadata/index.yaml` defines the plugin metadata (`group`, `name`, `title`, `description`)

Infrastructure dependencies (Docker Compose services):

- `app` in `docker-compose.yml` runs Kestra locally and mounts `./build/libs/` into `/app/plugins/`

Build/test workflow:

- `./gradlew test` runs the JUnit 5 + Kestra test suite
- `./gradlew shadowJar` produces the plugin jar in `build/libs/`
- `docker compose up` starts a local Kestra instance against the built jar

### Key Plugin Classes

- `io.kestra.plugin.java.Transform` — trims top-level string values, uppercases selected fields, and returns transformed records with a count

### Project Structure

```
kestra-plugin-task-java/
├── src/main/java/io/kestra/plugin/java/
├── src/main/resources/metadata/
├── src/test/java/io/kestra/plugin/java/
├── src/test/resources/flows/
├── build.gradle
├── docker-compose.yml
├── Dockerfile
├── README.md
└── gradlew
```

## Local rules

- Base wording on the implemented package, task behavior, tests, and flow resources.
- Keep changes aligned with Kestra plugin conventions: Java 25+, `@Plugin` and `@Schema` annotations, and Gradle-based jar packaging.
- Prefer the existing test patterns in `src/test/java/io/kestra/plugin/java/TransformTest.java` and `TransformRunnerTest.java` when adding validation coverage.
- When running the plugin locally, use the repo's Docker setup in `docker-compose.yml`; the mounted `build/libs/` directory is how Kestra loads the plugin at runtime.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
