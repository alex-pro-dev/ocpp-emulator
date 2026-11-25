# AGENTS.md

## Scope

Applies to the Kotlin Multiplatform/Compose Desktop emulator. Run Gradle commands from the repository root with the committed wrapper.

## Active Modules

- `settings.gradle.kts` includes only `common` and `v16`. Shared JVM source/tests are under `common/src/jvmMain` and `common/src/jvmTest`; the runnable OCPP 1.6 desktop application is under `v16/src/jvmMain`.
- Dependency and plugin versions are centralized in `gradle/libs.versions.toml`. `common/libs/sqlite-jdbc-3.42.0.0.jar` is a tracked local dependency.
- OCPP 1.6 protocol/profile handlers are under `v16/src/jvmMain/kotlin/.../v16`; persistence/UI shared across the application is split between `common` and `v16` packages.

## Constraints

- Do not edit `.gradle/`, `.kotlin/`, any module `build/` directory, generated KSP output, IDE state, or local `*.db` files.
- Keep existing upstream license/attribution headers and files.
- Protocol behavior changes should be checked against the emulator’s test suite, including loopback WebSocket scenarios.

## Validation

- Windows: `.\gradlew.bat :common:test`; Unix-like shells: `./gradlew :common:test`.
- Run `.\gradlew.bat :common:ktlintCheck :v16:ktlintCheck` for Kotlin formatting checks.
- Run `.\gradlew.bat :common:build :v16:build` for both active modules after Kotlin or Gradle changes.
- Run the OCPP 1.6 desktop application with `.\gradlew.bat :v16:run` (or `./gradlew :v16:run`).
- The wrapper downloads Gradle 9.4.1 and Maven dependencies when they are not cached, so first use requires network access.
