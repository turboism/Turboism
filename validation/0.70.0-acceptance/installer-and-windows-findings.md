# 0.70.0 installer and Windows acceptance findings

## Scope

This pass uses native Windows and Java 17.
It does not establish final completed-tree, signed-candidate, or Cubism host acceptance.
The user deferred the repeated-input investigation and all GIF acceptance.

## Fixed: piped Java installer ignored standard input

A task-owned installer stopped at language selection despite receiving piped answers.
Increasing the timeout to 360 seconds did not resolve the problem.

The thread dump identifies the input path:

```text
Kernel32.ReadConsoleInputW
WindowsTerminal.readConsoleInput
ConsoleReader.readLine
LanguageConsoleDialog.initLangPack
```

JLine selected its native Windows terminal instead of the verifier's standard-input pipe.
The global IzPack lock did not cause this failure.

Commit `362f9e853` adds a stream-backed terminal argument to verification-owned Java commands:

```text
-Djline.terminal=jline.UnsupportedTerminal
```

A focused replay reached the Welcome panel and honored the quit answer.
The production installer and interactive launch commands retain their existing terminal behavior.

## Fixed: Thin verification deselected the first plugin

The Windows answer generator supplied a managed-Graal answer for every installation mode.
The Thin console flow has no managed-Graal prompt.
That extra answer therefore deselected ACP, the first optional plugin.

The first replay installed successfully but produced this unexpected selection:

```json
{"disabledPlugins":["dev.turboism.plugin.acp"]}
```

Commit `362f9e853` restricts that answer to Full and Lite.
The existing Thin assertion remains unchanged.

## Installer verification results

All commands use these version arguments:

```text
-PinstallerVersion=0.70.0 -PturboismVersion=0.70.0 -PturboismRelease=true
```

| Command | Result | Evidence |
|---|---|---|
| `./gradlew --offline --console=plain checkJavaInstaller` | PASS, 2m19s | `build/acceptance/070/check-java-installer-2.log` |
| `./gradlew --offline --console=plain checkWindowsInstaller checkInstallerLocalization` | PASS, 20s | `build/acceptance/070/check-windows-installer.log` |

The second command also runs `checkWindowsInstallerPackaging`.

The Java installer pass covers four locales, plugin selection, reselection, and exact-byte no-op preservation.
It also covers migration, malformed-input rejection, retired-plugin cleanup, uninstall retention, relocation safety, and global-lock isolation.
The run reports an unavailable symbolic-link backup fixture and the unsupported Windows FIFO fixture as skips.

The Windows packaging pass exercises the production generator and PowerShell payload helper.
It verifies plugin identity backup, skipped selections, failed-copy recovery, localized README generation, and offline engine pins.
The Windows installer check verifies the script and payload simulation, not a running NSIS wizard.

No `makensis` executable or native EXE artifact exists in the inspected tool locations.
Native EXE plugin-selection and README interaction therefore remain unverified.
Do not mark the complete Windows installer acceptance item as passed.

## Fixed: Windows log filtering introduced incompatible document separators

The focused baseline reproduced two failing tests in `PaletteFilterHostOperationsTest`.
The filter joined lines with `System.lineSeparator()`.
A replacement Swing document also lacked an explicit serialization separator.
On Windows, those choices introduced CRLF differences and duplicate serialized line breaks.

Commit `7bb24a557` uses LF inside filtered documents and sets `DefaultEditorKit.EndOfLineStringProperty` explicitly.
The original exact assertions remain unchanged.
One additional assertion covers CRLF input normalization.

Verification:

```text
./gradlew --offline --console=plain :runtime:spotlessJavaApply \
  :runtime:test --tests dev.turboism.ui.filter.PaletteFilterHostOperationsTest \
  :runtime:spotlessJavaCheck \
  -PinstallerVersion=0.70.0 -PturboismVersion=0.70.0 -PturboismRelease=true
```

Result: 28 tests, zero failures, zero errors, and zero skips.
Java formatting also passed.
Evidence: `build/acceptance/070/log-filter-before.log` and `build/acceptance/070/log-filter-after-2.log`.
This focused pass does not replace the outstanding final verification gates.

## Old Cubism session cleanup

The user explicitly authorized closing the old sessions.
The inspection recorded eight Cubism 5.3.04 process identities before sending close requests.
Only PID 19420 had visible windows and a responsive JVM.
It used the isolated acceptance home and model copy.

Normal window-close requests did not complete.
An identity-checked forced termination ended PID 19420.
Record that session as an abnormal exit, not successful lifecycle acceptance.

Seven older process records remain with one thread each and non-`STILL_ACTIVE` exit codes.
Java attach reports access denied; forced `taskkill` reports no running task instance.
No corresponding windows remain.
Those results do not prove complete kernel cleanup.
Keep real-host admission blocked until the cleanup state becomes verifiable.

Evidence:

- `build/acceptance/070/shutdown/before.json`
- `build/acceptance/070/shutdown/close-results.json`
- `build/acceptance/070/shutdown/forced-close-results.json`

Do not terminate other Java applications or restart Windows without separate authorization.
