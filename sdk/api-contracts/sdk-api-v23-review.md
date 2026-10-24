# SDK v23 polygon atlas and progress dialog review

This baseline records the SDK extensions authorized for 0.67.4. All v2–v22
anchors and baseline files remain unchanged; v23 becomes the live release gate.

## Immutable reference

- Source: `46b21e5ca6e2761aec2d168c135f919e1fca6159`.
- Reconstructed from an isolated Git archive using the historical SDK Gradle
  builder, Gradle 8.10.2 and Java 17.
- Canonical dump: 8419 lines, SHA-256
  `051d6cad7ef74e9ce8e9d18f8068e1d0a0b2ec073ad657cc0d0f45c9daeb1881`.
- Captured to an external review path before adding the reviewed baseline.

## v22 → v23 delta and compatibility

The canonical dump grows from 8406 to 8419 lines: 17 added records and four
replaced records. No existing public type or callable JVM descriptor is removed.

- `TextureAtlasLayoutItem` adds optional source `outline`, a four-argument
  constructor and an accessor. The existing three-argument constructor remains.
- `TextureAtlasLayoutPlan` adds `polygonPages`, an eight-argument constructor
  and an accessor. Existing constructor descriptors remain.
- Both records gain a component. Record reflection, equality, hash codes and
  string representations therefore change. The retained former canonical
  constructors lose parameter-name metadata; reflective consumers must adapt.
- `TextureAtlasOutline` adds value equality, hashing and string representation.
- `TurboismProgressDialog` adds one constructor and show, focus, setMessage,
  setProgress and close methods. It is an additive AutoCloseable UI utility.

The maintainer explicitly authorized SDK extension for polygon one-click
packing and requested this stable release. These compatibility effects are
recorded here and in the release notes, rather than hidden with annotations.
The four official plugins using new APIs (atlas-folder-layout,
atlas-bitmask-nesting, atlas-dalsoo-polygon and psd-clip-mask-import) require
`[0.67.4,1.0.0)`. Other plugins retain their independently declared API floors.

## Ongoing checks

`checkSdkApiCurrent` checks the reviewed current snapshot. `checkRelease`
reconstructs this source and compares its canonical API exactly with the live
SDK. Historical anchors continue to audit their own immutable references.
