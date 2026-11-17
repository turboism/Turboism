# ACP Auto-Modeling Capability and Interface Completeness Audit

Date: 2026-10-24. Original audit baseline: `a7486647d66bbfb5e0c415523c38d5f0fcd1feac`, including the workspace changes of the original audit session.

Nature: a requirement matrix, a source review, and interface design proposals. It is neither a claim that the feature is delivered nor an acceptance report for every host version. The audit consolidation added documentation only: it changed no production code, started or modified no model, and connected to no Cubism. Later implementation and testing are recorded separately in the [implementation verification record](verification.md) and never retroactively rewrite the historical state of this audit.

The current workspace already holds later implementation and host evidence; the latest unified entry is [the current modelling checkpoint](current-readiness.md), with round-by-round records up to section 65 of verification. The 13-tool and matrix states in this document describe only the original audit baseline.

Supporting documents: [implementation checklist and acceptance gates](implementation-plan.md), [Paseo harness communication reference and acceptance table](harness-reference.md), and [verification record of the existing implementation slices](verification.md).

## 1. Evidence Boundary and Workspace Differences

This document consolidates the audit record the user provided. The uncommitted changes of the original audit session are not part of the baseline commit itself and cannot be reproduced from that commit alone. At consolidation time the workspace HEAD was `63993968018d88e8158303dd6f363bd4411f2721` and the initial workspace was clean; the original baseline commit object exists, but no complete cross-commit review was performed this time.

| Item | Original audit record | Evidence boundary of this consolidation |
| --- | --- | --- |
| Cubism live catalog | No windowed process was found; stdio initialize returned `-32000` and no catalog succeeded | The host was not connected this time; that history is not described as a result of this connection |
| Pi resource bridge | The original session already had a list, templates, and read workspace patch | The `pi-mcp-extension.mjs` before consolidation bridged only the tools catalog and held no such resource bridge; the patch must be reimplemented or recovered |
| Permission digest | The original session fixed a `<unavailable>` caused by the message string | A historical workspace record that was not re-verified here, and it is not claimed to be in the current HEAD |
| Offline collector | The original session had `collect_live_catalog.py` and 2 tests | Before consolidation neither the script nor the tests existed in this directory; no script, successful catalog, or test result was fabricated |
| Historical ACP tests | 200 cases: 189 passed, 4 failed, 7 skipped; 19 native transport and Pi cases passed | A historical record the user provided, not a run of this round and not acceptance of the new interfaces |
| Local evidence and links | Windows `D:\Turboism-maintenance\acp-evidence`; 48 local links of the original text passed | Not general repository CI evidence; the original link check result does not apply to this document |

A spot check before consolidation confirmed: the SDK declares `CubismModel.renderPixels` and `exportMoc`; the thumbnail request is capped at 150×150; `NativeAgentProvider.tool` JSON-encodes a non-string result and emits it as text; the Pi bridge has a 15-second request timeout and an 8 MiB message limit; the Chinese MCP description says 11 tools at the top while its body lists 13. The rest of the matrix keeps the original audit judgement and still needs item-by-item source and host admission review.

## 2. Conclusion and Corrections

The main problem is that **the capability chain SDK → MCP → Agent → ACP interface is not fully connected**.

The SDK and Runtime capabilities the original audit located include mesh and per-keyform geometry, Warp and Rotation stored keyform editing, native model rendering, image registration and binding, texture atlas layout application, partial animation editing, and native MOC export. The existence of a low-level primitive does not mean a reliable auto-modelling algorithm or the current production MCP entry exists.

Still to design or accept: the file authorization remote session bridge, long tasks with cancellation and result reconciliation, cross-object safe transactions, geometry and visual quality assessment, the physics authoring write interface, and the scene lifecycle plus complete delivery flow.

Two over-broad conclusions that must be corrected:

- **Native rendering exists.** `CubismModel.renderPixels(width,height)` already has an SDK and Runtime route. `ScreenshotCaptureService` is a different recent-file preview capped at 150×150 and cannot replace eye and mouth detail acceptance. Neither is exposed to an Agent by the production MCP described in this audit.
- **MCP cannot be said to be unable to save at all.** The ID of `EditorCommand.SAVE` is `save`, and direct dispatch does not exclude it uniformly; `turboism.editor_commands.execute({kind:"direct",commandId:"save"})` may be used only when the live `availableDirectCommands` declares it and the permission and host allow it. The OPEN and SAVE_AS workflows with an authorized handle are not open; an enumeration existing cannot replace live session discovery or a verified save completion.

## 3. Scope and State

### Three Goals

| Layer | Goal | Required loop |
| --- | --- | --- |
| L1 | Incremental modelling of an existing CMO3 | Read topology and keyforms → head, eye and mouth, and body edits → render check → undo → save and reopen |
| L2 | Bind a split PSD and its layers from scratch | Import → semantic mapping → image binding and mesh → deformers, parameters, keyforms → correction → atlas and export |
| L3 | One illustration to a complete delivered model | Additionally semantic layering, occlusion repainting, style consistency, physics, expressions, motion, and application adaptation |

The complete list covers the engineering chain of the three goals and does not mean every Cubism tool, version, style, or parameter combination is covered. L3 cannot be promised to be solved automatically through the open Cubism API alone.

| Marker | Meaning |
| --- | --- |
| E | A production MCP entry exists; admission is still dynamic by host, permission, object, and operation |
| P | Partly public, or only able to start a UI command, without a complete workflow carrying data and verification |
| B | The original audit found an SDK or Runtime implementation but no MCP wrapper; not a host pass marker |
| S | An SDK declaration or partial capability exists, and complete Runtime support of the target scenario awaits review |
| N | The currently audited interface is insufficient and needs a new typed contract, implementation, or evidence |
| A | Mainly Agent, algorithm, quality, and orchestration work rather than a single setter |

Priority: P0 = a real L1 head editing loop; P1 = L2 material to a new model; P2 = complete character delivery extension. The table holds **59 items**, all in the original audit state and none a proof that the new interfaces pass.

## 4. The Production MCP Surface at Audit Time

The production assembler and domain, together with the original audit record, list 13 tools under the prefix `turboism.`:

`model_objects.apply`, `parameters.apply`, `parameter_bindings.apply`, `glues.read`, `glues.write`, `textures.read`, `textures.write`, `history.read`, `history.undo`, `history.redo`, `transaction.execute`, `capabilities.read`, `editor_commands.execute`.

The catalog records 15 resources, 2 templates, and 8 prompts. The Chinese description still says 11 tools at the top and should be corrected separately; this round kept the production plugin documentation unchanged and only recorded the difference.

The parameter resources read values and binding points and cannot provide mesh coordinates, Warp control grids, or the forms of a Rotation. An object create may carry initial geometry, which is not the same as per-keyform read and write of an existing object. A direct command depends on the UI mode, the selected object, and the parameter state, and an enumeration existing never replaces a dialog-free, parameterized, verifiable interface.

The coverage ledger tracks only the thirteen explicitly listed SDK owners and cannot prove the whole SDK is covered, nor may a capability be declared absent because Drawable or Warp or Rotation is not listed.

## 5. End-to-End Capability Matrix

### 5.1 Environment, Files, and Material

