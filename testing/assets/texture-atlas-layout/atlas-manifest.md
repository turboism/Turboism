# Atlas 基准工程包

用户于2026-09-22提供 `atlas.7z` 作为测试基准工程。压缩包61,452,640字节，SHA-256 `b2478390cacbb17abb86d972e5ad17f5701ed6a593e09dff620de72bc6956792`。

条目路径及普通文件类型检查通过，`7z t` 完整性检查通过。在独立临时目录解压后，按文件类型和数据集复制到本目录的 `cmo3/{circle,geometry}/`、`psd/{circle,geometry}/`；复制前后哈希一致。共17份文件、123,517,686字节：9份 CMO3、8份 PSD。

- Circle 的8份文件与 [circle-manifest.md](circle-manifest.md) 的大小、SHA-256 全部一致。
- Geometry 的8份既有文件与 [geometry-manifest.md](geometry-manifest.md) 的大小、SHA-256 全部一致，包含2500 CMO3。
- 新增文件如下，尚未实机核验其当前页输入数。

| 路径（相对此用途目录） | 字节 | SHA-256 |
|---|---:|---|
| `cmo3/geometry/atlas_mapping_geometry_500x2.cmo3` | 6978687 | `117f54e98f1ef37d935a006ea90ae81cf71e6d99e658074f47ad0278e41cf3e2` |

固定 Geometry100 输入位于 `cmo3/geometry/atlas_mapping_geometry_100.cmo3`，694,856字节，SHA-256 `369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff`，与统一队列的固定模型要求一致。

所有 CMO3 文件头为 `CAFF`，PSD 文件头为 `8BPS`。字节完整性不能替代宿主验收；实机操作须使用任务专属副本，保持基准文件不变。资产沿用本目录的本地测试约定，二进制由 Git 忽略，清单随代码保存。
