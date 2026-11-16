# Geometry Test Assets

The user provided `/tmp/geometry.7z` for the current test only, with no redistribution. The archive is 11279490 bytes with SHA-256 `09851c72d91be8c51beb7868968ffddae509173fa038661251b64b8c610df0f2`. `7z t` passed; the 7 regular files total 34848642 bytes, the file-name allowlist check passed, and unpacking into an isolated temporary directory then filing by suffix overwrote no file with different content.

**Difference from Circle:** the user states that every Circle model holds same-size circles, while Geometry holds geometric images of differing size and shape. Test and report the two input distributions separately.

Every PSD is 2048×2048, 8-bit RGB, 4 channels; parsing the PSD structure directly confirmed that the layer record counts match the names. The CMO3 headers are CAFF, and the bytes and copy hashes match. The actual host input count and texture page size need measurement; a PSD size does not stand in for them.

| Path (relative to this asset directory) | Bytes | SHA-256 |
|---|---:|---|
| `cmo3/geometry/atlas_mapping_geometry_100.cmo3` | 694856 | `369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff` |
| `cmo3/geometry/atlas_mapping_geometry_500.cmo3` | 3564178 | `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b` |
| `cmo3/geometry/atlas_mapping_geometry_1000.cmo3` | 6701324 | `2f64a7f5fd4f2abc4cdd0036d1943f8382f30581228ea5f87143b5b367abd3e2` |
| `cmo3/geometry/atlas_mapping_geometry_2500.cmo3` | 24581494 | `4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b` |
| `psd/geometry/atlas_mapping_geometry_100.psd` | 946075 | `6aac4d59e30b27800a7eae4039a547f25f4fe6bd0c8db3a2b208180e2cee4f30` |
| `psd/geometry/atlas_mapping_geometry_500.psd` | 3267871 | `ad0c6ab31da87e2a05a497ea735a8878bb93035e7c9518938044cb0ba6117af1` |
| `psd/geometry/atlas_mapping_geometry_1000.psd` | 5850323 | `24f1e83ab7ebb5ea53fd6fb7ad3f55b1114d2ad3c0efe52c3b05a642b09d4dac` |
| `psd/geometry/atlas_mapping_geometry_2500.psd` | 13824015 | `ef210e2bfaf2d03ca770aed07a94d861fd9636d741ed5e33f49f5980e022b8b1` |

The archive initially held only the 100/500/1000 CMO3 tiers and the 2500 PSD. The user then provided `/tmp/atlas_mapping_geometry_2500.cmo3` separately (it is not part of the archive). Its CAFF header, 24581494 bytes, and matching source/copy SHA-256 are verified and archived. The user states that this project holds 2500 model images in one texture; the actual input count still needs host verification. After the three tiers were validated, the user explicitly authorized continuing with 2500, one native run and one new-algorithm run, with no warm-up and no automatic retry.

Create a separate model run copy before testing and keep the input assets and the archive unchanged. Git ignore rules in this directory exclude the binary content.