| ID | Required capability | Existing interface or fact | State and gap | Priority |
| --- | --- | --- | --- | --- |
| ENV-01 | Version, permission, service admission | `CubismFacade.runtime()`, the service directory, capabilities | P: missing dynamic admission per geometry, per form class, default, Morph, render, and export | P0 |
| DOC-01 | Document list, explicit target | activeProject, document, model; snapshot documentId | P: a write does not uniformly carry the expected document, model, and generation | P0 |
| DOC-02 | New model | `EditorCommand.NEW_MODEL` | P: missing document identity, readiness, canvas configuration, and success verification | P1 |
| FILE-01 | File authorization and session handle | `UserFileAccessService.request/readBytesBounded/writeBytesAtomic`, `UserFileHandle` | B/N: missing the MCP session opaque grant bridge; an attachment path is no authorization | P1 |
| FILE-02 | Open a CMO3, import a PSD | the OPEN file command, `EditorFileCommandRequest` | P: the handle command is not public; opening a PSD is not interactive model generation or merge | P1 |
| FILE-03 | Save, save a copy | SAVE, SAVE_AS | P: SAVE must be discovered live; save-as, overwrite approval, completion, and reopen verification are incomplete | P0/P1 |
| FILE-04 | Backup, checkpoint, recovery | `EditorAutoBackupService`, history, file handles | P/N: undo is no disk backup; locating, restoring, and orchestrating a copy after a restart are needed | P0 |
| ASSET-01 | Layer, image, PSD association | `psdDocuments()`, `ModelTextures.relations()` and `sources(query)` | P/B: complete source association, pixels, and binding are not all public | P1 |
| ASSET-02 | Facial feature, front and back hair, left and right, occlusion semantics | names, hierarchy, pixels | A: needs semantic mapping and manual confirmation; left and right must never be guessed from Eye or an object ID | P0/P1 |
| ASSET-03 | Split one image, repaint occlusion | no complete typed art-generation contract | A/N: needs external image capability, a model, a licence, and quality acceptance | P2 |
| ASSET-04 | External PSD modification and replacement | `exportRawImagePsd` and `replaceRawImagePsd`, `PsdFileRevision` | B: missing MCP; keep the revision conflict protection and never overwrite an arbitrary path | P1 |

### 5.2 Structure, Mesh, Image, and Appearance

| ID | Required capability | Existing interface or fact | State and gap | Priority |
| --- | --- | --- | --- | --- |
| OBJ-01 | Object creation, naming, hierarchy, deletion | `ModelObjectService`, the object collection, model_objects.apply | E/P: a modelling step across calls is not atomic | P0 |
| OBJ-02 | Identity, selection, edit mode | Drawables and MeshEditor select, selection, editLevel, defaultKeyformLocked | P/B: missing a general write contract; typed geometry should avoid depending on selection | P0 |
| GEO-01 | Source positions, UVs, triangle topology | `geometry()`, `authoringSnapshot().sourceGeometry()` | B: missing geometry read, pagination, and coordinate system; an object list cannot replace geometry | P0 |
| GEO-02 | Initialize topology, triangulate, validate | `previewSourceTriangulation`, source geometry create and createMany | P/B: create has basic triangle data and lacks preview plus separate source and default creation | P1 |
| GEO-03 | Modify existing topology, UVs, source geometry | `replaceGeometry`, MeshEditService point and edge operations | B/S: missing typed MCP; edit mode, selection, and host support need separate acceptance | P1 |
| GEO-04 | Per-keyform vertices of an existing mesh | authoringSnapshot, ordinaryKeyform, stored batch, `setPositions` | B: needs the complete parameter GUID tuple, formGuid, and expected snapshot | P0 |
| GEO-05 | Per-keyform draw order and joint editing | `setOrdinaryKeyformDrawOrders` and `setOrdinaryKeyforms` | B: missing MCP; an occlusion fix does not rely on vertices alone | P0 |
| GEO-06 | Evaluated geometry and freshness | evaluationSnapshot, vertexPositions, ModelInstance, update | B: missing a typed evaluation projection; a write does not mean the current frame updated | P0 |
| TEX-01 | Pixel registration, image binding | `registerImageAndBind`, bindModelImage, textureInput | B: missing MCP; geometry alone may be invisible or blank | P1 |
| LOOK-01 | Visibility, lock, opacity, colour, blend, culling | typed Drawable, Deformer, and Part properties | B/P: mostly not public; a UI toggle is no readable idempotent property write | P0/P1 |
| MASK-01 | Clipping and inverted mask | replaceArtMeshClipMasks, setClippingMaskIds and setInvertedMask | P/B: the resource is read-only and the write entry is missing; it affects eye and mouth appearance | P0 |
| GLUE-01 | Glue connection and strength | Glues, Glue, glues.read and glues.write | P: fields are writable while create and delete are marked RUNTIME_UNAVAILABLE | P2 |

### 5.3 Deformers, Parameters, Keyforms, and Algorithms

| ID | Required capability | Existing interface or fact | State and gap | Priority |
| --- | --- | --- | --- | --- |
| DEF-01 | Warp current and per-keyform grid | grid and replaceGrid, authoringSnapshot, stored positions | B: create initializes it, while stored form read and write are not public | P0 |
| DEF-02 | Rotation base, pivot, angle, scale, reflection | baseAngle, form and replaceForm, snapshot, stored forms | B: baseAngle cannot replace the angle of one Z keyform | P0 |
| DEF-03 | Mirror and child compensation | WarpMirrorService, mesh mirror | B/S: not public; mirroring the current form is not every keyform or every Morph | P1 |
| PAR-01 | Ordinary parameter definition, value, copy, batch | Parameters, updateDefinition and setValue | E: copy does not guarantee copying bindings or forms; a host measurement showed empty bindings | P0 |
| PAR-02 | Folder, ordering, Combined | ParameterGroups, combineWith and uncombine, the edit structure service | B: the current apply does not cover every structure operation | P1 |
| KEY-01 | Ordinary key points with binding and migration | ParameterBindingOperations and BatchOperations, parameter_bindings.apply | E/P: transfer is no lossless rig copy; use the read-back canonical ID | P0 |
| KEY-02 | Multi-parameter combination, shared and default form | the three AuthoringSnapshot axes, cells, and forms | B: a binding point list is insufficient and needs an exact tuple-to-form mapping | P0 |
| KEY-03 | Default form, source geometry, edit-level policy | defaultKeyformLocked, editLevel, source creation, writer protection | S/P: the stored ordinary batch refuses default and shared forms; never auto-unlock to bypass | P0 |
| MORPH-01 | Morph geometry and opacity | the Drawable and Warp morph API, ArtMeshMorphKeyform | B/P: limited domain; MCP refuses single-item blend-shape CRUD and offers only the supported atomic transfer | P1 |
| RIG-01 | Head XYZ, XY nine-grid, eye, mouth, brow, and hair linkage | geometry, bindings, render primitives | A+B: still needs templates, pivots, perspective and volume constraints, symmetry, and corner correction | P0/P1 |
| RIG-02 | Copy or migrate a rig while keeping keyforms | snapshots, transfer primitives; parameter copy is definition-level only | A/N: needs mapping, collision policy, and a high-level copy plan | P1 |
| RIG-03 | Body, limbs, clothing coordination | base objects, parameters, geometry | A: dedicated templates, associated constraints, and QA; a head pass is not a whole-body completion | P2 |

