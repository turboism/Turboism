# 0.70.0 cumulative release-note review

Status: draft inventory, not final approval.

## Baseline

Use the user-selected previous stable reference: 0.67.4 / Build 56.
Git tag `v0.67.4` resolves to `5e0f8b02cf644d9f35e836d9d9712af21422434e`.
The tag is an ancestor of the preparation branch.
The Build 56 label comes from the acceptance plan, not a new host observation.

Inventory head: `9dad69b7f`.
The tag-to-head range contains 4,360 non-merge commits and 1,106 first-parent entries.
These counts establish review scope; they do not establish complete commit-by-commit review.
Raw inventories remain in the ignored acceptance directory.

Existing `CHANGELOG.md` separates Unreleased changes from the 0.68.1 entry.
The 0.68.1 entry explicitly incorporates 0.68.0 beta changes.
The 0.70.0 stable comparison must include both sections rather than only the latest beta delta.
Do not infer publication status solely from a committed changelog heading.

## Cumulative content inventory

| Topic | Source anchor | Release-note treatment | Current acceptance limit |
|---|---|---|---|
| Combined framework/plugin update window and signed framework updates | `CHANGELOG.md`, 0.68.1 Added | Carry into the cumulative comparison; retain receipt and managed-launch requirements. | Real-host update and recovery remain pending. |
| Parameter synchronization and batch deformer rename | `CHANGELOG.md`, 0.68.1 Added | Include official bundling and user-visible workflows. | Rename host acceptance remains pending. |
| Parameter self-inversion | `CHANGELOG.md`, 0.68.1 Added | Include in-place self-targeted transfer without a temporary parameter. | Real editing and Undo/Redo remain pending. |
| Geometric mesh layout | `CHANGELOG.md`, 0.68.1 Added; `caea9a2bc` | Include the preview feature and host-owned dialog correction. | Do not generalize focused tests into complete host acceptance. |
| Recent-project board and HiDPI previews | `CHANGELOG.md`, Unreleased Added; PR #169 | Include layouts, persisted layout control, native viewport integration, and cached thumbnails. | Layout and genuine empty-state host checks remain pending. |
| Shared project work-time tracking | `CHANGELOG.md`, Unreleased Added; PR #163 | Include foreground-session and cross-session project totals. | Final source-bound host matrix remains pending. |
| Marketplace counts and filters | `CHANGELOG.md`, 0.68.1 Added | Carry the user-visible listing changes into the stable comparison. | Do not imply all marketplace branches passed. |
| Long marketplace explanations | `daa133535`, PR #176 | Describe truncation at 2,000 Unicode code points and localized README links only after truncation. | Actual truncated-branch UI acceptance remains pending. |
| Parameter-sync persistence and dialog behavior | `caea9a2bc` | Include immediate persistence, removed footer buttons, and late-load edit protection. | Preserve the previously recorded test and host evidence boundaries. |
| Mirror reset gesture | `caea9a2bc` | Describe consumption of the release that belongs to a double-click reset. | Do not claim unrelated mirror workflows changed. |
| UI scheduling during host delays | `73aaa5610`, `92944d11d` | Describe the UI/lightweight exemption without claiming removal of queue limits. | Shared admission, bounded workers, heavy-work policy, and shutdown checks remain intact. |
| Windows installer | `362f9e853`, `ad2adef20` | Include console verification repairs, native compile paths, visible amd64 labels, and corrected strategy header. | Native selection/README interaction passed; the GUI run canceled before installation. |
| Swing log filtering | `7bb24a557` | Include canonical document separators and preserved serialized text. | The focused 28-test suite passed; final aggregate gates remain pending. |
| SDK capabilities and deprecations | `CHANGELOG.md`, Unreleased Added/Deprecated | Include preview capture, host-view panels, mesh sessions, and consolidated mirror/edit services. | Retain exact replacement APIs and distinguish no-op adapters from working compatibility adapters. |
| Korean localization and marketplace publication split | `CHANGELOG.md`, 0.68.1 Added/Changed | Carry documentation and independent-market publication changes into the stable comparison. | Do not equate bundling with marketplace-only publication. |
| Persistence and architecture hardening | PR #174 and subsequent source contracts | Summarize user-visible safety separately from developer-only refactoring. | Review the full diff before claiming final coverage or unchanged compatibility. |
| Texture-upload preparation and Performance settings | PR #172; `README-texture-upload-preparation.md` | Describe the opt-in feature and settings placement without an overall speed or memory promise. | Current-build performance and version-matrix acceptance remain pending. |

## Mandatory qualifications

### GIF deferral

The user explicitly deferred all GIF acceptance and repeated-input investigation.
The feature remains in source and the existing Unreleased changelog.
That source presence does not establish release readiness.

The release-preparation PR must disclose the deferral.
Do not describe the missing-strip investigation as fixed or accepted.
The constructor bridge does register preview surfaces.
The input source remains unresolved; do not resurrect the unsupported internal-loop diagnosis.

### Performance evidence

Texture-upload preparation remains default-off and opt-in.
The experiment documentation distinguishes conversion-only speed from end-to-end document loading.
It also records adverse or variable memory observations.

Do not promote conversion microbenchmarks into general loading, RSS, VRAM, or FPS claims.
Historical pinned agents do not automatically validate the newly integrated source or this candidate.
Keep pixel, geometry, rendering, lifecycle, and normal-exit checks unchanged.

### Compatibility and version identity

Declared support spans reviewed Cubism versions 5.0.00 through 5.3.04.
The representative minimum/maximum patch matrix still needs final acceptance.
Declared compatibility does not replace those executions.

The current builds use explicit 0.70.0 version overrides.
Confirm the canonical source version before final completed-tree and candidate/source binding gates.
Do not rename changelog sections or generate final localized release metadata before that review completes.

## Remaining review work

- Complete the source-diff audit for the cumulative stable range.
- Resolve all non-deferred acceptance items and document their exact scope.
- Prepare the final English changelog section and matching localized metadata.
- Verify English-body hashes and locale bindings through the existing release-note tools.
- Recheck the final completed source tree and signed candidate.
- Create the actual release-preparation PR through an authenticated GitHub API identity.

`gh auth status` currently reports no authenticated hosts.
A local PR body does not establish PR creation.
No publication or final release-note approval has occurred.
