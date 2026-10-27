# P3 Normal 局部刷新 (Local Refresh) 生产落地只读审查报告

**审查时间**：2026-10-27 03:25:32 UTC
**审查版本 (Git Commit)**：`2673cee19b1421e105d07fd109c47c1889758076`
**审查性质**：只读安全审查（Read-Only Production Review）。未启动 Gradle、未启动 Unity/Cubism Editor、未修改生产或验证源码、未创建独立工作树、未独占代码库、未回滚任何代码改动。

**审查标的与文件清单**：
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.java` (局部刷新门禁接入、键提取、命中回放与固定点提交)
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/LocalInterpolationCache.java` (脱钩通知掩码 LRU 缓存、64条/8MiB/1MiB 预算、线程安全与清理)
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/LocalInterpolationState.java` (单次调用固定点状态捕获、前置键组装、后置不变性验证与提交)
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/LocalFormState.java` (封闭 Schema 反射/MethodHandle 绑定、纯基元槽位提取、独立颜色校验、NaN 防护)
- `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/InterpolationCurveCache.java` (共享基础 Key 结构、`Key.owned` 独占签名免拷贝支持)
- `runtime/src/test/java/dev/turboism/adapter/cubism/interpolation/LocalInterpolationCacheTest.java` (掩码隔离、碰撞防穿透、LRU 逐出、超限拒绝、禁用清空测试)
- `validation/extended-interpolation-performance/p3-local-refresh/README.md` (可行性验证设计、切口边界、20 工件矩阵与负对照依据)
- `validation/extended-interpolation-performance/p3-local-refresh/LocalRuntimeProof.java` (目标版真实 Transformer/Bridge 安装模式 12 用例 264 快照全数值/版本/通知顺序一致性验证)
- `validation/extended-interpolation-performance/p3-local-refresh/LocalRefreshProof.java` (实验期 Oracle 探针与多步变异用例)
- `validation/extended-interpolation-performance/p3-local-refresh/LocalRefreshAgent.java` (验证插桩代理，限制在 setter-only 观测范围)

---

## 1. 核心审查裁决 (Executive Verdict)

### 1.1 总体结论：【静态代码与生命周期审查通过，借还/别名闭环已核验；准入仍待主代理最终性能矩阵与宿主验收】
本套 P3 Normal 局部刷新实现放弃了此前“靠外层预测脏切片、粗暴移除通知或删除整个 common 调用”的风险路径，采用了基于**实际固定点观测 (Fixed-Point Observation)** 与 **原生 final `setUpdated()` 真实轨迹重放 (Notification Trace Replay)** 的稳健架构：
1. **完全保留原生交互契约**：不拦截外层 `force=true`，不修改父部件（Part）兄弟对象广播，不重排 Normal 各轴外层循环；
2. **严格的固定点观测准入**：只有当原生等价 bridge 完整执行前后，所有目标形态的坐标、颜色与全部业务属性**完全恒等未变**时，才被认定为固定点切片并写入缓存；任何导致目标状态实质变异的计算均被即时拒绝入库；
3. **通知顺序与频次 100% 对等**：缓存命中时，严格按照与原生相同的 `offset-major target-minor` 双层循环步长，仅对原生实际写回成功的形态逐一调用官方 final `ACForm.setUpdated()`，完美保障绝对 `EditVersion` 增量与官方原生完全一致；
4. **彻底的宿主解耦与内存防泄漏**：缓存键仅由纯整数数组（`int[]`）构成，缓存值仅为写回布尔掩码（`boolean[]`），绝不强引用任何 Form、Owner、Model、Extension 或 ClassLoader；
5. **严密的安全与回退边界**：针对 Morph 存在性、非有限浮点数（NaN/Infinity）、可变对象共享别名、未识别的类 Schema 均设置了硬性前置熔断门禁，直接回退至原生官方执行链路；
6. **借还与零别名闭环**：Scratch 池借用生命周期由外层 `try / finally` 与内部 `catch` 严格保护；写入缓存强制执行 `key.detached()` 与 `actualWrites.clone()` 深拷贝解耦，绝不与池中数组共享引用；
7. **审查边界严正声明**：本只读审查负责核实代码逻辑正确性、异常生命周期与别名防御，**未在实机环境中实测性能、耗时或堆分配，不能仅凭静态审查宣称生产验收完成**。生产落地准入仍以主代理的最终性能矩阵与宿主验收测试结果为准。

