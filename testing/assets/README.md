# Test Assets (Test Corpus and Model Assets)

This directory manages the manual test models, PSD files, and 3D geometry fixtures for Turboism. The repository keeps the Git tree small and blocks large or private assets, so it uses a **manifest-driven, versioned release distribution** strategy.

- **Small geometry fixtures (< 1 MB)**: committed directly to version control with `storage: git`.
- **Large binary files (CMO3, PSD, CAN3, and similar)**: **never committed to Git**. `testing/assets/corpus.json` registers each file (name, byte size, SHA-256 hash), the binary archives publish to GitHub Releases, and `scripts/test_corpus.py` pulls them on demand into a shared local cache and maps them into the workspace.

## Directory Structure

```text
testing/assets/
├── .gitignore                     # Excludes locally fetched cmo3 and psd binaries
├── README.md                      # Corpus overview (this document)
├── corpus.json                    # Corpus manifest (Schema v1)
├── texture-atlas-layout/          # Texture atlas layout corpus (docs in Git, binaries on Releases)
│   ├── README.md                  # Dataset usage and license
│   ├── BUILDING.md                # Test model build and reproduction notes
│   ├── atlas-manifest.md          # Baseline archive checks and the 17-file manifest
│   ├── circle-manifest.md         # Circle 8-sample manifest and PSD specs
│   ├── geometry-manifest.md       # Geometry 9-sample manifest and PSD specs
│   ├── cmo3/                      # Local fetch/extract target (Git-ignored)
│   └── psd/                       # Local fetch/extract target (Git-ignored)
└── model-import-3d/               # Minimal self-built GLB fixtures (tracked by Git)
    ├── README.md                  # Geometry specs, provenance, and build notes
    ├── cube-985496727e50/
    ├── cube-textured/
    └── plane-textured/
```

## Dataset List

| Dataset ID | Version | Storage | License | Files | Total bytes | Notes |
|---|---|---|---|---:|---:|---|
| `texture-atlas-layout` | `1` | `release` | CC0-1.0 | 17 | 123,517,686 (~118MB) | Circle and Geometry texture atlas current-page layout corpus (9 CMO3 + 8 PSD) |
| `model-import-3d` | `1` | `git` | CC0-1.0 | 4 | 13,544 (~13KB) | Self-built minimal cube and plane GLB fixtures (embedded textures and a diagonal seam case) |

## Corpus Tool (`scripts/test_corpus.py`)

`scripts/test_corpus.py` is a standard-library-only corpus tool. It fetches, verifies, packs, and reports status.

### 1. Fetch a Corpus (`fetch`)

Download a dataset from GitHub Releases, verify the archive and per-member SHA-256 values, extract it into the shared cache, and map it into this workspace:

```sh
# Fetch one dataset
python3 scripts/test_corpus.py fetch texture-atlas-layout

# Fetch every release dataset
python3 scripts/test_corpus.py fetch --all

# Offline or local test: fetch from a local archive and verify after extraction
python3 scripts/test_corpus.py fetch texture-atlas-layout --from-file build/test-corpus-release/texture-atlas-layout-v1.tar.gz

# Copy files instead of symlinking
python3 scripts/test_corpus.py fetch texture-atlas-layout --copy
```

- **Shared cache directory**: defaults to `${XDG_CACHE_HOME:-~/.cache}/turboism/test-corpus/<dataset>@<version>/`. Multiple local worktrees share one cache, so the 118MB corpus is stored once.
- **Workspace mapping**: by default the tool creates symlinks into the cache and keeps the existing relative paths `testing/assets/texture-atlas-layout/{cmo3,psd}/...`, so existing unit tests and automation scripts need no path change.

### 2. Verify a Corpus (`verify`)

Check that each corpus file in the workspace exists and that its size and SHA-256 match the `corpus.json` manifest:

```sh
# Verify one dataset
python3 scripts/test_corpus.py verify texture-atlas-layout

# Verify every dataset, including Git-tracked and release-fetched entries
python3 scripts/test_corpus.py verify --all
```

### 3. Report Status (`list`)

Show the storage mode, license, file scale, and local readiness of every registered dataset:

```sh
python3 scripts/test_corpus.py list
```

## Private Asset Policy

Turboism enforces strict repository hygiene:

1. **Absolute prohibition**: never commit private user project assets (such as unreviewed private models or personal illustration PSDs), unauthorized third-party 3D models (such as third-party cat or apple meshes), or any binary that is not on the official test manifest.
2. **Enforced gates**:
   - The root `.gitignore` and `testing/assets/.gitignore` exclude every `*.cmo3`, `*.can3`, `*.cmo3.bak`, `*.psd`, and `*.moc3` file.
   - `scripts/check_remote_hygiene.py` fails closed on those binary suffixes in pre-commit, pre-push, `devCheck`, and CI gates. Force-staging with `git add -f` still cannot commit them.

## Adding a Test Dataset

1. **Confirm license and provenance**:
   - The corpus needs a clear, compatible open-source license (such as CC0 1.0, MIT, or Apache-2.0). Never include private assets or assets with unclear licensing.
2. **Register metadata in `testing/assets/corpus.json`**:
   - Declare `id`, `version`, `license`, `storage: "release"`, `targetDir`, and `usedBy`.
   - Measure and record the relative path, byte size, and SHA-256 hash of every file.
3. **Pack a deterministic archive (`pack`)**:
   - Generate a reproducible archive from a prepared local source directory with the pure-Python packer, which fixes mtime, uid, gid, and file ordering:
     ```sh
     python3 scripts/test_corpus.py pack <dataset-id> --source <source-dir> --out build/test-corpus-release/
     ```
   - The packer compares the source directory against the manifest and refuses to pack when the source holds files outside the manifest, which prevents accidental contamination.
4. **Submit for maintainer review and release**:
   - Developers must not create GitHub tags or upload release assets on their own.
   - Submit the packed archive and manifest for maintainer review. After approval, the maintainer publishes the versioned release asset with `gh release create`.
5. **Backfill the manifest and commit the docs**:
   - Write the published archive SHA-256 and byte size back into the `release` field of `corpus.json`.
   - Write the dataset-specific `README.md` and `BUILDING.md` with reproduction and build instructions.
   - Commit the manifest and documentation updates and pass the gates.