### 5.4 Observation, Validation, Physics, Animation, and Delivery

| ID | Required capability | Existing interface or fact | State and gap | Priority |
| --- | --- | --- | --- | --- |
| VIS-01 | Native model rendering, Agent image | renderPixels, CubismRenderedPixels, EditorRenderPixelsAccess | B: missing the MCP image and resource wrapper, the revision and pose correspondence, and the size budget | P0 |
| VIS-02 | Recent-file thumbnail | ScreenshotCaptureService, 150×150 cap | B: a separate preview that does not replace detail rendering | P2 |
| VIS-03 | Local crop, multi-pose comparison | renderPixels combined with the parameter service | A/N: needs parameter save and restore, pose to revision to image, a budget, and failure recovery | P0 |
| QA-01 | Geometry quality check | source, stored, evaluated, topology, statistics | A: needs degenerate, flipped, out-of-bounds, intersecting, cracked, density and stretch, and continuity analysis | P0 |
| QA-02 | Visual quality check | native rendering, semantics, reference images | A: closed-eye white gaps, mouth artefacts, occlusion, volume and facial drift; APPLIED is no quality pass | P0 |
| PHY-01 | Read physics settings and computation state | PhysicsSettings, AutoYure | B: no typed MCP resource | P2 |
| PHY-02 | Physics authoring and replay | PhysicsEditorService.contribute is a UI extension; PhysicsSettings is read-only | N/S: needs a writer and a simulation loop; a plugin existing does not prove automatic configuration | P2 |
| ANI-01 | Existing timeline, track, keyframe, curve | AnimationDocument, Scene, Track, Attribute | B/S: SDK and Runtime exist and MCP is missing; per-version admission of scalar, point, and curve | P2 |
| ANI-02 | Create or delete a scene, a brand-new motion project | the animationDocuments note declares no reviewed Undo for the scene lifecycle and fails closed | N/S: an existing track setter does not represent scene creation | P2 |
| EXP-01 | expression, motion, and pose application assets | parameter and animation data, motion3 validation, partial file commands | A/N: missing format, parameter reference, blink, lipsync, and pose grouping plus the application adaptation flow | P2 |
| ATLAS-01 | Atlas metadata, page add and remove | ModelTextures, textures.read and textures.write | E/P: cannot replace pixel upload, layout, or UV baking | P1 |
| ATLAS-02 | Complete layout, polygon packing, appended page | textureAtlasLayouts and current and apply, polygonLayouts, appendTextureAtlasPages | B: implemented and covered by standalone probes but missing MCP; keep the target, revision, and layout constraints | P1 |
| OUT-01 | Native MOC and atlas export | exportMoc(settings), EditorMocExportAccess | B: in-memory export exists; missing MCP plus the valid atlas, settings, budget, and authorized write-to-disk loop | P1/P2 |
| OUT-02 | CMO3 and model file set delivery | SAVE and file commands, resources, native export | P/A: needs reopen, relative references, and texture, physics, motion, and manifest verification | P1/P2 |

### 5.5 Transactions, Transport, and Agent Orchestration

| ID | Required capability | Existing basis | State and gap | Priority |
| --- | --- | --- | --- | --- |
| SAFE-01 | Read, plan, write consistency | expected authoring snapshots, history generation and revision, transactions | P: unify document, model, sourceGuid, and formGuid with CAS; a history revision cannot replace a geometry comparison | P0 |
| SAFE-02 | Single-step cross-object undo, exception rollback | AuthoringTransactionService, transaction.execute | P: the three legacy applies cannot enter a transaction; textures have their own undo; they cannot be stitched into an atomic modelling transaction | P0 |
| SAFE-03 | Idempotence, unknown result, cancellation | the APPLIED, READBACK_WARNING, and OUTCOME_UNKNOWN semantics | P/N: needs operationId and reconciliation; a timeout or abort is no host cancellation, and blind replay is forbidden | P0 |
| ACP-01 | Agent discovery, model, connection, permission, recovery | Pi, Codex, and Claude native plus partial ACP-v1 | E/P: a historical Pi native success does not mean the other routes pass | P0 |
| ACP-02 | MCP resources entering the model context | the original session's Pi list, templates, and read patch | P: the current consolidation workspace does not hold that patch; it must be recovered or implemented, published, and accepted per Agent | P0 |
| ACP-03 | The image model sees it, the window shows it | Pi can forward an image; ACP has an image component | P: `NativeAgentProvider.tool` JSON to text may lose the typed image and emit a large base64 block | P0 |
| ACP-04 | Large geometry, images, pagination, context | HTTP 1 MiB, Pi 8 MiB, transaction per-step result 256 KiB | P/N: needs immutable pagination, sparse patches or controlled blobs, digests, and a memory budget | P0 |
| ACP-05 | Long tasks, progress, disconnect, cancel, reconnect | Pi request 15 seconds; MCP has no Tasks, progress notification, or subscription | P/N: needs an asynchronous job with a verifiable result or an accepted timeout and cancellation protocol | P0/P1 |
| ACP-06 | Plan, budget, manual checkpoints, definition of done | conversation, permission UI, modelling reference context | A: needs task state, scope, vision capability, cost and quality thresholds, and a retrospective | P0 |
| ACP-07 | Authorization boundary | loopback, Bearer, stdio, plugin permissions | E/N: never open a generic invoke, host.unsafe, an arbitrary shell or path, or a bypass of approval | P0 |

## 6. Proposed MCP Catalog (All Drafts)

The prefix is `turboism.`. A read operation moves no parameter and changes no selection; a write operation binds the object identity and the precondition state and depends on no implicit UI state. Names and domain merges may change, but a closed input, version admission, result type, and rollback boundary must never be sacrificed; not every P2 item needs to finish at once.

