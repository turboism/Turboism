# Cubism 5.3.04 拓展插值刷新范围与触发机制调查报告

目标版本：**Cubism Editor 5.3.04**（对照覆盖 5.0.00–5.3.04 共 20 个版本）。
调查日期：2026-10-26。
调查范围：官方原生类反编译源码、字节码静态审计、独立官方类动态探针及既有优化边界。证据材料归档于 `validation/extended-interpolation-performance/refresh-evidence/`。

---

## 核心结论速览

1. **核心命题裁决**：
   用户调查命题：“**但任一源 keyform 变化后，会刷新该对象的整套拓展形态。**”
   - **单对象粒度（静态源码与控制流确认）**：**在非线性扩展参数轴范围内完全准确**。原生代码不存在按轴、按区间或按脏标记的细粒度局部刷新切口。只要调用未命中前置 early return（且未因 LINEAR 跳过），原生就会遍历该对象绑定的全部非线性扩展参数轴和组合切片，并刷新 Morph Target 扩展形态；
   - **跨对象传播（超出命题，原生存在父部件广播）**：在已知 Editor UI 交互链路中，形状编辑不仅通知自身，还会顺着 `getParentGuid()` 遍历直接父部件（`CPartSource`）的直接子对象列表，向**同部件下所有启用了拓展插值的直接兄弟对象**派发全量刷新（静态传入 `forceUpdate=true`）。
2. **静态调用点审计与“未变跳过”现状**：
   - `CExtendedInterpolationExtension.updateInterpolatedForms_exe` 内部确实编写了基于 `keyformUpdateMap` 比对 `EditVersion` 的跳过逻辑；
   - 但在扫描的 5.0.00–5.3.04 全部 20 个版本 JAR 的静态字节码中，所有已知的直接调用点（`l.class` 及 `setupFormStructure`）传入的实参均为 `iconst_1`（`true`）。在已知官方静态调用链路上，未变跳过分支不会生效；但需明确：反射、外部脚本、未扫描插件以及前置 early return 并不在此结论排除之列。
3. **旋转变形器（CRotationDeformerForm）行为与适配定位**：
   - **点引用**：`getAllPointRef()` 非空，但长度**恒为 1**（指向旋转中心原点 `Origin`），无网格顶点数组；
   - **计算行为**：外层形态混合对 `angle`（旋转角）和 `scale` 走常规线性加权累加；内层几何曲线循环**正好执行 1 轮**（为原点计算曲线，写回 `originX/originY`）；
   - **收益评估与覆盖**：131 形态重负载下单次耗时仅 ~0.09–0.31 ms、分配仅 25–35 KB（比 Warp 小 1–2 个数量级），几何点放大效应极低（属于低收益路径）。但鉴于 `RotationDeformerPointRef.moveToOnLocal` 存在 `originX/originY` 修改及 `setUpdated()` 副作用，主代理将补上对 origin 的适配以完全覆盖用户场景。
4. **P3 独立优化审计（`firstOrNull` 基于 `isEmpty` 替换）**：
   - `updateInterpolatedForms_exe` 首行对 `getAllExtendedKeyforms()` 的调用仅用于取出首元素做兼容性检查；
   - 替换为 `!normal.isEmpty() ? normal.firstOrNull() : morph.firstOrNull()`（必须基于 `!normal.isEmpty()`，保留非空首 null 不落 morph 的行为）；
   - 4 场景差分验证完全等价、零副作用；151 形态实测单次分配从 1,288 B 降至 0 B（100% 消除该处分配），耗时由 802.6 ns 降至 27.2 ns。

---

## 一、刷新范围与调用链路详细审计

### 1.1 updateInterpolatedForms_exe 控制流与前置退出门禁

反编译 `com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension`：