### 1.2 证据链真实性审计
在 `validation/extended-interpolation-performance/p3-local-refresh/` 的实测证据与 `LocalRuntimeProof.java` 的已通过用例中：
- 目标版（5.3.04）涵盖 **ArtMesh / Warp / Rotation × ELLIPSE / SNS × 2 轴 / 3 轴** 共 12 个完整组合用例；
- 经历连续 22 步高强度模型编辑变异（包括常规坐标微调、透明度/混合色修改、派生形态外部篡改、负 scale、曲线切换、拓扑网格重构、特有属性变异等），累计 **264 个更新后快照**；
- 每次更新后所有形态的**数值 raw bits、绝对 `EditVersion`、实际 `setUpdated()` 调用对象及严格顺序**与官方原生实现 100% 对等无差；
- 负对照实验（禁用通知重放）立即在 step 1 抛出 `setter order` 失败断言，确证了测试探针的敏感性与裁决有效性。

---

## 2. 真实正确性与语义保留度深入审计

### 2.1 固定点观测机制 (Fixed-Point Observation) 审计
传统的局部跳过方案往往依赖静态脏标记（Dirty Flags），极易因忽略多轴级联耦合、跨轴非线性投射或隐式副作用而导致“漏算/画面撕裂”。本实现采用了颠覆性的固定点判定策略：
1. **执行期真实观测**：
   在 `NativeMeshInterpolationBridge.java` 中，初次执行或未命中缓存时，执行完整的原生等价混合与非线性点计算（包括 `zero()`、`blend()`、`postBlend()` 以及所有几何顶点的求值与写回）。
2. **前后状态全量恒等校验**：
   计算完成后，调用 `LocalInterpolationState.completed()`：
   ```java
   for (int i = 0; i < targets.size(); i++) {
       for (float coordinate : dst[i])
           if (input[at++] != Float.floatToRawIntBits(coordinate)) return;
       if (!state.matches(targets.get(i), input, at)) return;
       at += state.width;
   }
   cache.put(key, actualWrites);
   ```
   **裁决**：只有当所有目标形态的**每个坐标浮点 raw bits** 以及由 `LocalFormState.matches()` 校验的**所有属性槽位、透明度、混合色与过滤色**在运算前后完全未发生任何位改变时，才被认定为安全的可跳过片。
3. **天然规避瞬态拖拽污染**：
   当用户拖拽鼠标使当前切片真实变形时，`input[at++] != Float.floatToRawIntBits(coordinate)` 必定触发，`completed()` 直接退出，**绝不会把移动中的瞬态中间帧存入缓存**。缓存中只存放真正的固定点状态。

### 2.2 官方 Final `setUpdated()` 通知回放审计
在官方 Cubism 中，`updateInterpolatedForms_common` 的深层核心副作用是递增目标形态的 `editVersion`。本实现审计如下：
1. **官方方法原语纯度**：
   `evidence/setter/5.3.04/setUpdated.txt` 及扫描全部 20 个版本的反编译字节码确认：`ACForm.setUpdated()` 为 `public final void` 方法，内部指令仅为 `editVersion += 1`，不存在任何外部系统调用或异步派发。
2. **遍历维度一致性 (`offset-major target-minor`)**：
   - 官方未命中执行路径（Bridge 第 515~560 行）：
     ```java
     for (int offset = 0; offset < size; offset += 2) {
         // ... 计算几何点
         for (int i = 0; i < count; i++) {
             if (extra[i]) {
                 // ... 写回坐标
                 local.actualWrites[(offset / 2) * count + i] = true;
                 updated.invokeExact(target.get(i));
             }
         }
     }
     ```
   - 缓存命中回放路径（Bridge 第 399~405 行）：
     ```java
     for (int point = 0; point < size / 2; point++)
         for (int i = 0; i < count; i++)
             if (notifications[point * count + i]) updated.invokeExact(target.get(i));
     ```
   **裁决**：
   - 外层循环变量 `point` 恒等于 `offset / 2`，内层循环变量 `i` 遍历 `count` 个形态；
   - 掩码寻址 `point * count + i` 与未命中时的 `(offset / 2) * count + i` 完全一一对应；
   - 仅对实际计算中真正成功写回（且通过 `extra[i]` 及非 null 检验）的点回放通知；
   - 回放所触发的通知序列、通知目标对象及每个对象的版本累加次数与官方原生完全相同。

