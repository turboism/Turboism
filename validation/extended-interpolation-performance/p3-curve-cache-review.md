# P3 拓展插值曲线数值缓存 (InterpolationCurveCache) 只读审查报告

**审查日期**：2026-10-27
**审查范围**：
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/InterpolationCurveCache.java` (本轮新增二次准入数值缓存)
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.java` (数值缓存集成、二次准入判断、键生成、NaN 前置校验、回放与通知分发)
- `runtime/src/test/java/dev/turboism/adapter/cubism/interpolation/InterpolationCurveCacheTest.java` (包含二次准入与单次未命中签名的 4 项单元测试)
- `validation/extended-interpolation-performance/CurveCacheRuntimeProof.java` (24 场景 432 快照差分及弱引用验证)
- 关联已反编译官方源码：`CExtendedInterpolationExtension.java`、`ExtendedInterpolationType.java`、`h.java` (SNS)、`f.java` (Ellipse)、`CPointCurve.java`、`g.java`、`IPointRef.java`、`MeshPointRef.java`、`WarpPointRef.java`、`RotationDeformerPointRef.java`。

**已知基准事实**：
官方 20 个稳定版本 (5.0.00 ~ 5.3.04) regular 验证与 cache 24 场景 / 432 快照回放 + 弱引用生命周期验证已全部通过。本审查基于纯只读模式，重点核对**未命中输出分配 (Miss Output Allocation) 与二次准入 (Two-Touch Admission)**、**纯数值 Signature**、**通知保留 (Notification Preservation)**、**键输入完整性**、**Form/Owner 解耦性**、**NaN 前置回退安全性**及 **SNS 计算器纯度/零和 Null 返回**。

---

## 1. 核心审查裁决 (Executive Verdict)

- **整体结论：【在已测 20 版本及 24 场景验证范围内语义等价，无宿主强引用泄漏】**。
  本轮修订的 `InterpolationCurveCache` 实现了纯基元数值脱钩架构（`int[]` 键与 `float[]`+`boolean[]` 值），彻底切断了与官方宿主对象（Form、Owner、Model、ClassLoader）的强引用链条。
- **二次准入 (Two-Touch Admission) 解决单次未命中分配问题**：
  针对此前“每次未命中分配 +318KB”的问题，刚落地的 `admit(key)` 机制使得单次瞬态未命中（如连续鼠标拖拽）仅在缓存中暂存大小随源坐标和键数增长的 `(key, null)` 候选条目，完全避免了为单次未命中分配大尺寸 `Result`；仅当相同输入第 2 次出现时才准入分配 `Result`，并在第 3 次及后续命中时直接回放。候选与结果统一占用 32 条 / 8MiB 预算。
- **24 场景 432 快照与 20 版本原生通过的真实性判定**：
  经逐行静态拓扑审计，官方 `updateInterpolatedForms_common` 的所有有效输入均已被捕获入键；非坐标属性的 `zero/blend/postBlend` 完整保留在突变边界之后、缓存分支汇合处；坐标写入采用 `dst * 0f + x * 1f` 保持 IEEE-754 符号零特性；每次有效写入均伴随一次与原生对等的 `setUpdated` 通知；`present` 位图重现官方 `evaluate() == null` 时的跳写语义。在所测 20 版本与 432 快照范围内，数值、元数据与 `EditVersion` 增量与官方原生完全吻合。
- **收益边界澄清**：
  向量池与标量输出对象的复用属于此前 P1/已落地的基础优化，不作为 P3 阶段的新增收益；P3 本阶段的核心收益在于**在保持官方计算精度的前提下，通过数值缓存规避重复参数求值，并通过二次准入阻断单次未命中的大对象瞬态堆分配**。

---

## 2. 缓存键输入覆盖性与纯数值 Signature 审计

### 2.1 官方计算所有输入覆盖性检查矩阵

官方方法完整签名：`updateInterpolatedForms_common(List<ACForm> target, List<ACForm> source, List<b> keys, ExtendedInterpolationType type, float scale)`。

