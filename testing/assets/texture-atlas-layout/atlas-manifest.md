# Atlas Baseline Project Archive

On 2026-09-22 the user provided `atlas.7z` as the baseline test project archive. The archive is 61,452,640 bytes with SHA-256 `b2478390cacbb17abb86d972e5ad17f5701ed6a593e09dff620de72bc6956792`.

Entry path and regular-file type checks passed, and the `7z t` integrity check passed. After extracting into an isolated temporary directory, the files were copied by type and dataset into `cmo3/{circle,geometry}/` and `psd/{circle,geometry}/` under this directory. Hashes matched before and after the copy. The total is 17 files and 123,517,686 bytes: 9 CMO3 and 8 PSD.

- The 8 Circle files all match the sizes and SHA-256 values in [circle-manifest.md](circle-manifest.md).
- The 8 existing Geometry files all match the sizes and SHA-256 values in [geometry-manifest.md](geometry-manifest.md), including the 2500 CMO3.
- The newly added file follows. Its current-page input count is not yet verified on a real host.

| Path (relative to this asset directory) | Bytes | SHA-256 |
|---|---:|---|
| `cmo3/geometry/atlas_mapping_geometry_500x2.cmo3` | 6978687 | `117f54e98f1ef37d935a006ea90ae81cf71e6d99e658074f47ad0278e41cf3e2` |

The fixed Geometry100 input is `cmo3/geometry/atlas_mapping_geometry_100.cmo3`, 694,856 bytes with SHA-256 `369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff`, which matches the fixed-model requirement of the unified queue.

Every CMO3 file header is `CAFF` and every PSD file header is `8BPS`. Byte integrity cannot replace host acceptance. Real-host work must use a task-specific copy and keep the baseline files unchanged. The assets follow the local test conventions of this directory: Git ignores the binaries and the manifest stays in version control.