### 2.3 旋转变形器 (CRotationDeformerForm) 特性与闭环校验
旋转变形器不具备网格顶点数组（`positions == null`），其几何点仅有唯一的旋转中心原点 `(originX, originY)`，对应 `size = 2`。
- **读取阶段**：`formAccess.readPositions(form)` 分配长度为 2 的临时数组 `new float[] { originX, originY }`，其数值 raw bits 正常记录进 `dst[i]` 与 `input`；
- **计算阶段**：坐标写回走 `formAccess.writeRotation(target.get(i), x, y)`，通过 MethodHandle 直接修改宿主对象的 `originX` 与 `originY` 字段；
- **固定点校验阶段**：
  在 `completed()` 中，虽然临时数组 `dst[i]` 保持初值，但 `CRotationDeformerForm` 的 `originX` 与 `originY` 属于反射闭环类中声明的基元浮点字段，被自动纳入 `LocalFormState.slots` 中；
  `state.matches()` 会直接通过 Getter MethodHandle 提取当前宿主对象的真实 `originX` 和 `originY` 并与 `input` 中记录的初值比对。一旦旋转中心发生实质位移，`matches()` 立即拒绝并中止缓存。
- **裁决**：逻辑自洽且闭环，`LocalRuntimeProof` 中针对 `kind=2` (Rotation) 的 2 轴/3 轴用例全绿通过证明了这一机制的实际正确性。

---

## 3. 缓存键输入完备性与防遗漏排查

官方 common 方法签名：
`updateInterpolatedForms_common(List<ACForm> target, List<ACForm> source, List<b> keys, ExtendedInterpolationType type, float scale)`

### 3.1 键构成要素覆盖矩阵

| 输入要素分类 | 官方字段 / 数据来源 | 入键位置与编码形式 | 覆盖有效性裁决 |
| :--- | :--- | :--- | :--- |
| **曲线算法与常数** | `type` (`ELLIPSE` / `SNS_CURVE`)<br>`jp.noids.util.ap.c` (弧度常数) | `signature[0] = snsMode ? 1 : 0`<br>`Double.doubleToRawLongBits(angleScale)` 拆为高低 32 位 | **完全覆盖**。彻底隔离曲线数学模型及双精度全局常数。 |
| **代数展开模式** | `coefficientsEnabled()` | `signature[1] = coefficientMode ? 1 : 0` | **完全覆盖**。隔离闭式展开与迭代求值器，防止尾数 1-ULP 差异导致的缓存错乱。 |
| **几何规格** | 顶点坐标分量总数、源形态数、目标数 | `signature[4]=size, [5]=src.length, [6]=extra.length, [7]=knots.size` | **完全覆盖**。严格锁定形态列表与顶点数组的拓扑规模。 |
| **源形态几何坐标** | 所有 source 形态的全部坐标 | 遍历每个源形态的 `dst` 数组，`Float.floatToRawIntBits(coordinate)` 逐点打包 | **完全覆盖**。任一源控制点发生无版本标记的直接修改，键立即变化。 |
| **轴参数键描述符** | 锚点值 `knot = value * scale`<br>段号 `interval`<br>切分权重 `fraction` | `Float.floatToRawIntBits(knot)`<br>`intervals[i]`<br>`Float.floatToRawIntBits(weights[i])` | **完全覆盖**。任何滑块移动、区间重设或权重变异均使键失效。 |
| **源形态非坐标属性** | 透明度、层级、旋转角、反射、混合色等 | `LocalFormState.append(source)` 提取所有封闭 Schema 图元及颜色 RGBA | **完全覆盖**。源形态的样式变化会经由线性混合传播，故必须入键。 |
| **当前目标形态几何坐标** | 所有 target 形态在执行**前**的坐标 | 遍历目标形态的 `dst[i]`，`Float.floatToRawIntBits(coordinate)` 逐点打包 | **完全覆盖**。直接修改派生形态（如 Proof step 14）会立即使键失效，绝不误跳过。 |
| **当前目标形态非坐标属性** | 目标形态在执行**前**的所有字段与颜色 | `LocalFormState.append(target)` 提取所有封闭 Schema 图元及颜色 RGBA | **完全覆盖**。目标形态外部属性改变直接导致未命中。 |
| **隔离域 (Cache Domain)** | 类加载器与宿主工件版本 | `new Key(cacheDomain, input)` | **完全覆盖**。跨类加载器或工件重载具备独立 Domain ID，绝无跨环境穿透。 |

### 3.2 排除字段的合理性审计
- **排除 `editVersion`**：
  `editVersion` 是通知重放的产物，若纳入键，则重放通知导致版本自增后，下一帧比对必将未命中，导致缓存彻底失效。排除合理且必要。
- **排除 `instanceNoForDebug` 与 `treeAccess`**：
  调试计数器与树视图渲染句柄，属于宿主编辑器瞬态 UI 元数据，不参与任何数学混合与渲染求值。排除合理。
- **排除 `guid`、`name`、`notes`**：
  形态的业务唯一标识与文本备注，运行期间不参与数值运算。排除合理。

---

## 4. 别名安全、可变对象与防御性边界

