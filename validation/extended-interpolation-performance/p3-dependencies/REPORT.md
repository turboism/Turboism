# 局部刷新依赖调查

本专项以官方 **5.3.04** 为目标，仅设计和隔离实验，未修改生产刷新策略。主优化的 20 版本矩阵不等于本专项局部算法的 20 版本验证。

## 原生依赖

`CExtendedInterpolationExtension.updateInterpolatedForms_exe` 先检查启用、首形态、绑定/Morph、兼容性和结构，必要时 setup；随后按普通源 GUID/EditVersion 判断是否改变，force 可绕过未变跳过。实际更新先重填普通源版本表，再调用 Normal 和 Morph 更新。该版本表不能完整表达类型、scale、键、绑定和 Morph 状态。

`setupFormStructure_Normal` 生成的 `normalExtendedParameters` 按原生轴顺序保存切片。每片的 sourceKeyforms 是输入，interpolatedKeyforms 是传给 common 的目标列表。第一轴读取普通源；后续轴还读取前序轴已生成的派生形态。因此必须沿原生顺序传播，不能把各轴当作互不相关的任务。

`ExtendedKeyFormMorphTargetSet.updateInterpolatedForms` 静态链中包含 Base 和普通 Morph 输入，并调用 common 写扩展形态；Base 改变可能传播到 Morph。这里没有专门构造 Morph 创建、编辑和历史恢复实验，当前设计一旦存在 Morph 就整个对象回原生。

可用 `javap -c -p` 核对本地官方类，复核命令见 `bytecode-evidence.txt`。反编译行号会随 CFR 配置变化，以方法签名及字节码为准。`ACForm.setUpdated` 自身只递增 editVersion；point setter 的写坐标及更新调用还须结合 common 的过滤、目标访问次数和顺序理解，不能推成“每次整对象刷新所有形态统一增加 V”。

## 隔离差分

`run-p3-probe.py` 用官方类、独立 JVM 和唯一 /tmp 工作目录运行，JDK 17、编译/执行各 60 秒上限。三个场景均为 **ELLIPSE、每轴三个普通键、固定种子的合成模型、修改第一个普通形态**。无 Editor 鼠标操作。

| 场景 | 派生形态 | 全部/选中切片 | 跳过切片 | 比较 float 值 |
| --- | ---: | ---: | ---: | ---: |
| ArtMesh 两轴，4 插入点 | 112 | 14 / 12 | 2 | 7,168 |
| Warp 三轴，2 插入点 | 316 | 79 / 57 | 22 | 10,112 |
| Rotation 两轴，4 插入点 | 112 | 14 / 12 | 2 | 448 |

两腿使用独立、同种子模型，原生全量对照和按依赖闭包选择切片的计算结果逐位匹配。Rotation 的 448 值含 origin、angle、scale，不能全部称为几何坐标。ArtMesh/Warp 比较 positions；没有完整 opacity 等属性差分。本实验只能支持这些 Normal/ELLIPSE 用例，不构成对所有输入的数学证明、SNS 验证或生产通知等价证明。单次计时仅为探针观测，不构成性能承诺。

**版本增量并不完全相同**，原始日志保留差异数量。传入 common 的目标列表还含不一定实际写入的锚点；保守 dirty-list 成员不能直接等同“本轮实际改写的形态”。原探针的“全部 dirty 形态均增加版本”统计标签已修正，日志重新运行。不能将一次 setUpdated 或统一 V 次补偿作为已证明的解决方案。

标准正规网格中，每个普通形态直接参与全部相交轴，未构造出只影响最后轴的普通源。不将该假想用例或未运行的大轴数比例列为实测收益。

## 裁决与版本边界

局部切片图提供了具体候选切口，尚不具备生产准入条件：必须补齐真实编辑原因与结构代际、所有输入签名、每个目标的原生写入及版本增量、属性副作用和下游读取契约。详细门禁见 [设计](MINIMAL_CUT_DESIGN.md)。保持当前 force、Normal/Morph 更新和保存语义。

本专项仅动态运行 5.3.04 三个用例。20 版本 Normal 私有结构、Morph 路径、SNS 局部剪枝及 setter 依赖族仍须专项核验，不声称“20 版私有结构字节码完全一致”。既有 20 版刷新入口扫描、主优化 installed 差分与缓存生命周期证据仍各自有效，但不能替代上述局部刷新准入。

后续局部刷新实验已补齐 Normal 场景的绝对版本及真实通知顺序对照，见 [局部刷新可行性](../p3-local-refresh/README.md)。
