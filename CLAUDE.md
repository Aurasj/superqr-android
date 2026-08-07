# superqr-android

Standalone SuperQR Android application.

Current Gradle architecture:

`app -> vision`

Only `app` and `vision` are current modules.

- `app`: UI, CameraX integration, Android orchestration, receive workflow.
- `vision`: detection, tracking, classification, transport decoding, diagnostics, replay and reusable vision logic.
- `vision` must not depend on `app`.

Shared protocol semantics come from `superqr-protocol`.

Vendored protocol/visual-contract files are synchronized snapshots, not independent sources of truth.

Do not add Gradle modules or change dependencies unless explicitly justified by the task.

## Verification policy

Use verification proportionally to the change.

Default workflow:

1. Inspect the final source/diff.
2. Run the smallest relevant JVM/unit test when useful.
3. Run broader module tests only when the change warrants it.
4. Do not repeatedly run expensive verification after small edits.

By default, do NOT run:
- `gradlew clean`
- APK/AAB packaging
- release builds
- ADB
- emulator/device operations
- full Android lint

Run `:app:assembleDebug` only when explicitly requested or when final build verification is materially useful to the task.

Physical-camera/device validation belongs to the user unless explicitly requested and available.

Never claim physical validation that was not performed.

## Completion

Review `git diff` before finishing.

Report:
- what changed
- why
- focused verification performed
- verification still needed from the user