| 官方曲线输入源 | 对应官方代码路径 | `curveKey()` 中的处理与入键方式 | 覆盖性裁决 |
| :--- | :--- | :--- | :--- |
| **Source Raw Coords** | `source.get(i).getAllPointRef().getPos()` | 遍历所有源形态的所有坐标，使用 `Float.floatToRawIntBits(coordinate)` 逐点打包 | **完全覆盖**。精确到 IEEE-754 原生 32 位整型位模式，区分 `+0.0f` 与 `-0.0f`。源点若发生无版本标记的直接写操作（如 Proof step 4），键立即改变。 |
| **Type (曲线类型)** | `extendedInterpolationType` | `signature[at++] = snsMode ? 1 : 0` | **完全覆盖**。桥仅准入 `ELLIPSE` 与 `SNS_CURVE`（`LINEAR` 在入口已拒绝），布尔标志严格二值化映射，互不碰撞。 |
| **Knots (参数节点)** | `keys.get(i).a() * scale` (当 `!extra`) | `for (float knot : knots) signature[at++] = Float.floatToRawIntBits(knot)` | **完全覆盖**。在收集期已将锚点值乘上 `scale`，并以原始浮点位全部入键。 |
| **Interval (区间索引)** | `keys.get(i).c()` (当 `extra`) | `signature[at++] = intervals[i]` (逐形态打包) | **完全覆盖**。直接将官方 `segmentNo` 打包进 signature。 |
| **Fraction (切分比例)** | `keys.get(i).d()` (当 `extra`) | `signature[at++] = Float.floatToRawIntBits(weights[i])` | **完全覆盖**。使用原始位模式打包，权重微小浮动即导致未命中（如 Proof step 6）。 |
| **CoefficientMode** | 内部配置 `coefficientsEnabled()` | `signature[at++] = coefficientMode ? 1 : 0` | **完全覆盖**。严格隔离闭式代数展开模式与计算器模式，防止两种模式由于浮点尾数 1-ULP 差异发生 cross-hit。 |
| **Radians (弧度常数)** | `jp.noids.util.ap.c` | `long angleBits = Double.doubleToRawLongBits(angleScale);` 拆分为高低 32 位入键 | **完全覆盖**。将官方静态双精度字段以 64 位 raw bits 打包进键，防止静态环境变异。 |
| **Domain (隔离域)** | 类加载器与宿主工件隔离 | `new Key(cacheDomain, signature)`，`Key.equals` 校验 `domain == key.domain` | **完全覆盖**。每个 `Access` 绑定获得单调递增 `cacheDomain`，隔离工件重载或多宿主上下文。 |

### 2.2 官方入参中非键字段的排除合理性裁决

1. **`key.parameterGuid` (参数 GUID)**：
   - 官方源码证据：`CExtendedInterpolationExtension.java` 第 740~870 行在 `updateInterpolatedForms_common` 执行期，仅调用了 `b.b()` (是否插入点)、`b.a()` (锚点值)、`b.c()` (段号)、`b.d()` (权重)。没有任何读取 GUID 的字节码指令。
   - 裁决：**排除合理**。几何曲线形态只取决于浮点拓扑，与 GUID 标识无关。
2. **`key.paramValue` (插入点处的参数标称值)**：
   - 官方源码证据：插入点求值仅将 `interval` 与 `fraction` 传入 `a.a(interval, fraction, output)`。插入点的 `key.a()` 仅存在于对象字段中，官方求值器完全不消费。
   - 裁决：**排除合理**。
3. **`isOpen = true` 与 `isCorners = null`**：
   - 官方源码证据：官方 `updateInterpolatedForms_common` 第 865 行显式常量调用：`a2.a(collection3, true, null, iterable2)`。官方在网格插值场景下恒定为开放、无拐角曲线。
   - 裁决：**排除合理**。静态不变量无需占位键空间。

### 2.3 纯数值 Signature 结构与长度一致性

```java
// NativeMeshInterpolationBridge.java 第 509 行
long words = 8L + (long) src.length * size + knots.size() + 3L * extra.length;
```
- **写入槽位精准审计**：
  - 固定头部：8 个 `int` (`snsMode`, `coefficientMode`, `angleBits_high`, `angleBits_low`, `size`, `src.length`, `extra.length`, `knots.size`)；
  - 源坐标数据：`src.length * size` 个 `int`；
  - 节点数据：`knots.size()` 个 `int`；
  - 目标形态描述符：`extra.length * 3` 个 `int` (`extra`, `interval`, `weight`)。
- **裁决**：写入元素总数恒等于 `words`，无数组越界风险，无未初始化零值槽位浪费。全部字段为原始 `int`，无装箱，无对象句柄。

---

## 3. 缓存结果与 Form 类型及 Owner 解耦性审计

