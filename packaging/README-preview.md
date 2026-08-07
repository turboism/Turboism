# Turboism 0.1 Developer Preview

This preview is an isolated, local Java-agent launch path for Live2D Cubism Editor 5.3.02. It does not modify the Cubism installation, launcher, license, or user files.

## Bundle layout

```text
turboism-preview/
├── turboism-agent.jar
├── run-preview.bat
├── launch-cubism-turboism.bat
├── launch-cubism-turboism.ps1
├── plugins/
│   └── project-inspector.jar
├── plugin-data/
├── state/
└── logs/
```

## Run

Double-click:

```bat
run-preview.bat
```

The launcher searches the supported Cubism 5.3.02 locations and `CUBISM_ROOT`. It invokes Cubism's bundled `java.exe` directly with the official classpath and runtime flags, then adds only `turboism-agent.jar`. It intentionally does not call a possibly modified `CubismEditor5.bat`, does not reuse a legacy Turboism agent, and does not use `JAVA_TOOL_OPTIONS`.

To select another 5.3.02 directory explicitly:

```bat
launch-cubism-turboism.bat -CubismRoot "C:\Program Files\Live2D Cubism 5.3.02"
```

## Logs

Runtime and plugin diagnostics are written to one file per Cubism session under:

```text
logs\runtime\<UTC-date>\turboism-<session>-p<PID>-<suffix>.log
```

## Removal

Close Cubism and delete the preview directory. No Cubism installation file is changed.

## Current limitations

- Cubism Editor 5.3.02 only.
- Windows preview wrapper only.
- Read-only Project Inspector is the sole supported real feature.
- No automatic install, update, rollback, recovery, marketplace, signing, or global launcher integration.
- A mismatched Cubism JAR leaves host adapters unavailable rather than attempting an unsafe fallback.