| Proposed interface | Input focus | Return focus and implementation boundary |
| --- | --- | --- |
| authoring.capabilities.read | document and model, object type or set | per-operation version, permission, default, shared, morph, budget; extends the existing capabilities |
| authoring.objects.read | documentId, IDs, field mask, cursor | canvas and profile, hierarchy, source GUID, binding, lock, pagination and count |
| authoring.meshes.read | meshId, source, stored, or evaluated, form filter, pagination | positions, UV, indices, axes, cells, forms, identity, revision, coordinate system; distinguish the three geometries |
| authoring.warps.read | deformerId, form filter | rows, columns, quadTransform, stored control points, axes, cells |
| authoring.rotations.read | deformerId, form filter | baseAngle, the angle, origin, scale, and reflection of each form, axes, cells |
| authoring.keyforms.write | expectedState, kind, objectId, complete GUID tuple, formGuid, edits | the closed union of the three stored batches; changed IDs, before and after digests, undo or transaction receipt, read-back warning |
| authoring.geometry.replace | source or current geometry, expected, explicit impact scope | topology, UV, and source change with re-read; a separate high-risk entry that is never mixed with an ordinary keyform write |
| authoring.properties.write | enumerated property, object, value, expected | real read-back of visibility, opacity, colour, draw order, and mask, keeping the version domain |
| parameters.structure.apply | closed folder, Combined, and ordering operations | complete structure and reference impact; never abuse bind or transfer |
| authoring.morphs.read and write | parameter GUID, non-basic key, target domain, expected | morph identity, geometry, opacity; open only the verified domain |
| render.capture | expected document, model, and revision, size, options | an MCP image or controlled link with pose, revision, time, and coordinate metadata; reuses renderPixels |
| render.pose_grid | pose list, objects, image budget, restorePolicy | the image and state of each pose plus the restore result; not only the final pose image |
| files.request_grant | READ or WRITE, purpose, filter | the user approval flow, session grantId, expiry and revocation; maps UserFileHandle and accepts no arbitrary path |
| documents.open, save_as, checkpoint | grantId, expected document, overwrite policy | document identity, completion, version, verification; an ordinary SAVE may reuse the existing command |
| assets.read, register, bind, replace_psd | authorized image or blob or grant, mesh, expected revision | source, revision, pixels, binding; reuses ModelTextures and adds authorized binary transport |
| atlas.read, plan, apply, append | issued target, complete plan, expected, budget | page, UV, collision, undo, binding change; the plan algorithm stays separate from apply |
| validation.model, geometry, poses | scope, thresholds, poses, reference requirement | object, parameter, and form localization, severity, evidence, and the scope of the implemented checks; an empty findings list does not mean every check ran |
| physics.read, plan, apply, simulate | physics document, input and output, expected | parameter reference, simulation quality, real write; the complete writer still needs evidence |
| animation.read and keyframes.apply | scene, track, attribute, typed edits | keyframes and curves, undo, version limits; scene creation is admitted separately |
| export.plan, execute, validate | type, settings, authorized target, expected | bytes and file manifest, references, compatibility, hash; not every format is supported by default |
| jobs.start, status, cancel, result | allowlisted operation, operationId, budget | queued, running, committed, failed, outcome_unknown, progress and result; no arbitrary tool or code execution |

### Ordinary Keyform Write Example

The following is a draft; the numbers only demonstrate the format and must not be used on a real model. `snapshotId` is a proposed server cache mechanism and not an existing SDK field.

```json
{
  "expectedState": {
    "documentId": "<discovered-document>",
    "modelId": "<discovered-model>",
    "snapshotId": "<server-issued-immutable-snapshot>"
  },
  "operationId": "<client-unique-operation>",
  "edits": [{
    "kind": "rotation_deformer",
    "objectId": "<discovered-deformer>",
    "formGuid": "<discovered-stored-form-guid>",
    "coordinates": [{"parameterGuid": "<native-axis-guid>", "value": 30}],
    "form": {
      "angle": 12, "originX": 0, "originY": 0,
      "scale": 1, "reflectedX": false, "reflectedY": false
    }
  }]
}
```

Contract constraints:

1. The coordinates cover every ordinary binding axis of the object and reference a really existing cell or form; a GUID is not a display ID, and a display ID may repeat or change.
2. The server stores and compares the complete expected authoring state and never skips CAS because of a snapshotId. Create the key points first and read again; never reuse an old snapshot.
3. The ordinary writer edits only an existing, independent, non-default form inside the supported domain; default, shared, Morph, and topology are admitted separately and are never auto-unlocked or bypassed through replaceGeometry.
4. ArtMesh positions, Warp controlPoints, and the complete Rotation form use separate closed schemas; a cross-type field, NaN or Infinity, an out-of-bounds value, and a point count mismatch are refused.
5. State the coordinate space, the unit, and the parent deformer relation explicitly; a screenshot pixel, a Core evaluated vertex, and an authoring or source coordinate are never interchangeable.

## 7. Security, Transaction, and Transport Rules

- **Identity binding:** verify the document and model on every write and refuse immediately when the document switches. Verify source and form, generation, and the complete expected state once before any target write.
- **Authorization:** distinguish read-only, revocable write, destructive change, file, and external side effect. An attachment or path is no handle; a save promises no undo.
- **Transactions:** reuse AuthoringTransactionService; a new tool declares transactionEligible only after it meets the synchronous, UI-affinity, and exact-schema conditions. A legacy apply must never enter a transaction by flipping a flag alone.
- **Results:** distinguish NOT_APPLIED, APPLIED, APPLIED_WITH_READBACK_WARNING, ROLLED_BACK, and OUTCOME_UNKNOWN; a read-back failure does not mean a safe retry.
- **Retry and cancellation:** operationId and the cache must align with the real commit boundary; reconcile a timeout first and never replay blindly. An Agent stop, a bridge no longer waiting, a host cancellation, and a transaction rollback are four states, and a disconnect cannot prove nothing changed.
- **Budget:** limit the page, byte, vertex, pixel, and time budgets of geometry, images, and tables separately. Limits such as HTTP 1 MiB, Pi 8 MiB, and the transaction per-step 256 KiB must not be raised uniformly without a test.
- **Consistent pagination:** generate every page from the same immutable snapshot and refuse a mix of pre-write and post-write pages. A sparse patch rebuilds the complete DTO on the server first, then verifies identity and topology, then calls the SDK.
- **Fail closed:** return unavailable with a reason when a version is not admitted, a permission is missing, the UI does not fit, or a capability is absent; never reflect, never run a shell, and never fabricate success.

## 8. Required ACP and Agent Loops

### 8.1 Discovery, Permission, and Images

An Agent should discover tools, resources, and templates completely; the original Pi session patch must be recovered or reimplemented, packaged, and regressed for the catalog update after a session restore. The other native and ACP harnesses are accepted separately. The prompts list and get gap may be improved later and is no hard dependency of executing modelling.

The chain MCP image → model context → native or ACP tool result → Swing image display must be verified separately. The model needs vision; an ACP that can attach an image does not prove the current model can see it. Fix the JSON-to-text route first so base64 never replaces an image.

An image must bind the document, model, revision, pose, resolution, and crop coordinates, and an old image, a cache, or a file thumbnail must never represent a modified result. The permission window shows the target, the impact, the parameter digest, the dangerous properties, and the undo capability; a large geometry uses an expandable digest.

### 8.2 Recoverable Task State

Proposed state machine:

```text
DISCOVER → INSPECT → PLAN → APPROVE → SNAPSHOT → APPLY
→ VERIFY_GEOMETRY → VERIFY_RENDER → ACCEPT / REPAIR / ROLLBACK
→ SAVE → REOPEN_VERIFY → REPORT
```

Each stage records the taskId, operationId, object identity, pose, snapshot or transaction receipt, images, and findings. A conversation restore is no model restore; a reconnect re-verifies the document and generation and never continues an old plan.

The head algorithm also needs layer semantics and left and right mapping, head and neck pivots, volume and perspective templates, eye, brow, nose, mouth, and contour linkage, front and back hair occlusion, a symmetry and asymmetry policy, XY corners, Z detail, and blink, mouth-shape, and XYZ combination checks. Manual confirmation of the structure and layering, the deformer plan, the keyform visuals, and the export coverage is allowed; no promise is possible that any illustration yields a professional result without supervision.

## 9. Milestones and Acceptance Gates