```java
private final boolean updateInterpolatedForms_exe(boolean bl) {
    // 门禁 1：未启用扩展插值
    if (!this.isExtendedInterpolationEnabled) {
        return false;
    }
    // 门禁 2：扩展形态列表为空
    ACForm aCForm2 = (ACForm)CollectionsKt.firstOrNull(this.getAllExtendedKeyforms());
    if (aCForm2 == null) {
        if (s.a.c()) com.live2d.util.log.a.b(com.live2d.util.log.a.a, "Extended interpolation is enabled but extended keyforms is empty.", false, null, 6, null);
        return false;
    }
    // 门禁 3：绑定和 MorphTarget 均为空
    ACForm aCForm3 = aCForm2;
    if (this.getOwner().getKeyformGridSource().getKeyformBindings().isEmpty() && this.getOwner().getKeyformMorphTargetSet().getMorphTargets().isEmpty()) {
        return false;
    }
    // 结构/拓扑/兼容性检查
    boolean bl6 = !((ACForm)CollectionsKt.first(this.getOwner().getKeyforms())).isCompatible(aCForm3);
    boolean bl7 = this.isNeedSetupFormStructure_Normal();
    boolean bl8 = this.getExtendedKeyformMorphTargetSet().isNeedSetupFormStructure();
    if (bl6 || bl7 || bl8) {
        CExtendedInterpolationExtension.setupFormStructure$default(this, false, false, 1, null);
        if (!this.isExtendedInterpolationEnabled) {
            return false;
        }
    }
    // 检查所有普通 keyform 的 EditVersion 是否变化
    boolean bl2 = false;
    for (Object t : (Iterable)this.getOwner().getKeyforms()) {
        ACForm aCForm = (ACForm)t;
        Integer n = this.keyformUpdateMap.get(aCForm.getGuid());
        if (n == null || aCForm.getEditVersion() != n.intValue()) {
            bl2 = true;
            break;
        }
    }
    // 理论跳过分支：当且仅当 bl == false 且 bl2 == false
    if (!bl && !bl2) {
        if (s.a.c()) com.live2d.util.log.a.d(com.live2d.util.log.a.a, "skip update extended interpolation", false, null, 6, null);
        return false;
    }
    // 刷新逻辑
    this.keyformUpdateMap.clear();
    for (Object t : (Iterable)this.getOwner().getKeyforms()) {
        ACForm aCForm = (ACForm)t;
        this.keyformUpdateMap.put(aCForm.getGuid(), aCForm.getEditVersion());
    }
    this.updateInterpolatedForms_Normal();
    this.getExtendedKeyformMorphTargetSet().updateInterpolatedForms(this);
    return true;
}
```

#### 边界说明与判定严谨性
- **Early Return 路径**：在进入重算前，存在 4 处前置检查可能直接返回 `false`（扩展禁用、无扩展形态、空绑定、重新 setup 后变为禁用）；
- **内部过滤跳过**：在进入 `updateInterpolatedForms_Normal()` 后，若某个绑定的参数为 `ExtendedInterpolationType.LINEAR`，原生执行 `continue` 跳过，不执行 `updateInterpolatedForms_common`；若某个参数切片的 `list2`（interpolatedKeyforms）为空，也会跳过；
- **20 版本静态调用点审计**：
  使用 `scan-update-calls-20versions.py` 扫描 5.0.00–5.3.04 全部 20 个版本中 `invokevirtual updateInterpolatedForms:(Z)Z` 的字节码（见 `refresh-evidence/scan-update-calls-20versions.log`）：
  - `CExtendedInterpolationExtension.setupFormStructure`：调用前指令为 `201: iconst_1`；
  - `ExtendedInterpolationUtil`（`l.class`）：调用前指令为 `322: iconst_1`。
  所有直接静态调用均传入字面量 `true`。但在真实运行环境中，反射调用、未扫描的三方插件或宿主其他未显式引用符号的动态入口不在本静态扫描覆盖范围内。

---

### 1.2 触发场景分类矩阵

