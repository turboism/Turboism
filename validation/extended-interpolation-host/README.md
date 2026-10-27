# 5.3.04 拓展插值宿主验收

该探针在官方 Editor 的活体文档中运行，使用托管队列、官方 BAT、任务专属 CoW prefix 与模型副本。原始模型和官方安装保持不变。Generic direct runner 当前使用 `DISPLAY=:0`，不声称 Xvfb 桌面隔离。探针不进入产品 bundle。

## 操作与证据边界

- 模型缺少变形器时，在任务文档内补建 Rotation → Warp → ArtMesh 链；使用官方带 owner 的形态构造器及 source/Part 注册 API，验证无环后更新模型实例。
- 为缺少多轴绑定的对象建立 3×3 源网格，独立 clone context 保证形态引用互异且 owner 正确；已有多轴绑定按实际范围记录，不承诺其必为完整 3×3。
- 分别切换三类对象的**源网格**到 ELLIPSE/SNS，重建后断言派生网格实际类型。对每个组合改变源点，以关闭桥的官方刷新作为 oracle，再启用优化刷新，比较所有普通/Normal/Morph bank 坐标 raw bits、opacity、Rotation angle/scale 和 EditVersion 增量，并要求桥调用计数增加。这是活体文档内核 API 差分，不是鼠标编辑证明。
- 普通参数实例取值与重绘单独计数；重建增量为 0 是允许的真实观察。
- 官方视图输入事件链注入拖动后，只有源坐标实际改变才验证 Undo/Redo；恢复时按 GUID 重新取当前形态。无位移时如实记录未验证，不计作编辑或历史恢复通过。受控补建 Warp 使用子网格 Canvas→归一化局部坐标转换，避免直接挂接到无关 Warp；Undo/Redo 对全部普通/Normal/Morph bank 坐标及属性比较 raw bits。
- 保存使用官方 `saveDocument`，使用官方 `ArchiveReader` 读取 CAFF 目录。`.cmo3` **不是标准 ZIP**。另复制保存文件到任务内第二路径，官方打开为独立文档，若加载完成则比较三类对象全部形态 bank 的坐标和上述属性。该项属于同一 JVM；另有独立进程重开验收，见下文。
- 父级全部普通 Rotation 形态角度改变后，等待官方模型更新，要求子 ArtMesh 的计算坐标实际变化；在原生腿持续关闭桥的情况下，再与优化腿逐位比较子网格坐标。
- 使用末尾 GLEventListener 在原生绘制后读取真实 GLJPanel FBO，MSAA resolve 后读像素；保存/恢复 framebuffer、read buffer、PBO 和 pack 状态，GL 错误显式失败。要求原生连续帧稳定、优化帧逐像素一致、额外姿态变化确实改变画布像素。PNG 留在宿主任务目录，仓库只归档路径、摘要与日志；捕获包含原生辅助线，不声称只比较模型纹理。
- 独立 JVM 载入上一会话的保存副本，核对三类对象全部 bank 的 raw bits/属性摘要与父级 UUID，校验 JVM 启动时间与文档身份不同。

最终冒烟状态为 `SMOKE_PASS_PARTIAL_ACCEPTANCE`；不能据此宣称完整 UI 放行。延迟/线程分配指标混合了当前操作的 API 开销，常规冒烟的配置切换仅三次、拖动仅一次，不适合推断 p95/p99 或端到端性能改善。另有下述重复 A/B 受控基线。

## 运行

```sh
bash validation/extended-interpolation-host/build.sh
TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env \
  bash validation/extended-interpolation-host/run-extended-interpolation-host-validation.sh \
  5304 oracle --golden-prefix "${CUBISM_GOLDEN_PREFIX}" \
  --fixture-host "${CUBISM_TEST_FIXTURE}" \
  --fixture-sha256 57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4 \
  --aux-agent build/native-startup-assistance-agent.jar \
  --jvm-option '-Dturboism.validation.startupAssistance=true' \
  --prepare-dir /tmp/extended-host-request
TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env \
  python3 scripts/preview/host_validation.py _enqueue-runner \
  --request /tmp/extended-host-request/runner-request.json
```

wrapper 的 JVM mesh 开关用于内核宿主测试；持久设置的真实点击、保存和无 JVM 覆盖重启证据见 [设置宿主报告](../extended-interpolation-settings-host/README.md)。