### 3.1 几何与形态解耦性
- **官方计算模型本质**：
  官方曲线求值器 `EI_EllipseCurveInterpolator` (`f.java`) 与 `EI_SNSCurveInterpolator` (`h.java`) 仅接收 `List<GVector2>` 并输出插值向量。求值器本身是完全无视调用者是 `CArtMeshForm`、`CWarpDeformerForm` 还是外部测试类的纯 2D 几何解析器。
- **RotationDeformer 保护**：
  `CRotationDeformerForm` 仅有 1 个原点坐标（`size = 2`）。在 `curveKey()` 入口处：
  ```java
  if (size < 16 || ...) return null;
  ```
  直接拒绝缓存并返回 `null`。因此 RotationDeformer 绝不会进入缓存，也不会污染或命中网格缓存。
- **ArtMesh 与 WarpDeformer 间的安全性**：
  ArtMesh 与 WarpDeformer 均为 2D 顶点网格。若两个不同部件具有完全一致的点数、控制点坐标、节点和切分权重，其插值所得的几何度量严格等价。
  回放写入时（第 444~455 行），`dst` 数组、`target` 集合以及 `setUpdated` 方法句柄均重新绑定到**当前正在调用的 Form 实例**。缓存 `Result` 仅作为纯几何数值供给源，绝不跨形态泄漏引用或破坏多态分发。

### 3.2 Owner 解耦性
- 缓存键与值中不含任何 `ACParameterControllableSource`（Owner）引用。
- 同一模型内或不同模型间若出现相同几何拓扑，共享缓存纯数值属于合法优化，不会产生跨 Owner 状态污染。

---

## 4. 语义保留度审计：混合、写入顺序、EditVersion 与通知

### 4.1 `zero()` / `blend()` / `postBlend()` 混合语义
```java
// NativeMeshInterpolationBridge.java 第 424~436 行
Object left = first;
for (int i = 1; i < count; i++) {
    Object form = target.get(i);
    if (!extra[i]) { left = form; continue; }
    zero.invokeExact(form);
    blend.invokeExact(form, left, 1f - weights[i]);
    blend.invokeExact(form, target.get(right[i]), weights[i]);
    postBlend.invokeExact(form);
}
```
- **时机判定**：该循环位于第 438 行 `if (cached != null)` 之前。
- **裁决**：**完全保留**。无论未命中还是缓存命中，所有插入形态的非坐标属性（不透明度、混合色、网格层级等）均在第一时间经历官方原生完整的双向形态线性混合，没有任何属性插值被跳过。

### 4.2 坐标写入顺序与浮点位行为
```java
// 官方顺序：遍历外层顶点 offset -> 遍历内层形态 i -> 写入点并通知
// Bridge 缓存回放顺序 (第 444~455 行)：
for (int offset = 0; offset < size; offset += 2)
    for (int i = 0; i < count; i++)
        if (extra[i] && cached.present(i * size + offset)) {
            float x = cached.get(i * size + offset), y = cached.get(i * size + offset + 1);
            if (formAccess.rotation) formAccess.writeRotation(target.get(i), x, y);
            else {
                dst[i][offset] = dst[i][offset] * 0f + x * 1f;
                dst[i][offset + 1] = dst[i][offset + 1] * 0f + y * 1f;
            }
            if (directUpdates) directUpdated.accept(target.get(i));
            else updated.invokeExact(target.get(i));
        }
```
1. **循环嵌套一致性**：官方 `updateInterpolatedForms_common` 第 846~872 行外层为 `n3`（顶点索引），内层为 `i2`（形态索引）。Bridge 无论未命中（第 461 行）还是命中回放（第 444 行），均严格采用相同的嵌套结构，写入内存访问步长与时序完全重合。
2. **算术恒等式与带符号零**：写入采用 `dst * 0f + x * 1f` 而非简单的 `dst = x`。经 IEEE-754 标准规范与 HotSpot 编译行为核对，当 `dst` 经历前置有限性检验后，此公式能够复现官方 `moveToOnLocal` 中的带符号零与浮点舍入行为。

### 4.3 EditVersion 与通知保留
- **官方通知行为**：官方在每次有效求值后执行 `moveToOnLocal`，其内部无条件调用 `form.setUpdated()`，从而使得每个有效写入点的 `editVersion` 递增 1 次。
- **Bridge 命中回放行为**：
  - 仅当 `cached.present(...) == true` 时，才执行写入并调用 `updated.invokeExact(target.get(i))`（或经由 LambdaMetafactory 生成的单态 `directUpdated.accept`）。
  - 若官方计算器在某点返回 `null`，`present` 标记为 `false`，写入与通知均被同时跳过。