| 操作场景 | 是否调用 updateInterpolatedForms | 证据与触发入口 | 影响对象范围 | 内部重算范围 |
| :--- | :---: | :--- | :--- | :--- |
| **普通调参**<br>(拖拽参数滑块 / 时间轴播放) | **未发现调用**<br>*(基于静态调用链推导，未跑UI实测)* | 静态调用图中 `l.class` 仅被 UI 拖拽 Action、`endEdit`、`restoreOptionalState` 引用；常规帧求值走 `model.update()` -> `keyformGrid.c()` | 当前活动模型受影响对象 | 0（仅对已有网格混合求值，不触发形态缓存重算） |
| **源形状拖拽编辑**<br>(控制点拖拽 / 变形笔刷 / 变换框) | **是** | `CEAct_SelectOrMove_Point_forAll`、`CEAct_BoundingBox` 等交互每帧调用 `l.a(source, isDragging=true)`（带 50ms 防抖）；<br>松手进入 `CModelingEditMode_Main.endEdit` 调用 `l.a(this, false)` 同步执行 | **当前对象所属直接父 Part 下的所有启用扩展插值的直接兄弟对象** | 该对象的**全部非线性扩展参数轴、全部组合切片、全部 Morph Target** 全量重算 |
| **拓扑变更**<br>(增删顶点 / 网格重划 / 变形器分割数变更) | **是** | `isCompatible` 失败或 `isNeedSetupFormStructure` 为 true，触发 `setupFormStructure` 重建网格骨架 | 目标对象及同 Part 广播对象 | 彻底销毁旧派生 `ACForm`，重新分配对象列表并全量重算坐标 |
| **Undo / Redo** | **是** | `CModelingEditMode_Main.restoreOptionalState` 恢复状态后显式调用 `l.a.a(this)` | 当前选中对象所属直接父 Part 下的所有扩展对象 | 同父部件下所有扩展对象的全部扩展形态强制全量重算 |
| **扩展参数设置**<br>(在参数面板设置插值类型/点数) | **是** | `com.live2d.cubism.view.palette.parameter.b` 遍历选中对象列表逐一调用 `l.a(obj, true)` | 选中的所有对象 | 选中的每个对象重新 setup 并全量重算 |

---

### 1.3 跨对象传播：部件（Part-scope）广播核查

反编译 `ExtendedInterpolationUtil`（`l.java`）中私有方法 `a(ACParameterControllableSource)`：

```java
CPartGuid cPartGuid = aCParameterControllableSource.getParentGuid();
List<CPartSource> list = aCParameterControllableSource.getModelSource().getPartSourceSet();
for (CPartSource part : list) {
    if (Intrinsics.areEqual(part.getGuid(), cPartGuid)) {
        List<CParameterControllableGuid> list2 = part.getChildGuids();
        if (list2 != null) {
            for (CParameterControllableGuid childGuid : list2) {
                for (ACParameterControllableSource obj : modelSource.getAllObjects()) {
                    if (Intrinsics.areEqual(childGuid, obj.getGuid())) {
                        CExtendedInterpolationExtension ext = ...;
                        if (ext != null && ext.isExtendedInterpolationEnabled()) {
                            ext.updateInterpolatedForms(true);
                        }
                    }
                }
            }
        }
    }
}
```

#### 核查结论
1. **是否包含所有兄弟**：包含同一直接父部件下的**所有直接子对象**（当前对象自身亦在 `childGuids` 中）；
2. **是否递归子 Part**：**否**。代码仅调用了 `part.getChildGuids()`，**没有调用 `getAllDescendant()`**，亦无递归调用。嵌套在子 Part 内的对象不会被本级循环直接广播；
3. **筛选条件**：
   - 对象的 GUID 必须命中 `part.getChildGuids()` 且存在于 `modelSource.getAllObjects()`；
   - 对象必须挂载 `CExtendedInterpolationExtension` 实例；
   - 必须通过 `ext.isExtendedInterpolationEnabled()` 为 true 门禁（即该兄弟对象自身启用了非线性扩展插值）。

---

### 1.4 重算深度：每次遍历还是每次实际重算？

在 `updateInterpolatedForms_common` 内部：
1. **线性基底重算**：对每一个插入点派生形态，调用 `form.zero()` 清零，然后对相邻左右普通键调用 `form.blend(left, 1-t); form.blend(right, t); form.postBlend()`。对派生形态的所有属性完成线性加权；
2. **几何点非线性曲线重算**：外层按几何顶点数循环（`vertexCount` 轮），每轮调用 `extendedInterpolationType.b()` 动态创建数学计算器（ELLIPSE / SNS），收集各普通键位置向量，逐个插入键重新求值，并通过 `moveToOnLocal` 写回坐标。

**结论**：**实际深度重算（Full Recomputation）**，而非只读遍历。每个派生形态的所有几何点坐标在每次刷新中均被重新算值并覆写。

---

## 二、补充调查：旋转变形器（CRotationDeformerForm）审计与实测

### 2.1 结构与行为分析

1. **点引用情况**：
   - `CRotationDeformerForm.getAllPointRef()` 源码为：
     ```java
     public List<RotationDeformerPointRef> getAllPointRef() {
         return CollectionsKt.listOf(new RotationDeformerPointRef(this.getSource(), this));
     }
     ```
   - 长度**恒等于 1**。该唯一的 PointRef 代表旋转变形器的**旋转中心原点（Origin X/Y）**；
   - `CRotationDeformerForm` **不存在 `getPositions()` 网格顶点数组**，其内部主要字段为 `originX`, `originY`, `angle`, `scale`。