| Milestone | Scope | Pass gate |
| --- | --- | --- |
| A / P0 | the three snapshots, coordinate and dynamic admission, stored typed batch with CAS, draw order, mask, and appearance, render and pose restore, typed images, pagination, budget, result, and cancellation, dynamic SAVE | In an official Hiyori copy, really edit an ArtMesh vertex, a Warp stored form, and a Rotation stored form; numeric and image evidence; exact single-step undo and redo restore; fault rollback; save and reopen with unchanged forms and bindings; original hash unchanged |
| B / P1 | grant, open, save-as, layer and pixel, triangulation, source and default, image binding, parameter structure and Morph domain, atlas and export | Explicitly licensed layered material; create visible non-empty eye, mouth, and hair meshes plus head XY and Z and eye and mouth keyforms; geometry and visual, save and reopen, and native export verification |
| C / P2 | body, limbs, clothing, physics writer and simulation, animation, expressions, pose, complete manifest and target player | Accepted per specialty; only the passed part enters the "complete auto-modelling" claim |

The three A primitives may be separate cases and must not break the same head to pad the edit variety. Adding a point or moving a slider alone is not enough, and a successful Agent connection never replaces the auto-modelling loop. See the [implementation checklist](implementation-plan.md) for the detailed split.

## 10. Minimal Acceptance Case Set

| Category | Required cases |
| --- | --- |
| Admission | no document, a non-model document, an unsupported exact version, a missing permission, an empty resource, an explicit unavailable |
| Identity | tab switch, delete and recreate, parameter rename or duplicate ID, old generation or snapshot, topology change |
| form | incomplete tuple, undeclared key, shared or default form, NaN or Infinity, point count mismatch, partial failure, out-of-domain Morph |
| Geometry | exact read-back of vertices, control points, and Rotation; UV and indices, an unselected form, the original material, and other documents unchanged |
| History | single and multi-object single undo, redo, exception rollback, cancellation boundary, reconciliation of a host success with a lost response |
| Rendering | a real image from the official renderer, freshness, pose and revision, crop coordinates, parameter restore, visible to both the model and the ACP |
| Files | READ is not writable, expiry, revocation, and cross-session refusal, overwrite approval, a failure never reported as success, reopen persistence |
| Transport | consistent pagination, over-limit, timeout, disconnect, reconnect, duplicate operationId, no token leak, a read-only HTTP never posing as writable |
| Algorithm | XY nine-grid, XYZ combination, closed eyes, open mouth, occlusion, blink and mouth-shape combination, mirroring and asymmetric material |
| Delivery | atlas without overlap or out-of-bounds, valid bindings, complete MOC, textures, and references, a real load in the official or target player |

## 11. Source Evidence Index

The links below locate the original audit arguments; a file existing does not mean a complete semantic review this round or a host pass. SDK paths are rooted at `sdk/src/main/java/dev/turboism/sdk/`; the concrete package path is in each link.

- [Drawable.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/Drawable.java)
- [WarpDeformer.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/WarpDeformer.java)
- [RotationDeformer.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/RotationDeformer.java)
- [ArtMeshAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/ArtMeshAuthoringSnapshot.java)
- [WarpAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/WarpAuthoringSnapshot.java)
- [RotationAuthoringSnapshot.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/RotationAuthoringSnapshot.java)
- [EditorObjectReadAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorObjectReadAccess.java)
- [EditorArtMeshKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorArtMeshKeyformAccess.java)
- [EditorWarpKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorWarpKeyformAccess.java)
- [EditorRotationKeyformAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorRotationKeyformAccess.java)
- [PermissionCheckedDrawable.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/PermissionCheckedDrawable.java)
- [PermissionCheckedDeformer.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/PermissionCheckedDeformer.java)
- [CubismFacade.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/CubismFacade.java)
- [CubismModel.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/CubismModel.java)
- [EditorRenderPixelsAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorRenderPixelsAccess.java)
- [EditorMocExportAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorMocExportAccess.java)
- [ModelTextures.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/ModelTextures.java)
- [Drawables.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/Drawables.java)
- [EditorTextureRegistrationBindingAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorTextureRegistrationBindingAccess.java)
- [EditorTextureAtlasAppendAccess.java](../../runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorTextureAtlasAppendAccess.java)
- [TextureAtlasLayoutService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/textureatlas/TextureAtlasLayoutService.java)
- [WarpMirrorService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/mirror/WarpMirrorService.java)
- [UserFileAccessService.java](../../sdk/src/main/java/dev/turboism/sdk/ui/UserFileAccessService.java)
- [EditorFileCommandRequest.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommandRequest.java)
- [EditorFileCommand.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommand.java)
- [EditorCommand.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java)
- [ScreenshotCaptureRequest.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/screenshot/ScreenshotCaptureRequest.java)
- [PhysicsSettings.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/PhysicsSettings.java)
- [PhysicsEditorService.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/physics/PhysicsEditorService.java)
- [AnimationAttribute.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/AnimationAttribute.java)
- [AnimationScene.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/model/AnimationScene.java)
- [EditSession.java](../../sdk/src/main/java/dev/turboism/sdk/cubism/edit/EditSession.java)
- [McpHttpServer.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpHttpServer.java)
- [McpProductionDomainCatalog.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpProductionDomainCatalog.java)
- [McpParameterDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpParameterDomain.java)
- [McpHistoryCommandDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpHistoryCommandDomain.java)
- [McpTextureDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpTextureDomain.java)
- [McpGlueDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpGlueDomain.java)
- [McpTransactionDomain.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpTransactionDomain.java)
- [McpRegisteredTool.java](../../plugins/mcp/src/main/java/dev/turboism/plugin/mcp/McpRegisteredTool.java)
- [mcp-sdk-coverage.json](../../plugins/mcp/src/main/resources/META-INF/turboism/mcp-sdk-coverage.json)
- [pi-mcp-extension.mjs](../../plugins/acp/src/main/resources/native/pi-mcp-extension.mjs)
- [NativeAgentProvider.java](../../plugins/acp/src/main/java/dev/turboism/plugin/acp/NativeAgentProvider.java)
- [NativePiProvider.java](../../plugins/acp/src/main/java/dev/turboism/plugin/acp/NativePiProvider.java)
- [Chinese MCP description](../../plugins/mcp/README_zh.md)
- [Native atlas verification note](../model-import-3d-auto-atlas/verification.md)
- [MCP host probe note](../mcp-host-probe/README.md)

The historical atlas verification note records a standalone 5.3.04 native atlas and memory export probe without a cross-JVM save and reopen or a full GPU test, and it cannot be expanded into complete auto-modelling acceptance. A host probe directory existing does not mean it passed on the current machine.

## 12. Review Method and Current Executability

The original audit claimed two offline tests passed, but the original scripts and tests were not supplied with the consolidation workspace, so that historical result is not reproducible. On 2026-10-25 the [read-only collector](collect_live_catalog.py) and the [offline test](test_collect_live_catalog.py) were reimplemented, and the review command is now executable:

```sh
python3 -m unittest discover -s validation/acp-auto-modeling-capability-audit -p 'test_*.py' -v
```

The 14 offline tests pass, including a real child-process stdio fake bridge, pagination and budget, initialize -32000 producing no success, catalog content minimization, and error sanitization. The fake bridge test connects no Cubism and is no successful host catalog evidence.

After the user starts the test Cubism, copy the **trusted stdio launch descriptor** of its MCP connection window into a local file and run:

```sh
python3 validation/acp-auto-modeling-capability-audit/collect_live_catalog.py --config <local descriptor file>
```