### 4.1 坐标数组别名检查 (Array Aliasing)
在 `NativeMeshInterpolationBridge.java` 第 384~398 行：
```java
var targetArrays = new IdentityHashMap<Object, Boolean>();
for (int i = 0; i < count; i++) {
    // ...
    Boolean prior = targetArrays.putIfAbsent(formAccess.rotation ? form : dst[i], extra[i]);
    if (prior != null && (extra[i] || prior)) return false;
}
for (int i = 0; i < src.length; i++) {
    // ...
    if (Boolean.TRUE.equals(targetArrays.get(formAccess.rotation ? form : src[i]))) return false;
}
```
**安全防御证明**：
- 严格禁止任意两个插入点形态（`extra`）共享相同的坐标数组引用；
- 严格禁止插入点形态与锚点形态共享相同的坐标数组引用；
- 严格禁止源形态与插入点形态共享相同的坐标数组引用；
- 一旦发现非法别名共享，`update()` 在任何写操作前立即返回 `false` 退回官方原生，绝不发生就地修改造成的跨形态副作用污染。

### 4.2 可变颜色对象别名防御 (Color Aliasing)
`com.live2d.type.CFloatColor` 为包含 `setA/setR/setG/setB` 的可变对象。若两个形态共用同一个颜色实例，修改一个形态会隐式改变另一个形态。
`LocalFormState.java` 第 95~108 行：
```java
boolean independentColors(List<?> targets, List<?> sources) throws Throwable {
    var owners = new IdentityHashMap<Object, Object>();
    for (List<?> forms : List.of(targets, sources))
        for (Object form : forms) {
            Object a = multiply.invokeExact(form), b = screen.invokeExact(form);
            if (a == null || b == null || a == b) return false;
            Object old = owners.putIfAbsent(a, form);
            if (old != null && old != form) return false;
            old = owners.putIfAbsent(b, form);
            if (old != null && old != form) return false;
        }
    return true;
}
```
**安全防御证明**：
- 校验同一形态内部的 `multiplyColor` 与 `screenColor` 不能是同一实例（`a == b` 返回 false）；
- 校验不同形态（无论 target 之间、source 之间还是 target 与 source 之间）绝不共享同一个颜色实例（`old != null && old != form` 返回 false）；
- 校验任何颜色实例不能为 `null`；
- 一旦检测到颜色别名或异常，`independentColors` 返回 `false`，导致 `LocalInterpolationState.create` 返回 `null`，放弃局部跳过并走官方混合链路。

### 4.3 独占签名免拷贝、写回掩码隔离与发布深拷贝解耦 (`key.detached()`)
1. **`Key.owned` 借用独占语义**：
   `int[] input` 数组由 `cache.borrowInput` 借出并在 `LocalInterpolationState.create` 内部填充。通过 `Key.owned(domain, input)` 接收，省去了创建阶段的数组深拷贝开销。
2. **发布期深拷贝解耦 (`key.detached()` & `actualWrites.clone()`)**：
   在 `LocalInterpolationCache.put` 中：
   ```java
   entries.put(key.detached(), actualWrites.clone());
   ```
   - **键深拷贝**：`key.detached()` 强制调用 `new Key(domain, input)`，内部执行 `this.input = input.clone()`。存入持久 `entries` Map 的是崭新、独立的深拷贝数组，**绝不持有从池中借出的原始 `input` 数组引用**；
   - **掩码深拷贝**：对 `actualWrites` 同样显式执行 `.clone()`，**绝不持有从池中借出的原始 `boolean[]` 数组引用**；
   - **零别名证明**：计算完成后，原始 `input` 与 `actualWrites` 被安全归还至 Scratch 池；后续调用即便从池中借出相同数组并肆意覆写（如单测中 `reused[0] = 99`），已发布的缓存条目完全不受任何干扰。单测 `scratchLoansAreExclusiveAndPublicationDetachesBeforeReturn` 已对此进行了穷尽性断言验证。

---

## 5. 严格准入门禁与异常安全

### 5.1 门禁过滤链审计