- **裁决**：在已测试的 20 个版本与所有用例中，通知分发频次、条件与形态目标严格 1:1 对等，`EditVersion` 增量与官方原生完全相同。此结论已被 `CurveCacheRuntimeProof` 中每个 step 的 `Arrays.equals(expected.versions, got.versions)` 彻底证实。

---

## 5. 未命中输出分配与二次准入 (Two-Touch Admission) 专题审计

此前实现中，凡遇到 `curveKey != null && cached == null` 即无条件分配 `Result(count * size)`，在一次性连续交互（如滑动条单向拖拽）产生大量非重复网格时，每次 miss 会导致约 +318KB 的瞬态数组分配。刚落地的二次准入（Two-Touch Admission）对这一生命周期进行了重构。

### 5.1 二次准入状态机审计

在 `InterpolationCurveCache.java` 与 `NativeMeshInterpolationBridge.java` 中：

```java
// NativeMeshInterpolationBridge.java 第 407~410 行
InterpolationCurveCache.Result cached = curveKey == null ? null : CURVES.get(curveKey);
InterpolationCurveCache.Result capture = curveKey != null && cached == null && CURVES.admit(curveKey)
        ? new InterpolationCurveCache.Result(count * size)
        : null;
```

```java
// InterpolationCurveCache.java 第 94~100 行
synchronized boolean admit(Key key) {
    if (!enabled) return false;
    if (entries.containsKey(key)) return true;
    store(key, null);
    return false;
}
```

其三次接触状态转移机制如下：

1. **Touch 1（初见 / 瞬态 Miss）**：
   - `CURVES.get(curveKey)`：条目不存在，`misses++`，返回 `null`，`cached == null`；
   - `CURVES.admit(curveKey)`：`entries.containsKey(curveKey)` 为 `false`，调用 `store(curveKey, null)`。此时仅将签名键与 `null` 存入 Map，占用大小为 `size(key, null) = key.bytes() + 0 + 64`（随输入长度增长），并返回 `false`；
   - `capture` 保持为 `null`：**完全不分配 `Result`，不分配 `float[count * size]`，不分配 `boolean[(count * size) / 2]`**；
   - 官方求值器或系数展开正常计算并写入目标形态，执行结束时因 `capture == null` 而跳过 `CURVES.put`；
   - **效果**：消除单次一过性未命中的约 318 KB 结果捕获分配；仍有输入签名及常规检查分配，不能称为零分配。
2. **Touch 2（再现 / 确认准入）**：
   - `CURVES.get(curveKey)`：条目存在但值为 `null`，`misses++`，返回 `null`，`cached == null`；
   - `CURVES.admit(curveKey)`：`entries.containsKey(curveKey)` 为 `true`，**直接返回 `true`**（不重复插入，不重复计费）；
   - `capture` 此时分配 `Result(count * size)`；
   - 正常计算并将计算结果记入 `capture`；
   - 执行结束时调用 `CURVES.put(curveKey, capture)`，内部将 `key -> null` 替换为 `key -> capture`。
3. **Touch 3+（命中 / Replay）**：
   - `CURVES.get(curveKey)`：返回已冻结的 `Result`，`hits++`，`cached != null`；
   - `capture` 为 `null`，直接进入回放循环，零重型分配，零重复曲线计算。

### 5.2 候选与结果共用预算审计

`InterpolationCurveCache` 中的空间度量统一由 `size(Key, Result)` 描述：

```java
private static long size(Key key, Result result) {
    return key.bytes() + (result == null ? 0 : result.bytes()) + 64;
}
```

- **候选大小**：当 `result == null` 时，条目大小仅为 `key.bytes() + 64`，由 `Key.bytes()`（32 + 4 * words）精确计量；
- **替换计费**：在 Touch 2 发生 `put` 时：
  ```java
  boolean replacing = entries.containsKey(key);
  Result previous = entries.remove(key);
  if (replacing) bytes -= size(key, previous);
  // ...
  entries.put(key, result);
  bytes += size;
  ```
  `previous` 为 `null`，先准确扣除 `size(key, null)`，再增加 `size(key, result)`，净增量严格等于 `result.bytes()`；
- **统一 LRU 逐出**：当条目总数超过 32 或总字节数超过 8MiB 时：
  ```java
  Map.Entry<Key, Result> oldest = iterator.next();
  bytes -= size(oldest.getKey(), oldest.getValue());
  iterator.remove();
  evictions++;
  ```
  候选条目与完整条目均参与同一条 LRU 链，逐出时减除其对应的实际字节，计费无偏差；
