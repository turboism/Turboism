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
├── graal/
│   ├── lib/                  # isolated Windows GraalJS host closure
│   └── runtime/              # managed GraalVM runtime, if installed
├── cache/
│   └── runtime/graal/        # managed-runtime download cache
├── plugins/
├── state/
└── logs/
```

## Run

The managed launcher keeps the two Java runtimes separate:

- Cubism uses GraalVM by default. Turboism Settings → Performance → Cubism JVM
  can switch the next managed launch to Cubism's bundled Java. When GraalVM is
  selected but absent, that page offers an explicit, user-initiated automatic
  install on Windows x64 only. It installs the pinned GraalVM Community 25.2.4 /
  JDK 25.0.4 runtime in `graal\runtime`; `graal\lib` remains the packaged
  isolated host closure and `cache\runtime\graal` is the download cache.
- The installer verifies official HTTPS hosts, release metadata, exact size and
  SHA-256, safe extraction, and an isolated host `READY` result before it
  activates the runtime. It preserves applicable legal files and does not
  change `JAVA_HOME`, `PATH`, the registry, Cubism, or Cubism's bundled Java.
  Failure or cancellation retains the prior selection. Startup never downloads.
- The production managed launcher uses the same discovered GraalVM executable
  for the isolated script-host process, but still starts it as a separate JVM
  with the packaged Windows `graal\lib\*` closure. Discovery accepts the
  managed `graal\runtime` and configured external GraalVM locations. A
  candidate is accepted only when its adjacent JDK `release` metadata identifies
  GraalVM Community 25.2.4 / JDK 25.0.4.

Example:

```bat
launch-cubism-turboism.bat ^
  -CubismRoot "%ProgramFiles%\Live2D\Cubism 5.3.02" ^
  -CubismJava "C:\Java\graalvm-community-openjdk-25.0.4+8.1\bin\java.exe"
```

The launcher passes the Graal executable and packaged `graal\lib\*` classpath
as process-scoped `turboism.graal.*` properties. It does not replace
`JAVA_HOME`, persist `JAVA_TOOL_OPTIONS`, or load Graal/Truffle into Cubism's
classpath. If a saved or default GraalVM selection cannot be resolved, the
managed launcher warns and starts Cubism with its bundled Java. An explicit
missing/invalid `-CubismJava` path remains an error rather than being silently
ignored; a valid explicit path is still the advanced one-launch override and
may intentionally select another compatible Java runtime. When no separate
GraalVM is available, Cubism still starts but the isolated script runtime is
disabled.

The managed-runtime service can verify or remove the managed runtime, although
the current UI exposes installation only. For manual removal, close Cubism and
Turboism, delete `graal\runtime`, and preserve `graal\lib`. GraalVM Community
Edition is generally redistributable under GPLv2 with the Classpath Exception,
subject to retaining applicable licenses/notices, satisfying source-availability
obligations, and auditing the licenses of every bundled component.

Graal scripts are isolated, permission-checked automation and bulk-compute
programs. They are not Java plugins and do not automatically accelerate
Cubism rendering or native model evaluation. Java plugins remain the correct
choice for full SDK access, UI/lifecycle contributions, transactions, reviewed
host adapters, and latency-sensitive work. See `docs/graal-scripts.md` in the
source tree for the API, trade-offs, and suitable workload shapes.

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
Cubism root. This packaging preview makes no Cubism-host readiness claim.

## Removal

Close Cubism and remove the Turboism payload directory. The managed launcher
state and shortcuts are Turboism-owned; no Cubism installation file is
changed.
