# Windows Cubism Editor 5.3.05

The official Windows build `503050001` uses exact release admission.
Static verification passed for 1,842 selectors across 15 Editor and Core records.
The Core JAR matches 5.3.04 byte for byte and uses the same Core version tuple.

The native MOC export record binds the constructor, settings setter, export method, and two result accessors to their new owners.
The 5.3.04 record keeps its original bindings.

On Linux with Proton Experimental and Xvfb, the official launcher completed the host connection and plugin loading.
The runtime report showed version `5.3.05` and identity state `MATCHED`.
