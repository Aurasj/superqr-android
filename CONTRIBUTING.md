# Contributing

Contributions should keep the Android receiver measurable, testable and aligned with the shared SuperQR protocol.

## Development

Run the relevant JVM tests before opening a pull request:

```powershell
.\gradlew.bat :vision:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin
```

Shared protocol or frame-format changes belong in `superqr-protocol` first. The `app` module owns Android UI/camera integration; reusable optical decoding belongs in `vision`.

## Pull requests

Keep changes focused and describe:

- what changed and why;
- tests run;
- whether physical camera testing was performed;
- the phone/display setup for any optical performance result.

Do not report theoretical profile capacity as measured transfer speed. Physical claims should be reproducible and include test conditions.
