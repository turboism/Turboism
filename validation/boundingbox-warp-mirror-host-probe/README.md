# boundingbox-warp-mirror-host-probe

Host-behaviour probe for bounding-box updates during warp deformer mirroring.

Run `./build.sh` to compile. A real Cubism Editor host is required.

When `scripts/preview/run-boundingbox-warp-mirror-host-validation.sh` runs the probe, it enables
`-Dturboism.validation.boundingBoxOverlay.buttonPositions=true` and emits the
`BBOX_OVERLAY_BUTTON_RECT` coordinates that the automated click needs. A manual probe run needs
the same JVM argument. A routine run emits none of these validation logs by default.
