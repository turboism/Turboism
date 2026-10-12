# Test Assets (测试语料与模型资产)

本目录用于管理 Turboism 的人工测试模型、PSD 及 3D 几何测试夹具。为了保持 Git 仓库精简并防范大文件与私人资产泄露，仓库采用**清单化管理 + 版本化 Release 资产分发**策略。

- **小体积几何夹具（< 1MB）**：直接以 `storage: git` 形式入库受版本控制。
- **二进制大文件（CMO3、PSD、CAN3 等）**：**严禁直接提交 Git**。通过 `test-assets/corpus.json` 登记清单（文件名、字节数、SHA-256 哈希），二进制归档发布至 GitHub Releases，使用 `scripts/test_corpus.py` 工具按需拉取至本地共享缓存并映射至工作区。

## 目录结构

```text
test-assets/
├── .gitignore                     # 排除本地拉取的 cmo3、psd 二进制产物
├── README.md                      # 测试集总说明（本文档）
├── corpus.json                    # 语料库总清单（Schema v1）
├── texture-atlas-layout/          # 纹理图集排版测试集（文档在库，二进制 Release 分发）
│   ├── README.md                  # 数据集使用说明与许可
│   ├── BUILDING.md                # 测试模型构建与复现说明
│   ├── atlas-manifest.md          # 基准包校验与 17 个文件清单
│   ├── circle-manifest.md         # Circle 8 个样本清单与 PSD 规格
│   ├── geometry-manifest.md       # Geometry 9 个样本清单与 PSD 规格
│   ├── cmo3/                      # 本地拉取/解压落地点（Git 忽略）
│   └── psd/                       # 本地拉取/解压落地点（Git 忽略）
└── model-import-3d/               # 3D 导入自建最小 GLB 夹具（Git 直接跟踪）
    ├── README.md                  # 几何规格、来源与构建说明
    ├── cube-985496727e50/
    ├── cube-textured/
    └── plane-textured/
```

## 数据集列表

| 数据集 ID | 版本 | 存储方式 | 许可 | 文件数 | 总字节数 | 说明 |
|---|---|---|---|---:|---:|---|
| `texture-atlas-layout` | `1` | `release` | CC0-1.0 | 17 | 123,517,686 (~118MB) | Circle 与 Geometry 纹理图集当前页排版测试集（9 个 CMO3 + 8 个 PSD） |
| `model-import-3d` | `1` | `git` | CC0-1.0 | 4 | 13,544 (~13KB) | 自建最小立方体与平面 GLB 夹具（含嵌入纹理与对角线展开缝测试） |

## 语料管理工具 (`scripts/test_corpus.py`)

`scripts/test_corpus.py` 是基于 Python 标准库的语料管理脚本，提供拉取、校验、打包与状态检查能力。

### 1. 拉取语料 (`fetch`)

从 GitHub Release 下载指定数据集并校验归档与成员 SHA-256，解压到共享缓存并映射到本工作区对应路径：

```sh
# 拉取指定数据集
python3 scripts/test_corpus.py fetch texture-atlas-layout

# 拉取全部 Release 数据集
python3 scripts/test_corpus.py fetch --all

# 离线或本地测试：从本地归档拉取并解压校验
python3 scripts/test_corpus.py fetch texture-atlas-layout --from-file build/test-corpus-release/texture-atlas-layout-v1.tar.gz

# 复制文件（非符号链接）
python3 scripts/test_corpus.py fetch texture-atlas-layout --copy
```

- **共享缓存目录**：默认位于 `${XDG_CACHE_HOME:-~/.cache}/turboism/test-corpus/<dataset>@<version>/`。多个本地 worktree 之间共享同一缓存，不重复占用 118MB 磁盘。
- **工作区映射**：默认创建指向缓存文件的符号链接，保留 `test-assets/texture-atlas-layout/{cmo3,psd}/...` 既有相对路径，既有单元测试与自动化脚本无需修改路径。

### 2. 校验语料 (`verify`)

核对本地工作区中的语料文件是否存在、大小及 SHA-256 是否与 `corpus.json` 清单一致：

```sh
# 校验指定数据集
python3 scripts/test_corpus.py verify texture-atlas-layout

# 校验所有数据集（包括 git 跟踪与 release 拉取项）
python3 scripts/test_corpus.py verify --all
```

### 3. 查看状态 (`list`)

查看所有注册数据集的存储方式、许可、文件规模及本地准备情况：

```sh
python3 scripts/test_corpus.py list
```

## 私有资产严禁入库规则

Turboism 遵守严格的代码仓库卫生（Hygiene）防范策略：

1. **绝对禁止**：严禁提交任何用户私有工程资产（如未经审查的私有模型、私人插画 PSD）、第三方未授权 3D 模型（如猫/苹果等第三方网格）以及任何不在官方测试清单中的二进制文件。
2. **强制门禁**：
   - 根目录 `.gitignore` 与 `test-assets/.gitignore` 排除所有 `*.cmo3`、`*.can3`、`*.cmo3.bak`、`*.psd`、`*.moc3`。
   - `scripts/check_remote_hygiene.py` 在 pre-commit、pre-push、`devCheck` 与 CI 门禁中 fail-closed 拦截上述二进制后缀，即使使用 `git add -f` 强行暂存也会被拒绝提交。

## 如何新增测试数据集

1. **确定许可与来源**：
   - 语料必须具备清晰且兼容的开源许可（如 CC0 1.0、MIT、Apache-2.0 等）。私有/不明许可资产严禁纳入。
2. **在 `test-assets/corpus.json` 登记元数据**：
   - 声明 `id`、`version`、`license`、`storage: "release"`、`targetDir`、`usedBy`。
   - 逐个文件测量并记录相对路径、字节大小与 SHA-256 哈希。
3. **打包确定性归档 (`pack`)**：
   - 使用 pure-Python 打包命令从本地准备好的源目录生成可复现归档（固定 mtime、uid、gid、文件排序）：
     ```sh
     python3 scripts/test_corpus.py pack <dataset-id> --source <source-dir> --out build/test-corpus-release/
     ```
   - 打包逻辑会严格比对源目录与清单，若源目录包含清单外的多余文件将直接拒绝打包，防止误夹带杂质。
4. **提交主管审核与发布**：
   - 开发者不得私自创建 GitHub Tag 或上传 Release 资产。
   - 将打包产物与清单提交主管审核。审核通过后，由主管使用 `gh release create` 发布版本化 Release 资产。
5. **回填清单与提交文档**：
   - 将主管发布的归档 SHA-256 和字节数回填到 `corpus.json` 的 `release` 字段中。
   - 编写数据集专用的 `README.md` 与 `BUILDING.md`（说明复现/构建方法）。
   - 提交清单与文档更新并通过门禁。
