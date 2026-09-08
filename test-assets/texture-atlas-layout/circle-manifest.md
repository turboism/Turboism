# Circle Dataset Manifest

## 来源与完整性

- 用户提供：`/tmp/circle.7z`，用于当前纹理排版测试，不据此授予公开再分发权。
- 压缩包大小：23,786,928 bytes。
- 压缩包 SHA-256：`bcdb2acc1c70879440edd3701e783e18210900cb1ef4cb85c8a18cd496cec9c5`。
- `7z t`：通过；1目录、8文件，解压总大小57,108,863 bytes。
- 先检查条目路径，再解压到独立临时目录；归档使用排他创建，逐份核对复制前后的 SHA-256。源压缩包与已有样本未修改。
- 暂存目录位置记录在本地 `/tmp/texture-circle-stage-path`；正式输入样本为下表相对本目录的路径。

## 文件清单

| 文件 | Bytes | SHA-256 |
|---|---:|---|
| `psd/circle/atlas_mapping_100.psd` | 1836082 | `6501bb1ac2d79d43eaaf917d552e5fa6ffa645ab3c49ca6f5a733cb4dd5b6b78` |
| `cmo3/circle/atlas_mapping_100.cmo3` | 850522 | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` |
| `psd/circle/atlas_mapping_500.psd` | 4044916 | `3ebb782d0dc0dfee5774029f674f953ae3acfd42812c692c01845b81c5bf2d8d` |
| `cmo3/circle/atlas_mapping_500.cmo3` | 3538094 | `54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50` |
| `psd/circle/atlas_mapping_1000.psd` | 6707149 | `89244d4934048c9a7eee22c6ab94c050124d4781412d99b134b6171275017f05` |
| `cmo3/circle/atlas_mapping_1000.cmo3` | 7731906 | `5a1a4d0e1f27dbffe09eafc5d5f8626776bcad35fab8fcd06c6ecc34b68959f9` |
| `psd/circle/atlas_mapping_2500.psd` | 13708952 | `04a42c09dd0feaf64bc1352024786a87712381283f8f9eb2f42ad9c66b2e8a1e` |
| `cmo3/circle/atlas_mapping_2500.cmo3` | 18691242 | `3cbd3d8ef91166010c90d5f96d872ef527841b36aadb9e634368cab3d159a239` |

## 已验证与未验证

- PSD：签名 `8BPS`、版本1、4096×4096、8-bit、RGB、4通道。直接跳过 Color Mode / Image Resources 段后读取 Layer Info 的 signed layer count，绝对值分别为100/500/1000/2500；尚未逐层解码像素或核对可见性。
- CMO3：四份文件头均为 `CAFF`；只完成字节完整性与哈希核验，尚未在 Cubism 中打开。
- 不将文件名或 PSD layer count 当作当前纹理页实际输入数、纹理尺寸、模型可正常打开或性能验收通过的证据。
- 实机写操作必须另建运行副本，输入样本保持不变。