It accepts either `{command,args,env?,type?:"stdio"}` directly or `{mcpServers:{turboism:{...}}}`; the server name may be given with `--server`. The descriptor may also be named by TURBOISM_MCP_CONFIG and the command run without `--config`. Without a configuration it returns NO_STDIO_CONFIG and never scans a user directory or assembles a token; it accepts no HTTP configuration and never reads the raw mcp.token or mcp-connection.json. The launch descriptor is executed as argv and must come from a trusted local bridge, never from a foreign configuration, and never through a shell. The descriptor and its env are used in memory only and never copied into a report or a log.

The capture method is closed to initialize, the initialized notification, tools/list, resources/list, resources/templates/list, prompts/list, and the single resource turboism://host/editor-commands. It never executes tools/call, prompts/get, save, modelling, or another resource read, and never touches a parameter or a model. Each catalog reads every page, and a repeated cursor or identity, an over-budget value, a protocol error, and an initialize failure all produce no success report.

Only after complete success does it atomically write live-mcp-catalog.json (`--output` may override). It keeps the UTC time, the protocol, the public tool, resource, template, and prompt identities, the counts, the tool input and output schema hashes, availableDirectCommands, and the typedContracts hash; it never exports the raw schema or description, the initialize response, the configuration, the environment, the Bearer token, the session header, or the stdio transcript. The report is no cross-catalog atomic snapshot and does not prove the tools in the catalog can run. The default request timeout is 15 seconds (`--timeout` up to 60), with a single line of 8 MiB, a total of 32 MiB, 64 pages and 5000 items per catalog, and a 4 MiB report; an error shows only a constant code and never prints the native response. Exit cleans up only the bridge child process started here and never exits Cubism. A failure leaves an existing historical report unchanged, so check capturedAt; an old file existing is no success of this run. Writing over the input configuration is forbidden.

No real stdio descriptor was provided or connected this round and no live catalog was produced. The current state and the new evidence are in the [verification record](verification.md); the offline collector test proves no modelling, rendering, file, or harness acceptance.

## 22. Later Implementation: ArtMesh Stored Form Opacity Read

The SDK ArtMeshAuthoringSnapshot.Form now keeps an optional native stored opacity; the original three-argument constructor stays and means unknown. The Runtime admits that read selector independently, and the mesh_form row of MCP meshes.read returns a finite number or null, never substituting the evaluated or current opacity. A Morph raw value is neither clamped nor used to infer its absolute opacity. The field paginates with the immutable snapshot and takes part in the complete-state CAS, and the existing expected snapshots of the ArtMesh vertex and draw-order writes and of the Morph geometry and order writes all keep it. An opacity change within the same revision also invalidates an old MCP geometry write plan.

The tool catalog stays at 44, the resources, templates, and prompts at 15, 2, and 8, and the limited coverage ledger at 14 owners. This round opens no opacity writer; per-form colour, Warp and Rotation opacity, a complete appearance transaction, and real host, model, and save-reopen acceptance are still pending. See the latest record of [verification.md](verification.md) for the test and evidence scope; a read plus CAS protection is not a delivered artistic opacity auto-modelling feature.

## 23. Later Implementation: Joint Ordinary ArtMesh Opacity Write

On top of the read above, a closed SDK ArtMeshKeyformValues and Drawable.setOrdinaryKeyformValues were added, and the ArtMesh edits of the production keyforms.write support the seven non-empty combinations of positions, drawOrder, and opacity. The stored opacity of the target ordinary form must be known, the input must be finite in 0..1, and an omitted field keeps the expected value. The Runtime admits the existing reviewed opacity read and write selectors independently, and after the complete CAS and every target precheck it modifies the form through the real stored form in one keyform contribution; the admission of the original geometry write does not fail because an opacity selector is missing.

Cross-object transactions, complete read-back, and the operation result and replay keep the existing protocol. The default, shared, and Morph protection and the budget stay, and an opacity batch carries every retained target vertex; it calls no current-pose setter, moves no parameter, and saves no model. The catalog stays at 44 tools, 15 resources, 2 templates, and 8 prompts, with 14 owners in the limited coverage ledger. See the latest record of [verification.md](verification.md) for the verification scope; colour, Warp and Rotation appearance, automatic art, and real-model undo, redo, and save-reopen are still incomplete.

## 24. Later Implementation: Per-Operation Discovery and the Session Type-Chain Fix

ordinary_write_support gained an immutable, closed operationSelectors that reports separately the selector observation of ArtMesh vertices, order, joint values, joint values with opacity, Warp control points, and the Rotation form. A missing entry is UNKNOWN, the old SDK constructor stays and yields an empty map, and the permission decoration keeps the operation information. Opacity is checked independently and is never inferred as admitted from the geometry writerSelectors.

A dynamic session that dropped the capability query now forwards it, and the general deformers.find and all keep the real Warp and Rotation subtype; the former plain wrapper hid the typed form API MCP needs. Generation protection, the plain fallback, host order, object unwrap, and the general collection appearance identity routing stay. The MCP schema, the three-language description, and the limited coverage row were synchronized, and the tool count stays 44. Complete admission, real model and host, and save-reopen acceptance are still incomplete; see the latest entry of [verification.md](verification.md) for the exact test scope.

## 25. Multi-Axis Rotation Stored Angle Planning (2026-10-25)

The read-only planning domain of the existing turboism.rig.rotation.plan was extended. The old single-axis parameterGuid and nativeAnglePerParameterUnit still work; a new mutually exclusive axes input takes 1 to 3 explicit GUIDs and gains that must cover every ordinary axis, with no inference from a display ID, an object name, or the input order. Each axis needs at least two unique in-range keys including the exact default; the combination grid must be complete and independent with at most 65 cells and 64 non-default edits. For every non-default cell the target angle is the stored angle of the all-axis default plus the sum of (cellKey minus axisDefault) times gain, summed in double in the native snapshot axis order and converted to float once. Every target pivot, scale, reflection, baseAngle, default, and unrelated form, axis, and cell is preserved.

This lets an Agent generate a closed ordinary write request for an existing multi-axis Rotation binding instead of being limited by the old single-axis template. It is angle scalar superposition, not a composition of several deformer rotations, an XYZ semantic inference, or parent-child compensation. The ordinary writer still runs its pure precheck with the complete before and after state, parent, document, and history verification, the 128 KiB output budget, and the separate write admission and CAS; planning calls no setter, moves no pose or selection, renders nothing, and starts no transaction. It adds no tool, SDK selector, or native write domain.

The 14 focused cases and the complete 518 MCP cases passed with zero failures or errors (1 headless UI case skipped); format and packaging passed. See the implementation verification record for the source. Real multi-axis interpolation, head volume and eye-mouth coordination, visuals, and persistence are still not accepted; Cubism was neither started nor connected and no model changed, so Milestone A and auto-modelling completion must not be ticked on this basis.

## 26. ArtMesh and Warp Three-Axis Anchors and Combination Residuals (2026-10-25)

The existing rig.mesh.plan and rig.warp.plan were extended to an explicit one to three ordinary axes. Each axis supplies a negative and a positive endpoint anchor, and the GUIDs must cover every real binding; the default form is the reference and each axis displacement is superposed by the ratio from the default to the declared endpoint. The complete independent Cartesian grid, the exact default and both-side keys, and the limits of at most 65 cells, 64 edits, 4096 plan points, and 240 KiB are kept. Head XYZ is never guessed from a name, and no key point is added and no binding or topology changed.

