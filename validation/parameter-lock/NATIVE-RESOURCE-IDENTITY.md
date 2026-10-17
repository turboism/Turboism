# Parameter lock: native resource and persistence identity evidence

Reviewed on 2026-10-17 against local `cubism-ref/<version>/Live2D_Cubism.jar` artifacts.
This is archive/bytecode evidence, not a live Cubism interaction acceptance test.

## Parts palette lock artwork

All 20 reviewed versions, 5.0.00 through 5.3.04, contain the same 20 lock PNG variants:

```text
res/image_{light,dark}/icons/Lock_16x16_{Default,Disabled}.scale-{100,125,150,175,200}.png
```

The native tree-table lock renderer references `image/icons/Lock_16x16_Default.png`:

| Versions | Native renderer |
|---|---|
| 5.0.00–5.3.02 | `com/live2d/ui/treeTable/h.class` |
| 5.3.03–5.3.04 | `com/live2d/ui/treeTable/i.class` |

The Parts toolbar also has separate 24px artwork. The new `CubismIcon.LOCK` uses
its native **16px lock-column** artwork. Digests live in
[`CubismNativeIconCatalog`](../../runtime/src/main/java/dev/turboism/ui/resource/CubismNativeIconCatalog.java).
No PNGs are redistributed by the plugin. Runtime still requires a reviewed artifact
admitted by that catalog; archive presence alone does not widen native icon admission.
When verified resources are unavailable, Runtime omits the row icon action; context-menu actions remain available.

## Model identity across sessions

In every reviewed version, `com.live2d.cubism.doc.model.CModelSource` writes its
`CModelGuid` under the XML key `guid` in `serialize`, and restores that field in
`deserialize`. `EditorBackedCubismModelAccess` reads that GUID through
`cubism.editor-model.model-source.guid` and `cubism.editor-model.guid.value` for
`CubismModel.id()`.

The persistence key therefore survives saving/reopening. Save As or filesystem
copies that retain the native GUID share lock configuration. Project/document
registry identities and `bindingIdentity()` are session identities and must not
be used as persistent keys. Live SDK object handles must be reacquired after load.

Reproduce the identity inspection using:

```bash
javap -c -p -classpath cubism-ref/5.3.04/Live2D_Cubism.jar \
  com.live2d.cubism.doc.model.CModelSource
```

Inspect both `serialize` and `deserialize` for the `guid` field and `CModelGuid`.

## Automated checks

Targeted SDK/resource tests cover the 100-entry icon catalog and lock paths.
Row-action tests cover native folder-icon preservation, native enabled/disabled icon resolution, hit testing, lifecycle restoration, and separate parameter/group scopes. Older palette-toolbar tests describe the previous UI.
Plugin persistence tests are separate from this archive inspection.