## 实际执行记录

本地忽略目录 `evidence/` 保留日志和结果，不收录用户模型或官方 JAR。

- `missing-rig`：首次真实运行因 fixture 缺少 Warp/Rotation 而失败，未计作通过。
- `caff-format-failure`：job `bbe84473-d61b-424a-9120-e8c3d3b6c7b3` 已在活体文档创建受控结构，三对象曲线切换完成，普通调参重建 delta=0；视图事件无源坐标位移，Undo/Redo 跳过。官方保存并校验成功，但探针误用 ZipFile，最终失败。后已修正为官方 CAFF reader。
- `reopen-stale-carrier-failure`：六组合活体源点差分通过；探针随后直接修改 Rotation angle，却未调用扩展刷新，导致重开重新计算的派生 angle 与保存前陈旧缓存不同。补上 `setUpdated()` 与原生扩展更新后复验。
- **`oracle-pass`**：job `e0d52911-3a84-4167-881e-8138644620dc`，bundle SHA `ab298611c9d9cc82c4a3f2ab945b79dd5271954b2a82ab0ba6ac884e069e6519`，探针 SHA `a6477f8a45910811a68fe1bedde1ef667e13057ffecc79240d1ee63670f79123`（精确值以 manifest 为准）。六组合活体源点坐标/属性/EditVersion 增量对照通过，原生 CAFF 读取及同 JVM 独立文档的三类对象所有形态 bank 坐标/属性重开对照通过；桥接增加 1,924 次、拒绝 0 次，普通调参 delta=0。最终为部分验收冒烟通过，原始模型不变、官方身份正确、正常退出、cgroup 安全清理。

- **`mouse-history-pass`**：job `91503114-91fd-45aa-918b-0daefea12f5f`，探针 SHA `ef1e539b29ccd1ea31b566a776486d4b31ff2f00a47c9fdb7bf0f8d71fa9f2d6`。真实视图拖动改变源形态，Undo/Redo 的全部网格形态 bank 坐标/属性 raw bits 恢复与重应用通过；六组合活体差分与同 JVM 保存重开继续通过，桥接增加 3,908、拒绝 0。队列最终成功，fixture 不变、身份核验与 containment 清理通过。仅一次拖动，不据此给出端到端性能结论。
- `hierarchy-partial-metrics-failure`：job `00c688a0-bc7c-47ac-88c8-b903d36db551`。父级旋转确实改变子网格，原生/优化子级计算坐标 raw bits 匹配；随后指标函数排序不可变列表抛异常，整任务失败，未执行 GL，不计作整体验收通过。已改为复制后排序，等待复验。
- `mouse-r7-failure`、`mouse-r8-failure`：受控结构错误重用 fixture 无关 Warp，网格被放大至画布外；保留失败证据，已修正。

- **`cold-session-pass`**：job `e72ef3ea-83eb-4afa-8c2c-f14bb42b0b9f`，探针 SHA `a55cc80589a6a65cbe8aa0586d7e15d53622d3b6d3834da8aaa2319812322b6c`。独立 Editor JVM 重开 `mouse-history-pass` 保存副本，三类对象全部 bank 坐标/属性摘要、父子关系一致，启动时间与文档身份不同；队列成功。
- **`final-host-pass`**：job `6593f1c5-42b6-4d1f-8504-73f699678bdb`，探针 SHA `3290a566918d944ad85b802ed447a8cfe422e52640cd3be5f49fd67a00d7842b`。真实拖动与全部 mesh bank 历史恢复、六组合源点差分、父子级联坐标逐位一致、GL 像素一致与姿态变化正对照、CAFF 保存及同 JVM 新文档重开均通过；原生腿在模型重建/读取全程关闭优化桥。525×898、1,043 种像素值（非均匀、非黑）、MSAA resolve=true，原生/优化像素 SHA `93c70693c27d4da0dab6f9bdc49577250f0916bc707d71fc3d24b50b5237dd5f`，额外姿态变化 3,177 像素。桥接增加 5,231，拒绝 0；原始模型 SHA 不变、身份正确、正常退出与 cgroup 清理通过。
- 同一最终任务的受控三对象合计 1,575 形态、坐标长度合计 52,336 bytes，前后重建数量稳定。此值只统计坐标数组长度，不是 retained heap；同时记录堆使用、各堆池峰值、进程累计 GC。临时 GUID 索引预算 65,536 条，原生 dense grid 不裁剪。