cornerCorrections may name two or three different bound GUIDs and a direction, and the residual weight multiplies only the same-side normalized ratio of the declared axes. A two-axis residual keeps every key along the undeclared third axis, and a three-axis residual is zero on any default face. Three axes allow at most 12 two-axis quadrants plus 8 three-axis octants, that is 20 unique direction mappings; a duplicate mapping, a single-axis residual, and an unknown GUID are refused. The old two-axis input semantics are kept. The default and unrelated forms, the source positions, UVs, and indices, the stored draw order and observed opacity, and the Warp grid and quadTransform are preserved.

The plan reuses the pure precheck of the existing ordinary writer and may be passed unchanged as proposedEdits of validation.geometry or validation.warp for an in-memory diagnosis before keyforms.write is submitted separately. The native, parent, document, and history verification before and after, the transaction, complete CAS, and write admission boundary are unchanged; planning and diagnosis call no setter, move no pose, render nothing, and start no transaction. The catalog stays at 44 tools and 14 limited owners.

The 56 focused cases passed with zero failures, and the complete 526 MCP cases passed with zero failures or errors (1 headless UI case skipped), with format and packaging passing. A three-axis stored template proves no real interpolation, perspective or volume, eye-mouth or occlusion coordination, or visual quality; the host was neither started nor connected, no real model changed, and no new host acceptance was added, so Milestone A and the ongoing goal remain incomplete.

## 27. Local Colour and Difference Checks on Retained Pose PNGs (2026-10-25)

A read-only turboism.validation.raster was added, so the catalog now holds 45 tools, 15 resources, 2 templates, and 8 prompts, with 14 limited owners. For a pose-grid operationId that is terminal and not released in this MCP session, it checks 1 to 32 explicit local PNG regions. color_fraction counts the colour share over an inclusive RGBA byte range; frame_difference counts changed pixels where the maximum channel difference of the premultiplied sRGB RGB strictly exceeds the tolerance. Both take the explicit fraction bounds and the ROI from the caller and never guess the face, eye, mouth, or background position.

The ROI uses the top-left pixel coordinates of the cropped PNG; a difference comparison requires the complete native raster and crop metadata to match and never aligns or resamples. The counts and the decimal thresholds are compared exactly, and the result keeps the actual configuration, the counts, the ratios, the per-item passed flag, and checksPassed. checksPassed covers only the explicit pixel assertions, and ok means only that the check executed successfully, not that the model, the art, or every visual check passed. The report links the captureId and hash, the pose and state, and the crop of the capture moment, with imageContentIndex null and no copied image or base64. A partial scan may check the frames really retained, but scanOutcome, parametersRestored, and allRequestedPosesCaptured are reported separately, so a partial capture or a failed restore is never hidden as success.

Execution touches no host, moves no parameter, re-renders nothing, and writes nothing. Session isolation, the terminal, release, and frame identity protection, the PNG length, SHA-256, and real size verification, and the budgets of 4194304 ROI samples, 1048576 decoded pixels, 3 MiB source PNG, and 240 KiB report apply. A started read-only analysis holds a terminal copy, so a later release neither turns it into a new capture nor loses the correspondence; the tool caches no pixel long term. A historical image does not prove the freshness of the current model.

Eight regressions were added, including the 31 focused cases of the ledger; the final 534 MCP cases passed with zero failures or errors and 1 headless UI case skipped, with format and packaging passing. The real renderer, the vision model, Swing, and the closed-eye white gap and mouth artefact checks on an official copy are still not accepted, and Cubism was neither started nor connected and no real model changed. This slice provides locally reviewable visual evidence and does not replace the semantics, the art algorithm, or the complete Milestone A gate.

## 28. Warp Perspective Template from an Explicit Head Depth Field (2026-10-25)

rig.warp.plan gained the PROJECT_HEAD_VOLUME_FROM_DEFAULT policy, which uses the centre, radius, depth, and camera distance of headVolume plus the explicit degree gain from a real ordinary axis GUID to YAW, PITCH, and ROLL; it is mutually exclusive with the original anchor and cornerCorrections mode. Every axis must be covered and each component unique, and no name, side, pivot, or coordinate space is inferred. The template defines a frontal depth field along the ellipse of the default stored control points, lifts the image point along the camera ray, then applies yaw (+Y), pitch (+X), and roll (+Z) in order, then projects in perspective; the formulas, axis signs, and limits are in the three-language MCP description and the implementation checklist.

This is an image-defined approximate depth field that helps an Agent generate a reviewable head-turn candidate; it is no 3D reconstruction and no automatic rig of an arbitrary illustration. The default image is preserved by the ray lift, and a zero angle copies the original control points directly; a non-default target is rebuilt from the default form and replaces the existing target geometry without accumulating displacement. The default, unrelated forms, grid, quadTransform, and bindings are preserved. It reuses the complete independent grid and the pure precheck of the ordinary writer, the existing budget, and the before and after native, parent, and history verification, returns a separate keyforms.write request, and may be diagnosed first through proposedEdits of validation.warp.

Construction verifies that the profile is finite, the radius positive, the depth non-negative, cameraDistance above depth, and the remaining distance at least 1%; each cell limits yaw and pitch to ±85° and roll to ±180° and refuses a post-rotation near-plane or non-finite denominator, float overflow, a missing axis, a duplicate component, and a shared or incomplete grid. Planning moves no pose, writes nothing, renders nothing, and starts no transaction. The caller must still confirm the head Warp, the parent coordinates, the profile, the axis signs, and the child fit, and check the real rendering, occlusion, eye and mouth, and volume.

The 34 focused cases passed; the complete 540 MCP cases passed with zero failures or errors and 1 headless UI case skipped, with format and packaging passing. The tool count stays 45 and the limited owners 14. There is no SDK, Runtime, or ACP change, no Cubism start, connection, or model change, and no new host evidence, so Milestone A and the ongoing auto-modelling goal stay incomplete.

## 29. Pre-Write Preview of Ordinary Keyforms Across Objects (2026-10-25)

A read-only turboism.authoring.keyforms.preview was implemented, so the catalog is **46 tools, 15 resources, 2 templates, and 8 prompts** with 14 limited owners. It accepts exactly the typed request of write, shares the complete authoring, parent, and history CAS, the ordinary form support domain, and the budget, and re-verifies the state after computing. It returns requestHash, the changed fields of each object and form, the point count and the maximum displacement in native space, the drawOrder, opacity, angle, pivot, scale, and reflection differences, and the before and expected-after hashes. It returns no complete coordinates and composes no cross-object distance.

applied=false, readbackVerified=false, and writeAdmission=NOT_CHECKED. A preview resolves no transaction service, writes no model, moves no parameter, renders nothing, and reserves no operation ID or invalidated snapshot. An admitted ID must query its result; submitting the same request after a successful preview still re-executes the live CAS and write admission. A pure drawOrder budget point count is zero, and an opacity or joint batch keeps its vertices under the real writer budget. The limits are 8 objects, 64 forms, 16384 budget points, 768 KiB input, and 240 KiB preview. It never replaces geometry or visual QA, write authorization, or host acceptance.

