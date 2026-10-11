# Cat 插值缓存性能诊断

此目录是隔离 JVM 的差分工具。运行时已有默认关闭的候选接线，尚未完成 Editor 拖动验收或部署。不要将诊断 agent 安装到用户 Editor。

冻结结果：`build/model-import-3d-offline/cat-performance-cache-r2-20261003/manifest.json`。
输入是已交付 Cat 和 SHA 验证的官方 5.3.02 库；不写模型、安装目录或主 Editor。

| 路径 | 官方 | 候选 |
| --- | ---: | ---: |
| 全部缓存刷新主线程累计分配 | 8,975,205,840 bytes | 625,092,392 bytes |
| 同轮缓存刷新耗时 | 22.131 秒 | 20.102 秒 |
| setup 主线程累计分配 | 24,590,053,728 bytes（先前基线） | 16,227,000,600 bytes |
| setup 耗时 | 44.614 秒（先前基线） | 35.480 秒 |

时间是单轮测量，不能当作稳定性能承诺。累计分配不是同时占用的堆大小。
候选保留官方椭圆计算器和 form 混合，只绕过逐形态创建点引用并复用临时向量。
完整 512 网格、557,568 个普通/派生 forms 的坐标原始 float 位、透明度、绘制顺序银行一致；每个 form 的更新版本增量一致。
21,504 次二维缓存内部调用全部命中候选，无拒绝。
错误模型、官方库、银行或未命中候选都会拒绝验收；未支持的插值/形态使用官方路径。

原始反序列化仍约分配 7.5 GB，3 GB 堆基线会 OOM。这里使用 6 GB 堆仅用于有界诊断。
Editor 的鼠标拖动、全模型失效传播、撤销历史及其他线程内存尚未覆盖，不能宣称用户卡顿已修复。
现有 CMO3 没有改变；原面、UV、普通参数绑定及 5 px 精度预算没有删减。

复现命令（输出目录必须尚不存在）：

```sh
python3 -B validation/model-import-3d-user-entry/performance/run-cache-proof.py \
  --lib '/path/to/Cubism/app/lib' \
  --asm '/path/to/asm-9.7.1.jar' \
  --model artifacts/cat-native/cache-compact-r1-20261003/cat-textured-xyz360.cmo3 \
  --out build/model-import-3d-offline/cat-cache-proof-new \
  --optimized-setup
```

下一门槛：按官方库/类加载器验证接入候选，再在专属 Editor 中覆盖真实拖动和 Undo，比较同一鼠标操作的延迟、分配与银行恢复。反序列化和无插件工程性能需要另外解决。

实际反射运行时已通过同一全 Cat 差分：
`build/model-import-3d-offline/cat-performance-runtime-r1-20261003/manifest.json`。
全缓存分配 8,974,800,056 → 636,102,456 bytes，耗时 22.192 → 19.901 秒；全部银行位与更新版本增量相同。
用 `--production-runtime --optimized-setup` 选择该后端。
另有五项无写入拒绝与普通/零/负零三项官方差分，证据在 `cat-performance-runtime-guards-r1-20261003`。

启动候选位于 `runtime/.../interpolation` 与 bootstrap，需显式设置
`-Dturboism.meshInterpolation.enabled=true`，安全模式或禁用 `cubism.mesh.interpolation` 时仍拒绝安装。
它只允许精确官方 JAR 与原始扩展 class SHA，未知版本、已加载目标或不支持形态保持官方路径。
候选 agent、源码和 8 项门禁/生命周期测试 XML 已冻结到
`build/model-import-3d-offline/cat-interpolation-runtime-candidate-r1-20261003`。
这不是新的可交付 Cat 工程，也不是实际鼠标响应改善的证明。

## 确定性曲面优化候选

生产 XYZ 直接顶点回退已有本地曲面误差控制的减面。Cat 70,576 → 48,864 面；分区槽 48,497 → 35,397。既有 5px 总预算包含曲面优化和参数插值，纹理误差另限 0.5 texel。冻结证据：`build/model-import-3d-offline/cat-surface-optimization-r2-20261003/manifest.json`。旧无减面工程与上述缓存差分没有改动。

独立检查读原始 GLB，检查保留端点位置/UV，并对两个方向的顶点、三角形中心和边中点取固定样本计算最近三角形距离。它不调用 Java 合并证明，也不做 Editor 验收。

```sh
python3 -B validation/model-import-3d-user-entry/performance/verify-cat-simplification.py \
  --source artifacts/cat-textured/source-r2/cat-textured.glb \
  --candidate build/model-import-3d-offline/cat-surface-optimization-r2-20261003/optimized-geometry.json \
  --output /tmp/cat-surface-check.json
```

抽样不是完整曲面界的证明，更不是渲染像素或持续遮挡证明。新的实际保存/重开/拖动仍需专属 host profile；不能用旧 Cat driver 的固定 70,576 面门禁验收优化模型。

## 优化工程实测（2026-10-03）

`cat-optimized-cache-r1-20261003/manifest.json`绑定通过独立实机保存审核的新Cat，SHA `70983ff77d29ebb644f3f2bf5ac5ed5b663199d2433919dcfb795dbecdeeaba5`，46,775,888 bytes。512网格/35,397槽/48,864面；557,568普通与派生forms仍存在。

- 反序列化：24.219秒，主线程累计分配7,037,428,368 bytes。
- 候选setup：41.212秒，累计16,054,142,424 bytes。
- 同轮官方缓存刷新：25.252秒，累计6,585,516,104 bytes。
- 同轮候选刷新：15.665秒，累计473,240,920 bytes。
- 完整银行 `ba6aed0f7f545219797f135d0b21ce94fd2db27710657e7640e606dde3993d45` 与更新版本增量exact；21,504命中/0拒绝。

单轮耗时和累计分配不能冒充峰值内存或实际鼠标性能。仅减面仍未消除工程加载成本。工具新增 `--optimized-save-controller <controller>`：先重跑原生保存审核、绑定产物SHA，才允许优化模型；旧模型和冻结银行门禁保持。模型没有写入，候选仍默认关闭。

## 椭圆系数预计算候选

`--production-runtime --precompute-ellipse-weights` 在隔离差分工具中开启默认关闭的 `turboism.meshInterpolation.coefficients`。预计算角度系数，不缓存或跳过源顶点变更；保留官方两段 double 累加后的 float 舍入、最终 float 混合与每点更新标记。运行时仍须满足原有 SHA/类型/安装门禁。

`EllipseWeightProof` 与真实官方库对比 14,400 个标量，包括边界、非均匀和退化角度以及正负零。完整优化 Cat 差分证据位于 `cat-optimized-coefficient-cache-r1-20261003`：557,568 forms 的银行和更新版本增量一致；该轮刷新 41.399 秒/6,585,767,480 bytes → 2.120 秒/93,254,848 bytes。此轮与隔离 Editor 并行，不能当稳定延迟或堆峰值结论；反序列化未优化。

原生拖动验证必须使用官方 `l(MouseEvent)` 保存按下事件，并用其 `a(MouseEvent)` / `b(MouseEvent)` 更新拖动和释放。单独 `m(MouseEvent)` 没有按下原点，可能把绝对终点当位移。审查器拒绝旧 v2 carrier，v3 还须满足实际位移与相机映射的 20px 位移一致（0.01px 容差）、完整源银行 Undo 和基线/候选移动坐标 exact。任务内事件不等价于 OS 鼠标测试。