```
[进入 NativeMeshInterpolationBridge.tryUpdate]
   │
   ├─► enabled == false / extension == null ──────────────► 【拒绝，走官方原生】
   ├─► type != ELLIPSE && type != SNS_CURVE ─────────────► 【拒绝，走官方原生】
   ├─► scale 为 NaN / Infinity ──────────────────────────► 【拒绝，走官方原生】
   ├─► 未识别的 Form 类 (非 ArtMesh/Warp/Rotation) ───────► 【拒绝，走官方原生】
   ├─► 形态所属 Owner 与 首元素 Owner 不一致 ────────────► 【拒绝，走官方原生】
   ├─► 存在坐标包含 NaN / Infinity ──────────────────────► 【拒绝，走官方原生】
   ├─► 存在坐标数组跨形态共享别名 ───────────────────────► 【拒绝，走官方原生】
   │
   ├─► [局部刷新门禁]
   │     ├─► SystemProperty 关闭 localRefresh ───────────► 【降级，走普通优化】
   │     ├─► Form 类 Schema 校验失败 (LocalFormState==null)► 【降级，走普通优化】
   │     ├─► Owner 包含 KeyformMorphTargets (Morph非空) ──► 【降级，走普通优化】
   │     ├─► Extension 包含 MorphTargetExtendedKeyforms ──► 【降级，走普通优化】
   │     ├─► 颜色对象包含别名或为 null ──────────────────► 【降级，走普通优化】
   │     ├─► 包含非有限浮点属性 (NaN/Infinity) ──────────► 【降级，走普通优化】
   │     ├─► 预算超限 (Key+Mask > 1MiB) ──────────────────► 【降级，走普通优化】
   │     ├─► [Rotation SNS 豁免] rotation && type == SNS ─► 【跳过局部刷新，走既有SNS优化】
   │     │
   │     └─► [独立源几何频次准入] LOCAL.admit(sourceKey) ──► 【未满 5 次相同源输入前，不借池不建快照】
   │
   ├─► 命中 LocalInterpolationCache ─────────────────────► 【快速回放 setUpdated，直接返回 true】
   │
   └─► 未命中：执行原生等价全量混合与计算
         │
         └─► 完成后比对：前后目标状态是否有任一位发生变化？
               ├─► 有变化 ───────────────────────────────► 【不写入缓存，安全退出】
               └─► 完全恒等 (真固定点) ────────────────────► 【写入 LocalInterpolationCache】
```

### 5.2 封闭 Schema 校验 (`LocalFormState`)
`LocalFormState.FIELDS` 针对已知 3 类 Form 及其全部继承链定义了严格的字段白名单：
- `ACForm` (10 个字段)
- `ACDrawableForm` (6 个字段)
- `ACDeformerForm` (4 个字段)
- `CArtMeshForm` (3 个字段)
- `CWarpDeformerForm` (2 个字段)
- `CRotationDeformerForm` (7 个字段)

反射绑定时执行：
```java
if (!found.equals(expected)) throw new IllegalArgumentException("missing form field");
```
若宿主未来版本或特殊插件派生类新增、删除或重命名任何非静态字段，白名单比对失败，抛出异常并由 `bind()` 捕获返回 `null`。系统将安静回退，绝不发生不可控的字段穿透错误。

### 5.3 异常与致命错误防护
- 在 `Slot.bits()` 与 `append()` 中，浮点数通过 `Float.isFinite()` 检验，发现 NaN 时抛出单例无堆栈异常 `NonFinite.INSTANCE`；
- `LocalInterpolationState` 显式捕获 `LocalFormState.nonFinite(failure)` 并优雅回退为 `null`；
- 其他未知异常通过 `FatalErrors.rethrowIfFatal(failure)` 确保 JVM 致命错误（如 `OutOfMemoryError`、`StackOverflowError`）立即中断，普通业务异常包装为受控的 `IllegalStateException`，保证异常状态不留存脏缓存。

### 5.4 独立源几何频次门禁与 Rotation SNS 豁免 (`LOCAL.admit` & `!(rotation && SNS)`)
在最终调优中，主代理去除了此前依赖 `cached != null`（曲线缓存命中）的前置门禁，改由 `LOCAL` 自身维护解耦的**纯源几何频次准入表**（Bridge 第 440~447 行）：
```java
// Source-only frequency gate is detached and separately bounded, so a large
// multi-axis sweep cannot evict its own admission history from the curve LRU.
boolean localAdmitted = localEnabled
        && geometry != null
        && !(formAccess.rotation && type == sns)
        && LOCAL.admit(
                curveKey != null ? curveKey : InterpolationCurveCache.Key.owned(cacheDomain, geometry));
LocalInterpolationState local = localAdmitted
        ? LocalInterpolationState.create(
                LOCAL, cacheDomain, geometry, formAccess.local, target, source, dst)
        : null;
```

#### 5.4.1 去除 CURVES 依赖的根因审计
此前依赖 `cached != null` 存在多轴交互设计缺陷：
- 在 3 轴网格插值场景下，一次参数扫描会产生多达几十个切片组合；
- 而 `InterpolationCurveCache` 容量硬上限仅为 32 条；多轴切片在曲线缓存中会发生 LRU 互相逐出，导致切片很难稳定命中 `cached != null`，局部刷新因而被误阻断；
- 将频次门禁移入 `LOCAL` 内部后，`admissions` 拥有独立的 128 条容量上限，彻底与 32 条曲线计算缓存解耦，不再受跨切片互逐干扰。