Five Domain tests were added; the final MCP **545 cases passed with 0 failures or errors and 1 headless UI case skipped**, and the format check and JAR passed. They cover a hash match between the preview of the three object classes and the real simulated write, repeated previews reserving no ID, zero setter or transaction queries, the complete state change, and the cross-session, default, shared, Morph, and stale-snapshot refusals; the existing write and rollback suite stays. Cubism was neither started nor connected, and there is no real model change or host undo, redo, or save-reopen evidence. A and the ongoing auto-modelling goal remain incomplete.

## 30. Binding API for Existing Model Images (2026-10-25)

turboism.assets.binding.read, bind, and result were added, so the production catalog is **49/15/2/8** with 14 limited SDK owners. Based on a real meshes.read authoringSnapshotId, it observes the current image or atlas input or a real unbound state, reads up to 1024 unique model image candidate metadata, and issues an immutable session token. The cache keeps 5 minutes, 16 items, and 16 MiB and counts against the retained native authoring state; a read response is at most 240 KiB. A candidate name and size are no proof of semantic layering or pixel usability.

bind accepts only a discovered existing modelImageId and an ArtMesh with no parent deformer and no lock, and it verifies the complete native geometry, forms, and source GUID, the parent, the lock, the history, the original input including modelGeneration, and the candidate metadata; the same image yields NO_CHANGE without calling the native bind, and a different image or atlas is refused. Inside an independent SDK transaction it runs CAS again and verifies that the returned ModelImageBinding matches the real textureInput in model, image, mesh, and generation, and that the geometry, UV, forms, parent, and candidate are unchanged. A failure inside the transaction is rolled back by the SDK or explicitly reported as recovery failed or unknown; a read-back failure after commit keeps APPLIED_WITH_READBACK_WARNING and never falsely reports NOT_APPLIED. Native permission, exact host, ancestor lock, and extension admission still fail closed, with no automatic unlock, rebind, or bypass.

The result is session history evidence of at most 256 admitted operations with no eviction; a replay queries no host and binds nothing again, a conflicting ID is refused, and a timeout queries result first. The hash covers the document, model, mesh, and source GUID plus the input resource identity and excludes generation and geometry, which have their own exact CAS. A successful bind invalidates that binding token. An HTTP write needs Bearer and a result query is read-only. The existing textureInput and bindModelImage ledger rows were corrected to the real entry; authorized PNG registration follows in section 31; PSD is still not open, so the existing image binding interface must not be called a complete material-from-scratch chain.

Twelve regressions were added; the final MCP **557 cases passed with zero failures or errors and 1 headless UI case skipped**, and the format, packaging, and diff checks passed. There is no new SDK, Runtime, or ACP change, no host connection this round, and no real image, GPU, undo, redo, or save-reopen evidence, so the auto-modelling goal and Milestone A remain incomplete.

## 31. Authorized PNG Registration, Binding, and Task Result (2026-10-25)

assets.images.register, result, and cancel were added, so the current workspace catalog is **52 tools, 15 resources, 2 templates, and 8 prompts** with 14 limited owners. Based on a one-shot READ png grant of the same session, it asynchronously reads a complete PNG of up to 16 MiB and verifies the header against an explicit maxPixels (at most 16777216 with each side at most 8192); an expectedSha256 may pin the bytes of the real immutable AuthorizedImageContent. fileEvidence returns the hash, size, and byte count, fileStability=UNPROVEN, no path, no automatic file-name leak, and no base64. The native Runtime owns the real decode plus the permission, undo, extension, ancestor lock, and exact host admission.

The model precheck runs before the grant is consumed, and after the read the snapshot TTL and the complete native authoring, parent, lock, input, catalog, and history CAS are re-verified before the native transaction. Inside the independent SDK transaction exactly one raw image, image group, or model image must be added, with an explicit name and size and a binding of model, mesh, and generation matching the real typed observation; the old metadata, UV, topology, and forms stay, and a deviation triggers recovery. binding.read gained groupCount so an empty group change is not missed. The result writeResult keeps the rawImageId, the real textureInput, and the history receipt, while the metadata hash covers no pixel or disk stability and the geometry has its own complete CAS.

The task returns immediately and can be queried, keeps at most 128 operationIds without eviction, and uses a single global I/O, worker, and host slot until the real completion. A cancel takes effect before the final native transaction admission; after hostAdmitted it keeps reporting the real commit, rollback, or unknown, and an Agent abort, a disconnect, a closed session, or a stopped service is never called a host cancellation. A pending READ lease closes only after the real read finishes; closing the session removes result access, and a missing result is no proof that the model is unchanged. A post-commit read-back failure warning and a lost-response unknown are never retried blindly. The result keeps no PNG bytes and takes no part in transaction.execute.

Fourteen offline Domain cases were added; the final MCP **571 cases passed with 0 failures or errors and 1 headless UI case skipped**, and the format, JAR, and diff checks passed. The production catalog crossed 50 items for the first time, and the HTTP integration really walked nextCursor; the Pi simulation verified the 52-tool pagination with a second-page query and cancel execution, and the existing resource, image, permission, HTTP SSE, and negotiation verification passed. No SDK, Runtime, or ACP Java changed, the host was not started, and there is no real material import, pixel or GPU, undo, redo, or save-reopen evidence. This chain is the PNG tool foundation; PSD, topology, atlas, delivery, and the A and B host gates are still pending, and the ongoing auto-modelling goal stays active.

## 32. Claude Standard Observation Pre-Check (2026-10-26)

The test-only Claude native route gained a pinned runtime, tool isolation, and a random image canary; the restricted CLI handshake, the standard package, and the offline self-check passed. After the fix the task was prepared, but the host submission was refused by automatic approval and waits for explicit authorization to send private test renders and metadata to the external service configured in the existing Claude CLI; there was no second queue execution and no claim of a real vision pass. See [section 63](verification.md#63-claude-standard-observation-entry-and-submission-authorization-boundary) and the [evidence summary](claude-observer-preflight-20261026.json) for detail. The overall goal and Milestone A remain incomplete.

## 33. Host Reconciliation of a Lost Write Response (2026-10-26)

The standard head-edit task dropped one keyform write HTTP response in the Hiyori test copy and queried the production result twice with the same operationId to obtain a stable receipt; the three exact read-backs, undo and redo, the final restore, and the queue file, identity, golden, and safe cleanup checks passed. The client and the isolated queue passed 182 cases. See [section 64](verification.md#64-real-keyform-write-response-loss-and-same-session-result-reconciliation) and the [summary](live-host-20261026/head-response-loss.json). This is same-session receiver-fault evidence, not a host crash or cross-session recovery, real Agent vision, or complete modelling pass; the external model authorization still awaits a reply.

## 34. ACP rawOutput and the Real Window (2026-10-26)

A fix covers an ACP tool result that returned only rawOutput and lost the typed image, keeping the receipt text and the typed image and preferring the existing content so images are not mixed. The 252 Xvfb ACP cases (249 passed, 3 opt-in skipped) and the scripted proxy self-check passed; the standard host task verified that the native PNG matches the Swing full ARGB, the source label is complete, no base64 text appears, and the original, identity, golden, exit, and cleanup checks pass. See [section 65](verification.md#65-acp-rawoutput-result-fallback-and-real-window-verification) and the [summary](acp-raw-output-20261026.json). It covers no real external model consumption or durable task recovery; the goal remains incomplete.
