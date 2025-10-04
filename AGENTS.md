# Repository Guidelines

## Project Structure & Module Organization
Source lives in `src/main/java/com/bromano/instrumentsgecko`, written in Kotlin 1.8 despite the `java` folder name. The Gradle Shadow plugin emits the CLI jar at `build/libs/instruments-to-gecko.jar`. Shared assets (for docs, screenshots, and trace fixtures) sit under `docs/` and `example.trace`. Favor new modules staying under the existing package to keep command wiring centralized in `GeckoCommand.kt`.

## Build, Test, and Development Commands
```bash
./gradlew build        # compile Kotlin and run unit tests
./gradlew test         # execute the JUnit 5 suite only
./gradlew shadowJar    # package runnable jar with dependencies
java -jar build/libs/instruments-to-gecko.jar --help
```
Use the wrapper (`./gradlew`) so contributors share the same Gradle tooling.

## Coding Style & Naming Conventions
Indent Kotlin with four spaces and rely on Kotlin idioms (immutable vals where possible, extension functions when helpful). Classes and objects use PascalCase, functions and properties camelCase, and constants SCREAMING_SNAKE_CASE (see `THREAD_IDLE_MULTIPLIER`). Keep code within the `com.bromano.instrumentsgecko` package and place new CLIs or utilities alongside existing files like `GeckoGenerator.kt`.

## Testing Guidelines
Add unit or integration tests under `src/test/kotlin` (create the tree if missing) using JUnit 5. Mirror production package names so Gradle discovers tests automatically. Cover parser edge cases, symbol resolution, and CLI argument handling; prefer fixtures derived from `example.trace` to keep runs deterministic. Run `./gradlew test` locally before submitting and capture coverage deltas if logic-heavy changes land.

## Commit & Pull Request Guidelines
Write concise, imperative commit titles (`Fix trace idle sampling`) and optionally append issue or PR references as in `(#7)`. Each PR should include: purpose summary, key validation steps (commands run, profiler links), and any new artifacts or screenshots. Request review when `./gradlew build` passes; note follow-up work explicitly rather than deferring silently.

## Trace & Artifact Handling
Store large trace samples outside the repo when possible and reference them in PRs. Keep bundled examples small (`example.trace`) so CI remains fast, and document new assets in `README.md` to guide users.
