# Windows Cubism discovery

## Evidence from the official 5.3.04 installer

The maintainer supplied `Live2D_Cubism_Setup_5.3.04.exe` (247,132,880 bytes),
linked from the [official download page](https://www.live2d.com/zh-CHS/cubism/download/editor/).
Its NSIS header was decompressed and its registry/shortcut instructions inspected
without executing the installer. This is static evidence for this release, not a
claim about every past or future release or a completed Windows installation.

The installer writes these records using the selected installation directory:

| Hive and key | Value | Data |
| --- | --- | --- |
| `HKLM\Software\Live2D\Live2D Cubism` | `Install_Dir` | Installation directory |
| `HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\Live2D Cubism` | `DisplayName` | `Live2D Cubism 5.3.04` |
| Same uninstall key | `UninstallString` | `<installation directory>\uninst.exe` |
| Same uninstall key | `DisplayIcon` | `<installation directory>\CubismEditor5.exe` |

The inspected instructions do **not** write `InstallLocation`, `InstallDir`, or
`Location` to the uninstall key. The old scanner read only those names, so it
could miss an official custom installation when its directory traversal also
rejected a parent folder such as `D:\L2D`.

The installer also contains desktop and Start-menu shortcut creation instructions
targeting `CubismEditor5.exe`. Shortcuts can be removed, and the registry keys
are shared across installations, so neither source enumerates every possible
side-by-side or relocated copy. The language preference at
`HKCU\Software\Live2DCubism` is not an installation path.

## Discovery and manual import

Discovery reads the official `Install_Dir` value and Windows uninstall records
in both registry views, then resolves personal/all-users desktop and Start-menu
shortcut targets. A bounded directory scan remains a fallback. These sources
identify paths independently of the installation's parent names and depth.

The installer also provides **Import version** with a folder picker. A valid
installation directory must contain:

- `CubismEditor5.bat`
- `app\jre\bin\java.exe`
- `app\lib\Live2D_Cubism.jar`

Folder names and artifact hash allowlists are not used to recognize an imported
installation. Version and runtime compatibility are evaluated separately. A
structurally valid installation with an unresolved version remains visible as
unsupported; it is not silently activated. Existing snapshot digests protect
the scan-to-install handoff against changed files, not installation recognition.

Imports run asynchronously, preserve previous results on failure, deduplicate
roots, and update the snapshot passed to final configuration. The button,
folder prompt, progress and error messages support English, Simplified Chinese,
Japanese and Korean.