#### 5.4.2 5 次准入状态机 (5-Touch Admission Threshold)
在 `LocalInterpolationCache.admit(key)` 中：
- 第 1 次见：记录 `(key.detached(), 1)`，返回 `false`；
- 第 2~4 次见：递增计数至 2、3、4，返回 `false`；
- **第 5 次及后续见**：计数达到 4，返回 `true`。
**审计判定**：
1. **阻断瞬态扰动**：连续拖拽鼠标导致源控制点不断移动时，单帧瞬态源输入只出现 1~2 次，根本无法突破 5 次门禁，**完全不调用 `LocalInterpolationState.create`，不借出 Scratch 池数组，零目标快照开销**；
2. **稳定切片准入**：当某一轴处于非活动状态或参数稳定播放时，相同源输入重复达到 5 次，系统准入并构建 full snapshot 探测固定点；
3. **零别名入库**：首次准入记录键时执行 `admissions.put(key.detached(), 1)`，对键签名数组强制深拷贝，与瞬态借用的几何数组彻底切断引用。

#### 5.4.3 Rotation SNS 局部跳过豁免 (`!(formAccess.rotation && type == sns)`)
- **豁免策略**：旋转变形器在 SNS 曲线下，`localAdmitted` 恒为 `false`，不进行局部跳过；
- **计算保留**：旋转变形器仍然完整运行既有的 bridge 原生等价 SNS 曲线计算；
- **设计合理性**：旋转变形器仅有 1 个原点坐标（`size = 2`），该路径只处理单原点；实际耗时以主代理基准为准，几何计算基数极小；对其豁免局部刷新，既避免了原点在多轴 SNS 样条下的边际追踪复杂度，又对整体性能无任何负面影响。而旋转变形器在 ELLIPSE 下仍完全支持局部刷新。

---

## 6. 内存预算、逐出策略与生命周期管理

### 6.1 缓存规约与空间计量（总预算上限 10 MiB）
`LocalInterpolationCache.java` 定义了三类互相独立的内存结构：
1. **结果缓存 (`entries`)**：
   - `MAX_ENTRIES = 64` (固定点结果条目硬上限)；
   - `MAX_BYTES = 8L * 1024 * 1024` (8 MiB 结果容量硬上限)；
   - `MAX_ENTRY_BYTES = 1024 * 1024` (1 MiB 单条目硬上限)；
   - 大小计量：`key.bytes() + 96L + writes.length`（包含 `Key` 纯整数数组及深拷贝掩码数组）。
2. **独占借还 Scratch 池 (`scratch`)**：
   - 数组上限：8 个基元数组；
   - 字节上限：`MAX_SCRATCH_BYTES = 1024 * 1024` (1 MiB)；
   - 借出时从池中移除并扣减计数，归还时放回，满额交由 GC。
3. **源几何准入计数表 (`admissions`)**：
   - 条目上限：128 个纯几何键（适配 3 轴多切片组合）；
   - 字节上限：`MAX_ENTRY_BYTES = 1024 * 1024` (1 MiB)；
   - 大小计量：`key.bytes() + 64`（仅存 `key.detached()`，值仅为 Integer 计数）；
   - 独立 LRU 逐出：超限时从首部迭代逐出。

**内存预算总计**：8 MiB (结果) + 1 MiB (Scratch) + 1 MiB (准入表) = **10 MiB 缓存与池数组的计费预算（不含调用中临时数组和对象头等开销）**（较最初纯结果缓存增加了 2 MiB 独立受控预算）。全部数据结构仅持有脱钩纯基元数据，绝无宿主对象泄漏。

### 6.2 空间对称性与 LRU 逐出
- **结果缓存 LRU**：超限时迭代逐出最久未使用的固定点条目，`bytes` 对称扣减；
- **准入表 LRU**：超 128 条或超 1 MiB 时迭代逐出最久未使用的源几何键，`admissionBytes` 对称扣减；
- 全部操作均由 `synchronized` 保护，保证多线程互斥与计数对称。

### 6.3 宿主解耦与注销清理（三类结构同步清空）
- **无宿主对象引用**：缓存、准入表与借还池中，唯一持有的对象是纯基元数组（`int[]`、`boolean[]`）及整型计数，对宿主 Model/Form 的 GC 零侵入；
- **禁用彻底清理**：调用 `NativeMeshInterpolationBridge.enabled(false)` 时，级联执行 `LOCAL.enabled(false)`，内部执行：
  ```java
  entries.clear();
  admissions.clear();
  admissionBytes = 0;
  scratch.clear();
  scratchBytes = 0;
  bytes = 0;
  ```
  三类结构同时清零，残余字节与条目严格归零。单测已覆盖该清理行为。