尚未声明完成：参数面板 Slider 鼠标拖动、专门 Morph Target 创建与编辑历史、全部版本真实 UI 验收、长时间连续拖动帧率。Morph bank 纳入现有 bank 比较，不等于专门创建 Morph Target 并覆盖其完整编辑历史。

## 重复真实拖动 A/B 基线

`evidence/drag-ab-pass/`，job `075b36c1-6204-4049-b05e-d8f111f53acb`，probe SHA `d31726de3cd5a72d9a517961a3556bc249177048689eadda10136b5b0091d8a3`。开启额外 JVM 参数 `-Dturboism.validation.extendedInterpolation.dragBenchmark=true` 可复核。

每种曲线先预热 3 组，再测量 12 组 native→optimized 配对：实际选中 ArtMesh，经官方输入链 press→8 段 drag→release；每腿等待官方模型更新，比较全部 mesh bank 坐标/属性 raw bits。每腿都执行 Undo→Redo→Undo 并检查整个 bank，回到同一初始状态后再执行下一腿。原生腿全程禁用桥且成功计数增量必须为 0；优化腿必须实际命中。计时包含选择/准备、模型结构更新和更新屏障，**不是纯鼠标事件延迟或 FPS**；固定原生腿在先，未随机化顺序，只有一个模型和运行环境。

| 曲线 | 原生 p50 / p95 / p99 ms | 优化 p50 / p95 / p99 ms | 原生事件段 EDT bytes/次 | 优化事件段 EDT bytes/次 |
| --- | ---: | ---: | ---: | ---: |
| ELLIPSE | 250.3 / 321.5 / 351.2 | 218.2 / 290.4 / 295.7 | 14,172,717 | 3,217,108 |
| SNS_CURVE | 231.6 / 254.3 / 257.9 | 198.8 / 234.1 / 236.8 | 30,005,406 | 18,197,063 |

12 个样本的经验 p99 接近最大值，不能作为长时间尾延迟保证。分配只覆盖 drag 事件段 EDT，不覆盖整次操作及渲染线程。两曲线各 12 组均通过 raw bits/历史恢复检查；每曲线优化腿成功计数合计 22,188，原生腿 0。此后同一任务继续通过六组合源点差分、父子级联、真实像素对照及保存重开；总优化命中 81,483，拒绝 0，原始模型 SHA 不变，官方身份、正常退出、cgroup 清理均通过。此项增强 5.3.04 受控交互证据，仍不代表全部版本/全部 UI 操作或长时帧率验收。


## 本轮候选与 Warp 交互复验（通过）

最新候选 bundle SHA `923d33d61a3b2e3a2c0beea43dc21aa83b979ffb7b628e9a444fd9bb52c5e51e`，包含辅助索引统计及退出 token 引用释放。新宿主 probe SHA `844cc76538b672847b952a09ef81918fcc4544531db38f1956dbe2ab9a0be788`；任务 `ce38cb88-834a-4f38-89f9-fc9a32cfb0d2`。

`WarpDragValidation` 通过 `-Dturboism.validation.extendedInterpolation.warpDragBenchmark=true` 启用，默认每曲线 3 组预热、12 组配对。它选中实际 Warp，以 calculated control point 经 camera 映射到视口，执行官方 hover/press/8drag/release 输入链，要求源形态改变。每腿比较全部 Warp 普通/Normal/Morph bank 坐标及 opacity，并执行 Undo/Redo/Undo。原生腿从准备、重绘到读取全程关闭桥；优化腿要求实际命中，并与原生位值比较。事件分配只计 EDT drag 段，计时包含准备与官方更新等待，不解释为 FPS。

缓存观测新增 `cache.<phase>.auxIndex.*`，要求返回后 activeScopes 与 activeIndexEntries 都为零。本轮已在托管官方 Editor 实测通过，`evidence/warp-budget-pass/` 保留 outcome、日志、结果及包身份；队列 succeeded/PASS、模型副本来源不变、官方身份核验、正常退出与 containment 安全清理均通过。