2. **common 方法对旋转变形器的处理流程**：
   - **外层形态循环**：`blend()` 对 `angle`（旋转角）、`scale`、`origin` 统一走常规线性加权累加。`angle` 和 `scale` 在此阶段完成插值，**不参与后续非线性曲线运算**；
   - **内层几何点循环**：点引用长度为 1，循环**正好执行 1 轮**。计算器为旋转原点 `(originX, originY)` 计算椭圆或 SNS 轨迹，并通过 `RotationDeformerPointRef.moveToOnLocal` 直接更新派生形态的 `originX` 与 `originY`。
3. **副作用与适配定位**：
   - `RotationDeformerPointRef.moveToOnLocal` 存在真实赋值与状态修改：
     ```java
     cRotationDeformerForm2.setOriginX(cRotationDeformerForm2.getOriginX() * (1.0f - f2) + gVector2.getX() * f2);
     cRotationDeformerForm2.setOriginY(cRotationDeformerForm2.getOriginY() * (1.0f - f2) + gVector2.getY() * f2);
     cRotationDeformerForm2.setUpdated();
     ```
   - 尽管几何点数极少带来很低的几何级收益，但由于存在明确的坐标与版本变更副作用，为了完全覆盖用户场景，主代理将补上对 origin 的适配。

### 2.2 官方类动态探针测量数据（5.3.04 原生隔离 JVM）

探针源码及日志见 `refresh-evidence/RotationDeformerScalingProbe.java` 和 `refresh-evidence/RotationDeformerScalingProbe.log`。固定 1 个对象、3 个源普通键、scale=1.0、旋转原点沿弧线分布，测量单次耗时与内存分配（200 次重复，ThreadMXBean 测量）：

| 对象类型 | 插值类型 | 插入点/区间 | 总形态数 | 单次耗时 (ms) | 单次分配 (bytes) | 对比 Warp (64点/131形态) 差距 |
| :--- | :--- | ---: | ---: | ---: | ---: | :--- |
| **RotationDeformer** | ELLIPSE | 1 | 5 | 0.0124 ms | 2,128 B | - |
| **RotationDeformer** | ELLIPSE | 4 | 11 | 0.0099 ms | 3,232 B | - |
| **RotationDeformer** | ELLIPSE | 16 | 35 | 0.0270 ms | 7,648 B | - |
| **RotationDeformer** | **ELLIPSE** | **64** | **131** | **0.0897 ms** | **25,312 B** (~25 KB) | **耗时仅 Warp 的 1/100，分配仅 1/135** |
| **RotationDeformer** | SNS_CURVE | 1 | 5 | 0.0338 ms | 6,241 B | - |
| **RotationDeformer** | SNS_CURVE | 4 | 11 | 0.0351 ms | 6,810 B | - |
| **RotationDeformer** | SNS_CURVE | 16 | 35 | 0.0735 ms | 11,664 B | - |
| **RotationDeformer** | **SNS_CURVE** | **64** | **131** | **0.3079 ms** | **34,656 B** (~35 KB) | **耗时仅 Warp 的 1/13，分配仅 1/166** |
| *Warp (参考基准)* | *ELLIPSE* | *64* | *131* | *8.8410 ms* | *3,359,000 B* (~3.36 MB) | 基准 |
| *Warp (参考基准)* | *SNS_CURVE* | *64* | *131* | *3.9790 ms* | *5,638,000 B* (~5.64 MB) | 基准 |

---

## 三、独立低风险 P3 优化审计：firstOrNull 替换

### 3.1 现状与替换逻辑
在 `CExtendedInterpolationExtension.updateInterpolatedForms_exe` 首行：
```java
ACForm aCForm2 = (ACForm)CollectionsKt.firstOrNull(this.getAllExtendedKeyforms());
```
其中 `getAllExtendedKeyforms()` 定义为 `plus(normalExtendedKeyforms, morphTargetExtendedKeyforms)`，每次调用分配全新的 `ArrayList` 并拷贝全量形态引用。

