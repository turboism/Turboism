# warp-deformer-alt-symmetry-host-probe

Host-behaviour probe for Alt-symmetry operations on warp deformers.

Run `./build.sh` to compile. A real Cubism Editor host is required.

Multi-version bytecode regression check for the mirror hook and the button interface, covering every reviewed version in class-pins:

```sh
TURBOISM_CUBISM_REF_ROOT=/path/to/cubism-ref ./gradlew :runtime:warpAltMirrorArtifactTest
```

The directory must hold `<version>/Live2D_Cubism.jar` for every version. The task verifies the class digest, every hook target, and the actual injection result per version, plus the button factory, toolbar grouping, and scene graph interface. A missing version or interface fails the task. It does not replace button painting, click toggling, or mirror dragging verification in a real host.