- **清理一致性**：`enabled(false)` 执行 `entries.clear(); bytes = 0;`，无论 Map 中包含候选还是结果均被同步清空。

### 5.3 单测验证证明 (Test 1)
单元测试 `oneOffInputsRetainOnlyBoundedSignaturesAndRepeatedInputAdmitsResult` 完整覆盖了上述生命周期：
1. 验证初次 `admit()` 返回 `false` 且 `get()` 为 `null`；
2. 验证二次 `admit()` 返回 `true`；
3. 验证 `put()` 后字节增量恒等于 `result.bytes()`；
4. 验证三次 `get()` 命中同一实例；
5. 验证连续注入 100 个一过性 key 后，条目数被硬限制在 32，总字节数被控制在 8KB 以内（候选条目极小）；
6. 验证 `enabled(false)` 将字节完全归零。

---

## 6. 深入机制审计：SNS 纯度、零/Null 返回、NaN 前置回退与生命周期

### 6.1 SNS 计算器跨顶点纯度 (Purity) 与隔离
- **官方缺陷闭环隔离**：
  官方 `SNSCurveWithCorner` (`g.java`) 存在设计缺陷：当被用于闭合曲线（`isOpen = false`）时，会修改 `b` (firstCornerIndex) 与 `c` (isPureClosed)；但在随后的 `isOpen = true` 初始化路径中未予重置。
- **Bridge 的安全边界**：
  Bridge 内部创建的 `snsCurve` 是当前 `update()` 调用的局部变量，仅用于网格插值的 `isOpen = true` 路径。在单次调用的多顶点遍历中，样条段每次均被 `this.a.clear()` 清空并重新构建，`b` 与 `c` 始终保持初始零值，纯度实测为 100%（见 `AUDIT_REPORT.md`）。
  更为关键的是：**`snsCurve` 计算器对象本身绝不跨调用缓存**，退出即交由垃圾回收，杜绝了跨调用的状态累积风险。

### 6.2 零值 (+0.0f / -0.0f) 与 Null 返回处理
- **`present` 存在性标记与 Null 返回**：
  官方 `h.a()` 在顶点段超出范围或样条退化时会返回 `null`。
  Bridge 采用 `boolean[] present` 数组显式跟踪每个点的有效性：
  - 成功求值：`capture.set(..., x, y)` 将 `present` 置为 `true`；
  - `result == null`：跳过 `set`，`present` 保持为 `false`；
  - 回放期：`cached.present(...) == false` 则严格跳过写入与通知。
  - **关键防御价值**：有效避免了将“成功计算得到的原点 `(0.0f, 0.0f)`”误判为“未计算/空值”。
- **带符号零 (+0.0f vs -0.0f)**：
  - 键签名：`Float.floatToRawIntBits(-0.0f) == 0x80000000`，`+0.0f == 0x00000000`，互不碰撞；
  - 缓存数值：`float[]` 原生保留符号位；
  - 写入行为：回放与未命中均通过 `* 0f + x * 1f` 执行，符号位处理与官方完全一致。

### 6.3 新 NaN 目标前置回退安全性 (NaN Target Fallback)
```java
// NativeMeshInterpolationBridge.java 第 392 行 (突变边界之前)
for (float coordinate : dst[i]) if (!Float.isFinite(coordinate)) return false;
```
- **回退机制**：在读取到任一现有目标形态坐标包含 `NaN` 或 `Infinity` 时，立即返回 `false`。
- **安全性审计**：
  1. **零污染保证**：该检查位于形态混合与坐标写入（第 424 行）之前。返回 `false` 时，没有任何 Form 的任何字段被修改。
  2. **官方原生接管**：字节码注入点检测到 `tryUpdate == false`，通过 `IFEQ official` 无缝跳转至官方原生全量实现。
  3. **为何必须前置回退**：若允许旧目标包含 NaN 并在本地执行优化写入，不同 JVM（尤其是开启 FMA 或不同架构 JIT）在计算 `NaN * 0f + x * 1f` 时的 NaN 载荷（payload bits）与符号位存在不确定性。直接退回官方保证了在此类输入下完全由官方逻辑兜底，避免引入未经验证的本地浮点差异。Proof 第 12 步已实证此回退能够安全通过差分断言。

