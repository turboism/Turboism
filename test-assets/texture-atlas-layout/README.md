# Texture Atlas Layout Test Assets

## 用途

验证纹理图集当前页自动排版：原生/new/legacy 性能对照、固定及自动缩放、旋转、区域并行、overflow、撤销/重做和保存重开。

## 许可与分发说明

- **使用许可**：**Creative Commons Zero v1.0 Universal (CC0 1.0)**（用户明确确认授权，覆盖历史文档中“仅当前测试，不再分发”的早期说明）。
- **二进制分发**：本目录下的 17 份二进制模型与工程文件（9 份 CMO3、8 份 PSD，总计 123,517,686 字节）**不直接提交 Git**，由版本化 GitHub Release 资产统一分发，由仓库根目录 `test-assets/corpus.json` 清单管理。
- **获取方法**：
  ```sh
  # 拉取并映射到本地 test-assets/texture-atlas-layout/ 目录
  python3 scripts/test_corpus.py fetch texture-atlas-layout

  # 校验本地文件完整性与哈希
  python3 scripts/test_corpus.py verify texture-atlas-layout
  ```
- **模型复现与构建说明**：详见 [BUILDING.md](BUILDING.md)。

## 文件分类

- `psd/`：用于导入和构建测试模型的 PSD 副本（本地通过 `fetch` 拉取后软链或复制，Git 忽略）。
- `cmo3/`：可直接在 Cubism Editor 中打开的 `.cmo3` 模型副本（本地通过 `fetch` 拉取后软链或复制，Git 忽略）。

## 测试约定

1. 此目录中的文件作为输入样本；执行排版、保存等写操作前，另外创建运行副本，不覆盖输入样本。
2. 记录样本来源/使用许可、Cubism 版本、初始当前页、页面尺寸、比例、旋转及 margin 设置。
3. 每轮算法对照从相同输入副本开始，不使用上一轮排版结果作为下一轮输入。
4. 本目录的 `psd/`、`cmo3/` 内容被 Git 忽略规则完全排除，严禁将任何二进制模型文件提交入库。

实机验收协议：[`HOST-CHECKLIST.md`](../../validation/texture-atlas-current-page/HOST-CHECKLIST.md)。

## 样本登记

全套测试集包含 Circle、Geometry 两套模型和 PSD，共 17 份文件、123,517,686 字节（9 份 CMO3、8 份 PSD）。来源与新增文件见 [atlas-manifest.md](atlas-manifest.md)。

**两套分布不同（用户提供的说明）：Circle 的每个模型内是大小相同的圆形；Geometry 是大小、形状各不相同的几何图像。** Circle偏向同尺寸基线，Geometry用于检验异尺寸、异长宽比输入。分开报告，不能把Circle加速比直接外推到Geometry。

| Case | PSD | CMO3 | 许可 | 测试重点 |
|---|---|---|---|---|
| circle-100 | `psd/circle/atlas_mapping_100.psd` | `cmo3/circle/atlas_mapping_100.cmo3` | CC0-1.0 | 小规模基线 |
| circle-500 | `psd/circle/atlas_mapping_500.psd` | `cmo3/circle/atlas_mapping_500.cmo3` | CC0-1.0 | 中规模对照 |
| circle-1000 | `psd/circle/atlas_mapping_1000.psd` | `cmo3/circle/atlas_mapping_1000.cmo3` | CC0-1.0 | 大规模性能 |
| circle-2500 | `psd/circle/atlas_mapping_2500.psd` | `cmo3/circle/atlas_mapping_2500.cmo3` | CC0-1.0 | 高负载压力测试 |
| geometry-100 | `psd/geometry/atlas_mapping_geometry_100.psd` | `cmo3/geometry/atlas_mapping_geometry_100.cmo3` | CC0-1.0 | 异形几何小规模基线 |
| geometry-500 | `psd/geometry/atlas_mapping_geometry_500.psd` | `cmo3/geometry/atlas_mapping_geometry_500.cmo3` | CC0-1.0 | 异形几何中规模对照 |
| geometry-1000 | `psd/geometry/atlas_mapping_geometry_1000.psd` | `cmo3/geometry/atlas_mapping_geometry_1000.cmo3` | CC0-1.0 | 异形几何大规模性能 |
| geometry-2500 | `psd/geometry/atlas_mapping_geometry_2500.psd` | `cmo3/geometry/atlas_mapping_geometry_2500.cmo3` | CC0-1.0 | 异形几何高负载压力测试 |
| geometry-500x2 | *(基于 geometry-500)* | `cmo3/geometry/atlas_mapping_geometry_500x2.cmo3` | CC0-1.0 | 双页多纹理排版工程 |

四份 Circle PSD 均为 4096×4096、8-bit RGB（4通道），已直接读取 PSD 结构确认 layer records 分别为 100、500、1000、2500。**PSD 图层数不等同于原生某次当前页 invocation 的输入数**，后者必须实机记录。Circle 输入资产清单详见 [circle-manifest.md](circle-manifest.md)。

四份 Geometry PSD 均为 2048×2048、8-bit RGB（4通道），包含异形几何图形。详见 [geometry-manifest.md](geometry-manifest.md)。
