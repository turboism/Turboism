# Windows Cubism Editor 5.3.05

Windows build `503050001` uses structural compatibility admission, not exact-release admission.
Turboism leaves the optional status bar disconnected when the editor UI hook cannot provide its required capabilities.
This prevents the status bar from blocking the base host connection.

The Core JAR matches 5.3.04 byte for byte.
The menu, workspace control, and render-scene slices still have ambiguous mappings.
The Turboism adapter for native MOC export remains unavailable because five selectors changed.