#### 替换实现（必须基于 `!normal.isEmpty()`）
```java
List<ACForm> normal = ext.getNormalExtendedKeyforms();
ACForm aCForm2;
if (!normal.isEmpty()) {
    aCForm2 = (ACForm) CollectionsKt.firstOrNull(normal);
} else {
    aCForm2 = (ACForm) CollectionsKt.firstOrNull(ext.getMorphTargetExtendedKeyforms());
}
```
**关键修正**：判定条件必须是 `!normal.isEmpty()`，而不能是 `normal.firstOrNull() != null`。因为若 `normal` 列表非空但其首元素为 `null`（极端脏状态），官方 `plus` 拼接后首元素亦为 `null`，此时绝不能落入 `morphTargetExtendedKeyforms`。

### 3.2 动态探针实测验证（FirstOrNullAuditProbe）

源码及日志见 `refresh-evidence/FirstOrNullAuditProbe.java` 和 `refresh-evidence/FirstOrNullAuditProbe.log`：

1. **4 场景分支等价性验证**：
   - **场景 1（Normal + Morph 均存在）**：官方与优化方案均返回 `normal[0]`，引用一致；
   - **场景 2（Morph-only，Normal 为空）**：官方与优化方案均返回 `morph[0]`，引用一致；
   - **场景 3（Normal 非空但首元素为 null）**：官方返回 `null`，优化方案因 `!normal.isEmpty()` 亦返回 `null`，**未落入 morph**，行为一致；
   - **场景 4（均为空 Empty）**：官方与优化方案均返回 `null`，触发相同防御分支。
2. **性能与内存分配对比（131 Normal + 20 Morph，共 151 形态，100,000 次测量）**：
   - **官方实现**：**802.59 ns / call**，单次分配 **1,288.0 bytes**；
   - **优化实现**：**27.19 ns / call**（提速 **29.5 倍**），单次分配 **0.0 bytes（100% 消除该处分配）**。
3. **范围约束**：除该处外，`getAllExtendedKeyforms()` 在全库仅有 3 处其他调用（按 GUID 遍历、Warp 分割数备份等），那些位置确需全量列表，保持原语义不改动。

---

## 四、验证与证据清单

### 4.1 验证性质划分说明

| 证据项 | 验证方式 | 证据等级 | 备注 |
| :--- | :--- | :---: | :--- |
| `updateInterpolatedForms_exe` 控制流与跳过分支 | CFR 反编译 + `javap -c -p` 字节码指令对照 | **源码确定** | 确认 `(!bl && !bl2)` 跳过判定机制 |
| 全版本 `forceUpdate=true` 硬编码 | 5.0.00–5.3.04 共 20 版本 JAR 逐一扫描 `invokevirtual updateInterpolatedForms` | **源码确定** | 确认已知直接静态调用点均入栈 `iconst_1` |
| UI 编辑同 Part 直接兄弟广播机制 | CFR 反编译 `l.java` (`ExtendedInterpolationUtil`) | **源码确定** | 确认按 `part.getChildGuids()` 遍历，无递归 |
| 旋转变形器 点引用恒为 1 及字段构成 | 反编译 `CRotationDeformerForm` / `RotationDeformerPointRef` | **源码确定** | 确认无 positions 数组，仅 1 个 Origin 点 |
| 旋转变形器 耗时/内存与插值点关系 | 5.3.04 官方类隔离 JVM 动态探针实测 | **实测确认** | 椭圆 0.089 ms/25 KB，SNS 0.308 ms/35 KB |
| P3 `firstOrNull` 语义等价与 0 分配 | 官方类 4 场景差分测试 + 10 万次内存探针实测 | **实测确认** | 100% 消除 1,288 B 分配，提速近 30 倍 |
| 普通调参走常规帧求值不重算缓存 | 静态调用链路推导 | **推导确认** | 基于类与方法引用图分析，未做真实 UI 实测 |
| 真实 Editor 鼠标拖动端到端帧率/延迟 | **未测试** | **边界声明** | 本报告不拿隔离 JVM 纳秒测试推算 UI FPS |

### 4.2 证据归档文件索引
- `refresh-evidence/RotationDeformerScalingProbe.java` & `.log`：旋转变形器动态探针源码与原始日志
- `refresh-evidence/FirstOrNullAuditProbe.java` & `.log`：P3 firstOrNull 优化审计探针源码与原始日志
- `refresh-evidence/scan-update-calls-20versions.py` & `.log`：20 个版本静态调用点扫描脚本与结果
- `refresh-evidence/README.md`：复核命令与测量环境说明
