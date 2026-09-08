# Texture Atlas Layout Test Assets

## 用途

验证纹理图集当前页自动排版：原生/new/legacy 性能对照、固定及自动缩放、旋转、区域并行、overflow、撤销/重做和保存重开。

## 文件分类

- `psd/`：用于导入和构建测试模型的 PSD 副本。
- `cmo3/`：可直接在 Cubism Editor 中打开的 `.cmo3` 模型副本。
- 模型需要外部资源时，在相应分类内建立同名子文件夹，保留资源的相对路径。

建议使用可识别的英文文件名，例如 `dense-atlas.psd`、`dense-atlas.cmo3`。同一测试案例的 PSD 和模型尽量使用相同名称。

## 测试约定

1. 此目录中的文件作为输入样本；执行排版、保存等写操作前，另外创建运行副本，不覆盖输入样本。
2. 记录样本来源/使用许可、Cubism 版本、初始当前页、页面尺寸、比例、旋转及 margin 设置。
3. 每轮算法对照从相同输入副本开始，不使用上一轮排版结果作为下一轮输入。
4. 不公开或提交未经许可的模型、贴图和 PSD；本目录的 `psd/`、`cmo3/` 内容默认被 Git 忽略。

实机验收协议：[`HOST-CHECKLIST.md`](../../validation/texture-atlas-current-page/HOST-CHECKLIST.md)。

## 样本登记

已接收用户上传的 `/tmp/circle.7z`，解压完整性检查通过，按数据集单独归档，未覆盖分类目录中已有的同名文件。

已接收 `/tmp/geometry.7z`，另归档到 `psd/geometry/` 和 `cmo3/geometry/`，文件清单见 [geometry-manifest.md](geometry-manifest.md)。Geometry 有100/500/1000对应CMO3；2500仅有PSD，不属于本次实机测试范围。

**两套分布不同（用户提供的说明）：Circle 的每个模型内是大小相同的圆形；Geometry 是大小、形状各不相同的几何图像。** Circle偏向同尺寸基线，Geometry用于检验异尺寸、异长宽比输入。分开报告，不能把Circle加速比直接外推到Geometry。

| Case | PSD | CMO3 | 来源/许可 | 测试重点 |
|---|---|---|---|---|
| circle-100 | `psd/circle/atlas_mapping_100.psd` | `cmo3/circle/atlas_mapping_100.cmo3` | 用户提供，仅当前测试，不再分发 | 小规模基线 |
| circle-500 | `psd/circle/atlas_mapping_500.psd` | `cmo3/circle/atlas_mapping_500.cmo3` | 同上 | 中规模对照 |
| circle-1000 | `psd/circle/atlas_mapping_1000.psd` | `cmo3/circle/atlas_mapping_1000.cmo3` | 同上 | 大规模性能 |
| circle-2500 | `psd/circle/atlas_mapping_2500.psd` | `cmo3/circle/atlas_mapping_2500.cmo3` | 同上 | 高负载压力测试 |

四份 PSD 均为4096×4096、8-bit RGB（4通道），已直接读取 PSD 结构确认 layer records 分别为100、500、1000、2500。**PSD 图层数不等同于原生某次当前页 invocation 的输入数**，后者必须实机记录。

Circle 已通过宿主打开及160次真实入口A/B，实际输入数与页尺寸见 [HOST-AB.md](../../validation/texture-atlas-current-page/HOST-AB.md)；输入资产清单见 [circle-manifest.md](circle-manifest.md)。Geometry 的PSD尺寸为2048²；PSD画布同样不能替代CMO3当前纹理页尺寸。

分类目录 `psd/` 原有的同名文件未动；接收时其中100、500两个文件为零字节，1000、2500两个文件与本压缩包文件大小不同。本次验收使用 `circle/` 中经过核验的副本，不混用其他文件。
