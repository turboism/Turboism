# Turboism Windows managed-launch preview

The Windows payload is a Java-agent runtime for Live2D Cubism Editor. It never
modifies a Cubism installation, its official launcher, licensing files, or
user files.

## Bundle

```text
turboism/
├── turboism-agent.jar
├── configure_turboism.ps1
├── cubism-launch-common.ps1
├── launch-cubism-turboism.bat
├── launch-cubism-turboism.ps1
├── plugins/
├── state/
└── logs/
```

## Run

Run `configure_turboism.ps1` first. It discovers only supported Cubism 5.2.03
and 5.3.02 roots, checks the official `CubismEditor5.bat`, bundled Java
launcher, and application JAR shape, and stores its bounded selection in the
Turboism-owned `cubism-installations.json` state file. Manual folder selection
is available for installations not found by bounded discovery.

The configurator creates one explicit Start Menu entry per selected root and a
separate D3D entry only when that root supplies an official D3D BAT. The generic
launcher displays a picker when more than one selected root exists; it never
silently chooses a first result. Every launch calls the selected installation's
official Cubism BAT, passes process-scoped Turboism JVM options, and restores
the caller's environment after the BAT exits.

For an explicit command-line launch:

```bat
launch-cubism-turboism.bat -CubismRoot "%ProgramFiles%\Live2D\Cubism 5.3.02"
```

No production entry invokes the Cubism Java main class directly or edits the
Cubism root. No host-readiness claim is made by this packaging preview.

## Removal

Close Cubism and remove the Turboism payload directory. The managed launcher
state and shortcuts are Turboism-owned; no Cubism installation file is
changed.
