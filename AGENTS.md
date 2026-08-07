# superqr-android Agent Guide

## General Guardrails
- Stay strictly within task scope.
- Inspect git status before making edits.
- Do not commit, push, or create branches unless explicitly requested by the user.
- Avoid unrelated refactoring.
- Do not change dependencies, licenses, or protocol semantics without explicit request.
- Use focused verification.
- Report any verification not performed by the agent.

## Repository Architecture & Guidelines
- This is the standalone Android SuperQR application.
- Current Gradle module architecture is exactly: `app` -> `vision` (only `app` and `vision` modules currently exist). Do not add a Gradle module unless explicitly justified and requested.
- UI, CameraX integration, and Android application orchestration belong in `app`.
- Detection, tracking, classification, visual contract logic, diagnostic models, and reusable vision logic belong in `vision`.
- `vision` must not depend on `app`.
- Shared protocol semantics come from `superqr-protocol`. `vision/src/main/assets/visual_contract.json` is a synchronized vendored snapshot, not an independent source of truth.
- Debug Full Diagnostic implementation must remain debug-only. Release diagnostics must remain disabled/stubbed unless explicitly redesigned.
- Do not resurrect V4/V5 code.
- Do not attempt to fix the known physical color-classification problem unless explicitly tasked.

## Build and Test Policy (CRITICAL)
By default this agent **MUST NOT** run Gradle builds, APK builds, installs, ADB, emulator/device commands, or full lint.

After Android source changes:
1. Inspect source/diff visually.
2. Provide the exact recommended Gradle command for the USER to run manually.
3. Explicitly state that Android build/device validation was not run by the agent.

The USER owns:
- APK builds;
- Release/debug assembly;
- Physical-phone testing;
- Logcat/device verification.

## Multi-Repository Coordination

When this repository is opened as part of the SuperQR multi-repository workspace:

- An agent assigned to this repository has write ownership only here by default.
- It may inspect the other SuperQR repositories read-only for context.
- It must not modify another SuperQR repository unless the current user task explicitly grants cross-repository write scope.
- Different agents may work concurrently when each agent writes to a different repository.
- Multiple agents must not write to the same repository concurrently unless the user explicitly provides isolated Git worktrees/branches.
- Shared protocol changes must be finalized in `superqr-protocol` before client adaptation begins.