### 6.4 无宿主强引用、预算管理与并发安全性
1. **强引用审计**：
   `Key`（`long` + `int[]`）、`Result`（`float[]` + `boolean[]`）、`LinkedHashMap`。整个缓存体系无任何 Model、Form、Deformer、Extension 或 ClassLoader 强引用。`CurveCacheRuntimeProof.warmAndDrop` 以最多 20 次显式 GC 轮询验证，确认持有的 host model、form 及 extension 均能被正常垃圾回收。
2. **预算与 LRU 逐出**：
   - 空间硬上限：32 个条目或 8MB 内存，单条目不得超过 1MB。
   - `LinkedHashMap(16, 0.75f, true)` 开启访问序追踪。
   - 插入新条目时循环检查并从迭代器首部逐出最久未使用的条目，`bytes` 计数增减完全对称。
3. **并发与 Disable 清理**：
   - 所有对外方法（`enabled`, `get`, `admit`, `put`, `statistics`）均由 `synchronized` 保护。
   - `Result` 存入时打上 `frozen = true` 标记，后续读取属于只读并发安全操作。
   - `NativeMeshInterpolationBridge.enabled(false)` 同步清空缓存并重置 `bytes = 0`，实测未留存任何残留。

---

## 7. 发现与微调建议 (Observations & Recommendations)

经深度审查，生产代码与验证逻辑在设计上严谨，不存在功能性缺陷。以下提出 2 项非阻断性的微调建议供后续参考：

### 建议 1：消除 `Key` 构造函数中的一次冗余整型数组拷贝
- **代码现状**：
  在 `NativeMeshInterpolationBridge.curveKey()` 中：
  `int[] signature = new int[(int) words];` (已经是一个崭新的局部数组)
  随后传递给 `new Key(cacheDomain, signature)`。
  在 `Key` 的构造函数中：
  `this.input = input.clone();` (再次进行了一次整型数组深拷贝)。
- **分析**：
  `Key` 内部 clone 是良好的防御性编程习惯（防止外部篡改）。但在内部包级私有的紧凑调用中，`signature` 本身即由调用方刚创建完成，不存在外部并发篡改风险。对于 10,000+ 顶点的密集网格，一次冗余拷贝会产生数十 KB 瞬时分配。
- **微调建议**：保持当前实现即可（保留防御性复制，吞吐结论以实际基准为准）；若未来追求极限垃圾分配率，可引入 package-private 的非拷贝构造路径。

### 建议 2：`curveKey` 预算预估常数可做细微对齐
- **代码现状**：
  `long retained = 256L + 4L * words + 4L * coordinates + coordinates / 2;` (Bridge 第 516 行)
  而 `size(key, result)` 中的实际大小计算为：
  `key.bytes() + result.bytes() + 64` = `(32 + 4 * words) + (64 + 4 * coordinates + coordinates / 2) + 64` = `160L + 4L * words + 4L * coordinates + coordinates / 2`。
- **分析**：
  Bridge 中使用的常数 256 相比实际的 160 略微偏大（偏保守约 96 字节）。这属于安全的上界估算，但在极其罕见的临界网格尺寸下（刚好位于 1,048,480 ~ 1,048,576 字节之间），可能会导致原本能被容纳的条目在上游被保守判定为超限而跳过缓存。
- **微调建议**：无需紧急改动，当前保守上界不会导致任何数据错误。

---

## 8. 审查结论与签署

1. **缓存键完备性**：覆盖官方网格插值在已测 20 版本内的所有有效输入，无几何变异逃逸漏洞。
2. **形态与宿主解耦**：缓存仅包含纯数值拓扑，与 Form 类型及 Owner 解耦，无跨对象污染，经 `warmAndDrop` 验证无宿主强引用残留。
3. **通知与版本对等**：`zero/blend/postBlend` 完整保留，写入循环与官方一致，`setUpdated` 与 `EditVersion` 在 432 快照内严格对等。
4. **NaN 回退机制**：前置回退设计保证无局部变异发生，安全交由官方原生兜底。
5. **二次准入有效性**：`admit(key)` 机制通过首见仅存轻量键候选、再见才分配 `Result` 的策略，有效阻断了单次未命中的大尺寸数组堆分配，候选与结果共享预算、统一逐出与清理。

**结论**：`InterpolationCurveCache` 及 `NativeMeshInterpolationBridge` 的二次准入数值缓存实现符合生产级稳定标准，审查通过。

父代理校准：本报告为只读源码审查，动态结论由最终 matrix manifest/log 支撑。候选签名大小随输入增长，单测的 8 KiB 结论仅适用于测试内的两个 int 小键，不代表生产候选恒小。准入消除的是首次 miss 的完整结果分配，不能称为调用零分配。