| Warp 曲线 | 原生 p50 / p95 / p99 ms | 优化 p50 / p95 / p99 ms | 原生事件段 EDT bytes/次 | 优化事件段 EDT bytes/次 |
| --- | ---: | ---: | ---: | ---: |
| ELLIPSE | 205.9 / 235.0 / 265.1 | 187.0 / 295.3 / 311.5 | 3,313,667 | 1,298,651 |
| SNS_CURVE | 194.7 / 213.9 / 215.5 | 169.9 / 205.3 / 205.6 | 7,050,176 | 4,884,519 |

两曲线各 12 组全部 bank raw bits、opacity 与 Undo/Redo/Undo 通过，原生腿命中 0、优化腿各 7,608。**ELLIPSE 本轮 p95/p99 变慢**，尽管 p50 与事件分配下降，不能宣传交互尾延迟全面改善。样本少、单模型、原生腿固定在先，且计时含结构准备和更新等待；不据此断言回归或长时收益，保持默认关闭。无需调整数学或删除较慢样本。

此后六组合活体源编辑差分、父子级联 raw bits、真实 GL 像素与姿态变化对照、官方保存及同 JVM 新文档重开继续通过；总优化命中 34,365，拒绝 0。prepare 与 afterRebuild 的辅助索引 activeScopes/activeIndexEntries 均为零。最新包独立进程重开也通过：job `944d3022-b079-4a8c-91c1-49640b7dfc57`，`evidence/warp-budget-cold-pass/`。JVM 启动时间与文档身份均不同，三类对象全部 bank raw bits/属性摘要及父子关系一致；来源保存副本不变、官方身份、正常退出和清理均通过。

## P3 数值缓存复验

`evidence/p3-curve-pass/`，job `52a49f7b-0304-4b65-93ed-f2fb878ece10`，bundle SHA `044b0148e395b94ad7d7b76ebe4b3a997796b9659fb318197f0918c15f26d5b4`，probe SHA `b5e76993ed9869257222cbae8e22b68bb83991064fc38980e0aa253fa847bb41`。六组合源点对照、真实 mesh/Warp 双曲线各 12 对拖动、全 bank 历史恢复、父子级联、GL 像素/姿态变化、CAFF 保存/同 JVM 新文档通过；队列确认 fixture 不变、身份、正常退出和安全清理。新增缓存 hits 增量 5,302，最终估算 11,544 B / 8 条，预算 8 MiB；这只是辅助数值存储，不是原生 dense grid 上限。

本次真实拖动结果包含部分退化，完整 p50/p95/p99 和隔离 P3 增量见 [数值缓存报告](../extended-interpolation-performance/p3-curve-cache.md)。本次 A/B 是官方与合并优化，不单独归因 P3。独立进程重开当前保存副本也已通过：`evidence/p3-curve-cold-pass/`，job `775e8517-d9ad-4f83-ac9d-753664beec91`，三类对象全部 bank raw bits/属性/层级与上一会话匹配，JVM 身份不同；fixture 不变、身份/正常退出/安全清理均通过。

## P3 Normal 局部刷新生产包

详见 [生产落地报告](../extended-interpolation-performance/p3-local-refresh/production.md)。agent SHA `52e0df22a51115c809caace6e72f5fe5d9043a5cf3aa422a716e8c8930499fc8`，probe SHA `a53065d2276c63253989dd6b357a5b7e3080ffd5704b4102369efca59f2d58c8`。job `2c582221-78c0-470b-9e7f-dd172e79f444` 完成六组合差分、真实 ArtMesh/Warp 拖动、全 bank 官方历史、父子级联、GL 像素与姿态对照、CAFF 保存及同 JVM 重开；局部命中 7,348，曲线命中 5,302，拒绝 0。队列 succeeded、fixture 不变、官方身份/正常退出/清理通过。证据 [p3-local-pass](evidence/p3-local-pass/outcome.json)。

首次文档就绪超时 job `71aab21d-1e28-4dff-94a7-618232e8d48b` 不计通过。新增验证专用后备：模型文档列表为空持续 45 秒时，经官方 command_open 打开任务副本一次；不修改用户原始模型或生产包。

同一最终包独立 JVM 重开 job `65079e23-4ef0-48af-9e59-3d15dac32eb2` succeeded：三类对象全部 bank raw bits/属性/层级匹配，JVM 身份不同，fixture 不变、官方身份/正常退出/清理通过。证据 [p3-local-cold-pass](evidence/p3-local-cold-pass/outcome.json)。保留载体部分验收命名，不外推到参数滑块鼠标、专门 Morph 编辑或所有版本 UI。