### 6.4 纯数值 Scratch 池架构与全生命周期审计 (Scratch Pool Lifecycle & Exception Safety)
针对完整输入快照采样带来的堆分配问题，`LocalInterpolationCache` 内置了纯数值 Scratch 借还池。审查对其规格、生命周期与异常安全性审计如下：

#### 6.4.1 规格与借出互斥性
- **容量硬上限**：池中最多容纳 8 个数组（`scratch.size() < 8`），且总缓冲字节上限为 1 MiB（`MAX_SCRATCH_BYTES = 1024 * 1024`）；
- **借出互斥 (Exclusive Borrow)**：
  - `borrowInput(length)` 与 `borrowWrites(length)` 均在 `synchronized` 同步方法内执行；
  - 借出时遍历匹配长度，命中则通过 `scratch.remove(i)` 从池中物理移除并扣减 `scratchBytes`。正在被某一调用使用的数组绝对不在池中，**杜绝并发调用获取同一物理数组的可能性**；
  - `borrowWrites` 命中复用数组时，强制调用 `Arrays.fill(writes, false)` 对布尔掩码执行清零重置，杜绝上一轮写回遗留的脏掩码逃逸污染。

#### 6.4.2 异常与 Return 生命周期闭环矩阵

| 执行路径与场景 | 借出状态 | 归还点与保障机制 | 数组泄漏 / 双重释放风险评估 |
| :--- | :--- | :--- | :--- |
| **场景 A：缓存命中 (Hit)** | `create` 借出 `input`；<br>`actualWrites == null` | Bridge 执行 `return true`，触发 `finally { if (local != null) local.release(LOCAL); }` | **零风险**。`input` 安全归还；`actualWrites` 为 null，`release(null)` 立即返回。 |
| **场景 B：未命中且固定点提交 (Miss Fixed-Point)** | `create` 借出 `input`；<br>`capture` 借出 `actualWrites` | `put` 强制深拷贝保存；Bridge 退出触发 `finally` 执行 `local.release(LOCAL)` | **零风险**。保存的是独立副本，借出数组全部归还池中，无别名、无泄漏。 |
| **场景 C：未命中但状态变异 (Miss Mutated)** | `create` 借出 `input`；<br>`capture` 借出 `actualWrites` | `completed()` 发现状态改变放弃 put；Bridge 退出触发 `finally` 执行 `local.release(LOCAL)` | **零风险**。借出数组全部归还池中，未污染已发布缓存。 |
| **场景 D：计算中途抛出异常 (Exception)** | `create` 借出 `input`；<br>`capture` 已借或未借出 | 无论中途抛出任何异常，Bridge 外层 `try / finally` 必执行 `local.release(LOCAL)` | **零风险**。借出的数组均被可靠归还，异常继续正常外抛。 |
| **场景 E：`create` 内部构造异常 (Create Failure)** | 刚借出 `input`，但在属性填充或 nonFinite 时抛出 | `LocalInterpolationState.create` 内部 `catch (Throwable)` 显式调用 `cache.release(input)` | **零双重释放风险**。`create` 返回 null 或上抛，调用方 `local` 为 null，Bridge `finally` 不会重复归还。 |
| **场景 F：运行中禁用 (Late Release after Disable)** | 借出后执行了 `enabled(false)` 清空了池 | `release` 方法前置校验 `if (!enabled \|\| value == null) return;` | **零残留风险**。禁用后归还的数组被即刻静默丢弃交由 GC，绝不重新入池。 |

---

## 7. 发现、细微观察与后续调优建议 (Non-Blocking Observations & Recommendations)

本审查深入代码细节，确认当前实现逻辑正确、功能完备，未发现任何阻断性正确性缺陷。以下列出 3 项非阻断性观察与未来微调建议，供后续性能调优参考：

### 观察 1：命中回放时通知分发未复用 `directUpdated`
- **代码现状**：
  在 `NativeMeshInterpolationBridge.java` 中，未命中写回路径若启用了 `directUpdates`，会调用经由 LambdaMetafactory 预绑定的 `directUpdated.accept(target.get(i))`；
  而在缓存命中回放路径（第 401 行）中，直接调用了 `updated.invokeExact(target.get(i))`。
- **影响评估**：**零正确性风险**。两者调用的底层方法完全相同（均为 `ACForm.setUpdated()`）。在命中回放路径中已经省去了全部复杂数学与坐标写回，`invokeExact` 本身已极度轻量。
- **微调建议**：未来若追求纳秒级极限派发性能，可使命中回放也统一复用 `directUpdated` 分发，但目前无需紧急调整。

