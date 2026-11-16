# Circle Dataset Manifest

## Provenance and Integrity

- User-provided `/tmp/circle.7z` for the current texture layout test. It grants no public redistribution right.
- Archive size: 23,786,928 bytes.
- Archive SHA-256: `bcdb2acc1c70879440edd3701e783e18210900cb1ef4cb85c8a18cd496cec9c5`.
- `7z t`: passed. One directory and 8 files, 57,108,863 bytes after extraction.
- Entry paths were checked first, then extracted into an isolated temporary directory. The archive was created exclusively and every copy was checked against its SHA-256 before and after the copy. The source archive and the existing samples stayed unchanged.
- The staging directory path is recorded locally in `/tmp/texture-circle-stage-path`. The official input samples are the paths relative to this directory in the table below.

## File Manifest

| File | Bytes | SHA-256 |
|---|---:|---|
| `psd/circle/atlas_mapping_100.psd` | 1836082 | `6501bb1ac2d79d43eaaf917d552e5fa6ffa645ab3c49ca6f5a733cb4dd5b6b78` |
| `cmo3/circle/atlas_mapping_100.cmo3` | 850522 | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` |
| `psd/circle/atlas_mapping_500.psd` | 4044916 | `3ebb782d0dc0dfee5774029f674f953ae3acfd42812c692c01845b81c5bf2d8d` |
| `cmo3/circle/atlas_mapping_500.cmo3` | 3538094 | `54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50` |
| `psd/circle/atlas_mapping_1000.psd` | 6707149 | `89244d4934048c9a7eee22c6ab94c050124d4781412d99b134b6171275017f05` |
| `cmo3/circle/atlas_mapping_1000.cmo3` | 7731906 | `5a1a4d0e1f27dbffe09eafc5d5f8626776bcad35fab8fcd06c6ecc34b68959f9` |
| `psd/circle/atlas_mapping_2500.psd` | 13708952 | `04a42c09dd0feaf64bc1352024786a87712381283f8f9eb2f42ad9c66b2e8a1e` |
| `cmo3/circle/atlas_mapping_2500.cmo3` | 18691242 | `3cbd3d8ef91166010c90d5f96d872ef527841b36aadb9e634368cab3d159a239` |

## Verified and Unverified

- PSD: signature `8BPS`, version 1, 4096×4096, 8-bit, RGB, 4 channels. Skipping the Color Mode and Image Resources sections and reading the signed layer count from Layer Info gives absolute values of 100/500/1000/2500. Per-layer pixel decoding and visibility checks are still pending.
- CMO3: all four file headers are `CAFF`. Only byte integrity and hash checks are complete; the files have not been opened in Cubism.
- Never treat a file name or a PSD layer count as evidence of the actual current-texture-page input count, the texture size, a successful model open, or a passed performance acceptance.
- Real-host write operations must create a separate run copy and keep the input samples unchanged.