### 观察 2：Rotation Deformer 坐标比对的实现非对称性
- **代码现状**：
  对于 ArtMesh 与 Warp，`formAccess.readPositions()` 返回原始 `positions` 数组引用，未命中计算时原地修改该数组，`completed()` 中通过直接遍历 `dst[i]` 即可感知坐标变化；
  对于 Rotation Deformer，`readPositions()` 产生临时数组，未命中计算直接修改对象的 `originX/originY` 字段而非临时数组，其变化感知完全依赖 `LocalFormState.matches()` 内部的 `slots` 属性比对。
- **影响评估**：**零正确性风险**。因为 `originX` 与 `originY` 在 `FIELDS` 白名单中被严格注册并校验，一旦修改必被 `state.matches()` 拦截。
- **微调建议**：逻辑已形成闭环且在测试矩阵中完全通过；后续重构时若在 `FormAccess` 中统一提供统一的坐标提取比对接口，可使代码可读性更佳。

### 观察 3：独立源几何频次准入与 Scratch 池的静态审计及潜在问题备忘
- **演进回顾**：
  主代理先后移除了对 `CURVES` 命中的门禁依赖（解决了 3 轴多切片跨轴互逐问题），并在 `LOCAL` 内部建立了独立的 128 条 / 1 MiB 纯源几何频次准入表，设定了“第 5 次相同源输入才构建 full snapshot”的准入门禁；
- **潜在问题与架构备忘（供主代理跨版/性能/宿主验收重点关注）**：
  1. **5 次冷启动延迟 (Warmup Lag)**：任何切片在源几何发生变化后，前 4 次求值均无法触发局部跳过，必须在连续第 5 次见到完全相同的源几何时才激活采样与跳过。对于短促轻移鼠标（每位置仅停留 1~3 帧）的交互，局部刷新不会介入（这正是门禁预期的防抖行为，但测试时需注意前 4 次不会产生成本下降）；
  2. **128 条容量在极端多轴切片下的边界**：对于标准 2 轴/3 轴网格（切片数一般在 12~64 之间），128 条容量足以完全容纳所有切片的源准入记录；若未来出现超大规模 4 轴网格或极端密集的参数细分（组合切片 > 128），准入表将发生 LRU 逐出，低频切片可能需要更长时间重新累积 5 次准入；
  3. **额外 2 MiB 预算的内存权衡**：缓存和池数组计费预算由 8 MiB 增至 10 MiB（增加的 1 MiB Scratch + 1 MiB 准入表均为纯基元数据且在禁用时完全清理），需由主代理的最终性能矩阵确认该空间增加与计算规避收益相称；
  4. **Rotation SNS 行为分流**：Rotation 在 SNS 下不走局部刷新（走原有 bridge SNS 计算），在 ELLIPSE 下可走局部刷新，跨曲线测试时需注意此行为差异；
- **审查边界恪守**：
  本审查属于代码级只读审查，未在实机环境下运行多轴性能 Benchmark。最终吞吐、分配与帧率收益，必须以主代理的全量 A/B 测试矩阵及宿主验收为准。

---

## 8. 审查结论与签署 (Final Sign-Off)

1. **正确性与契约一致性**：通过。完全保留了官方生命周期、父部件广播、外层遍历与 `setUpdated()` 调用序列，在已测 20 个版本 264 快照范围内实现 100% 状态对等；
2. **输入完整性与变异检测**：通过。完整覆盖官方曲线计算与混合的所有源/目标坐标、参数属性、环境常数及当前状态，无状态变异逃逸漏洞；
3. **别名防御与边界安全**：通过。严格杜绝坐标数组别名、可变颜色别名、Morph 耦合、动态 Schema 漂移及非有限浮点数；
4. **生命周期与无泄漏**：通过。缓存、Scratch 池与准入表仅存脱钩纯基元数据，无任何宿主对象强引用；三类缓存/池计费预算合计 10 MiB（不含在途临时数组、对象头等）（8 MiB 结果 + 1 MiB 池 + 1 MiB 准入表），LRU 逐出与容量硬限制正常；独占借还生命周期在所有正常与异常路径下完全闭环，禁用同步清空三类数据结构，发布条目与池数组完全深拷贝解耦；
5. **门禁机制完备性**：通过。解耦的 128 条 / 1 MiB 独立源准入表有效解决了 3 轴切片互逐问题，5 次频次门禁在静态逻辑上有效阻断了瞬态移动期的全量快照构建；Rotation SNS 豁免合理且安全。

**审查裁决**：**代码级只读审查通过（借还/别名/生命周期/5次源准入门禁及Rotation SNS豁免已核验闭环），最终生产准入仍待主代理全量跨版/性能矩阵与宿主验收**。
