# Cubism 5.0.00 ~ 5.3.04 (全20版) SNS 官方计算器跨顶点复用审计报告

## 1. 核心裁决与架构边界 (Executive Verdict & Architectural Boundaries)

### 1.1 核心裁决 (Verdict)
- **单次 common 调用内（单个 `tryUpdate` 执行期）跨几何顶点复用**：**【静态约束下支持 / 所测样本 rawbits 一致】**。
  在官方网格插值调用合同中，同一次插值操作固定为开放曲线（`isOpen = true`）且无拐角（`isCorners = null`）。在此前置不变量下，官方类在每次 `initialize` 时会清空内部控制点集合（`curvePoints.clear()`）并重构内部样条段列表（`a.clear()` + `new f()`）。在单次 common 调用的局部生命周期内，在所测全部交替顶点、动态点数伸缩及退化几何样本集中，其计算结果与每顶点全新 `new calculator` 相比，原始浮点位（Raw Float Bits）100% 保持一致，未见发散。
- **通用共享缓存（General Shared Cache）跨调用复用**：**【不可实施（UNSAFE）】**。
  若计算器被放置在允许多种不同曲线类型（如闭合曲线 `isOpen = false` 或包含拐角 `isCorner = true`）共享的通用缓存中，官方核心类 `com.live2d.graphics.curve.g` (`SNSCurveWithCorner`) 内部字段 `b` (`firstCornerIndex`) 与 `c` (`isPureClosed`) 会被写入非初始值，但在随后的 `isOpen = true` 初始化路径中**未被 reset**！这种“闭->开污染”会导致后续开放曲线插值发生非预期的段索引偏移（`n - this.b`），在实测中产生严重位发散甚至数组越界。
- **纯开放专用工作区（Dedicated Open-Only Workspace）跨调用缓存**：**【静态分析与有限样本实测支持，但生产环境不建议采用】**。
  经探针 Suite 6 实测证明：如果一个计算器实例被**严格限制**为只处理 `isOpen = true` 且 `isCorners = null` 的专用工作区，在所测跨调用连续复用样本集中，其内部状态未见历史累积残留，所测样本 rawbits 亦保持一致。**然而，有限样本集测试通过不等于全输入空间证明；生产代码仍应严格限定在单次 common 调用内，不跨调用缓存**。原因如下：
  1. **收益与风险严重不对称**：复用计算器仅能节省最外层 5 个轻量包装对象（见 1.3 节），跨调用缓存无法带来实质性能飞跃；
  2. **防御性设计原则**：一旦未来有其他功能分支无意中向该缓存传入了非 open 参数，就会引发灾难性的无声数据腐化；单次 common 调用内按需分配、退出即丢弃是最稳健的工程边界。

### 1.2 `evaluate` 返回对象生命周期与使用契约
审计确认官方类 `h.a(int n, float f, GVector2 gVector2)` 的实现细节：
```java
// 反编译自 com.live2d.cubism.doc.model.interpolator.extendedInterpolation.h
public GVector2 a(int n, float f, @NotNull GVector2 gVector2) {
    GVector2 gVector23 = this.a.a(n, f);
    if (gVector23 != null) {
        gVector2.setX(gVector23.getX());
        gVector2.setY(gVector23.getY());
        return gVector2; // <-- 严格返回传入的 gVector2 实例本身！
    } else {
        return null;     // <-- 计算失败或超出有效范围时返回 null
    }
}
```
- **引用同一性（Reference Identity）**：当求值成功时，返回值 `result` 严格等于入参 `output`（`result == output` 恒成立，探针 Suite 4 断言 100% 通过）。
- **返回值检查的必要性**：生产代码 `NativeMeshInterpolationBridge.java` 中的实现：
  ```java
  Object result = evaluate.invokeExact(curve, intervals[i], weights[i], output);
  if (result != null) {
      dst[i][offset] = dst[i][offset] * 0f + (float) vectorX.invokeExact(result) * 1f;
      // ...
      updated.invokeExact(target.get(i));
  }
  ```
  **极其严谨且正确**！若官方计算器在异常退化或参数超出范围时返回 `null`，调用方若忽略返回值直接读取 `output`，就会读取到上一个顶点的残留脏数据。代码显式消费 `result` 并在非空时更新目标网格，完全契合官方契约。
- **输出向量复用**：由于官方方法在成功时无条件通过 `setX` / `setY` 覆盖入参向量的全部字段，预先分配单个 `output` 并在各顶点间复用不会引入任何脏状态泄漏（探针 Suite 4 注入 `NaN` / `Infinity` 预填验证 100% 覆盖通过）。

### 1.3 分配与时间收益裁决
- **节省的部分**：
  在单个 common 调用的循环外只 `new` 一次计算器，可节省 `size / 2` 次 `calculator.invokeExact(type)` 动态分派开销，以及外层包装对象和部分集合容量分配。这里未用对象布局工具测定每个对象的精确大小，不能把估算布局当作实测。主代理固定 256 顶点的桥测量中，该路径的线程分配从上一实现 2,343,328 降至 2,271,928 bytes/次，减少 71,400 bytes；这不是主要样条分配的消除。
- **无法节省的核心 GC 瓶颈**：
  官方实现中，在每次 `initialize` 时：
  1. `h.a()` 依然会为每个控制点调用 `new CCurvePt`；
  2. `g.a()` 依然会调用 `new f()` (样条核心类)；
  3. `f.e()` 依然会为每个区间段调用 `new a()` (`GetPositionOnCubicFast`)，并在内部创建 `GVector2[4]` 与 `float[4][2]` 样条矩阵。
  因此，复用 `h` 只能减少最外层包装分配，无法根除样条拟合的主体 GC 压力。要彻底解决性能瓶颈，后续需推导闭式直接权重算法（类似现有的 `EllipseWeights`）。

---

## 2. 静态状态与闭包拓扑审计 (Static Closure Audit)

### 2.1 依赖拓扑与对象树

```
h (com.live2d.cubism.doc.model.interpolator.extendedInterpolation.h)
 └── a: CPointCurve (com.live2d.graphics.curve.CPointCurve)
      ├── curvePoints: CArrayList<c>
      ├── isOpen: boolean
      └── snsCurve: g (com.live2d.graphics.curve.g / SNSCurveWithCorner)
           ├── a: e<f> (MutableCircularList<SNSCurve>)
           ├── b: int (firstCornerIndex)  <-- [CRITICAL RISK FIELD]
           ├── c: boolean (isPureClosed)  <-- [CRITICAL RISK FIELD]
           ├── d: boolean (isOpen)
           └── e: int (size)
```

### 2.2 风险字段深入剖析：`g.b` 与 `g.c`

`com.live2d.graphics.curve.g` 中的初始化方法反编译源码：
```java
public final void a(@NotNull List<? extends c> list, boolean bl) {
    Intrinsics.checkNotNullParameter(list, "");
    this.a.clear(); // 清空当前样条段
    this.e = list.size();
    this.d = bl;
    if (bl) { // 当 isOpen == true
        this.a.add(new f()); // 重新创建全新样条实例
        ((f)CollectionsKt.last((List)this.a)).a(true);
        for (int i = 0; i != list.size(); ++i) {
            ((f)CollectionsKt.last((List)this.a)).a(new GVector2(list.get(i).a().getX(), list.get(i).a().getY()));
            if (list.size() <= 2 || i >= list.size() - 1 || !list.get(i).b()) continue;
            ((f)CollectionsKt.last((List)this.a)).e();
            this.a.add(new f());
            ((f)CollectionsKt.last((List)this.a)).a(true);
            ((f)CollectionsKt.last((List)this.a)).a(new GVector2(list.get(i).a().getX(), list.get(i).a().getY()));
        }
        ((f)CollectionsKt.last((List)this.a)).e();
        return; // <-- 漏洞根源：直接 return！未重置 this.b = 0，未重置 this.c = false！
    }
    this.c = true;
    int n = list.size();
    for (int i = 0; i < n; ++i) {
        if (!list.get(i).b()) continue;
        this.b = i;       // <-- 闭合曲线上被修改为第一个拐角索引！
        this.c = false;
        break;
    }
    // ...
}
```

在求值时（`g.a(int n, float f2)`）：
```java
public final GVector2 a(int n, float f2) {
    int n2 = this.a(n - this.b); // <-- 依赖 this.b！
    if (this.c) {                // <-- 依赖 this.c！
        return ((f)CollectionsKt.last((List)this.a)).b(n2, f2);
    }
    int n3 = n2;
    int n4 = this.a.size();
    for (int i = 0; i < n4; ++i) {
        if ((n3 -= this.a.get(i).b() - 1) >= 0) continue;
        return this.a.get(i).b(n3 += this.a.get(i).b() - 1, f2);
    }
    return ((f)CollectionsKt.last((List)this.a)).b(n2, f2);
}
```

- **闭->开污染机理**：
  若对象先处理了带拐角的闭合曲线，`this.b` 变为拐角下标（如 2）。后续在处理开放曲线时，`g.a()` 执行 `int n2 = this.a(n - this.b)`，原本应当请求段 0 的求值变成了请求段 `this.a(0 - 2) = N - 2`，导致样条插值点完全错乱！
- **专用开放环境的不变性（Invariant）**：
  新实例 `b` 初始为 0，`c` 初始为 `false`。只要实例只接受 `isOpen = true` 的调用，这两个字段永远不会被修改，因此段索引始终为 `n - 0 = n`，计算在所测样本空间内保持一致。

---

## 3. 全 20 版官方依赖闭包指纹证据 (20-Version Closure Fingerprints)

本节数据直接从 `validation/extended-interpolation-performance/sns-reuse-audit/dependency-closure-fingerprints.json` 自动装载生成，无人工手抄偏差。

### 3.1 闭包稳定性全景

| 依赖类名 | 20版本状态 | 差异变体数 | 说明 |
| :--- | :--- | :---: | :--- |
| `ExtendedInterpolationType.class` | **全20版完全一致** | 1 | 枚举定义与工厂方法 `b()` |
| `ExtendedInterpolationType$a.class` | **全20版完全一致** | 1 | switch 映射表 |
| `a.class` (AEI_CurveInterpolator) | **全20版完全一致** | 1 | 抽象基类定义 |
| `h.class` (EI_SNSCurveInterpolator) | **全20版完全一致** | 1 | 计算器对外 API 主体 |
| `CPointCurve$CCurvePt.class` | **全20版完全一致** | 1 | 曲线控制点载体 |
| `CPointCurve$a.class` (NearestPt) | **全20版完全一致** | 1 | 最近点辅助载体 |
| `c.class` (ICCurvePt) | **全20版完全一致** | 1 | 控制点接口 |
| `f.class` (SNSCurve) | **全20版完全一致** | 1 | 三次样条主求解器 |
| `f$a.class` (GetPositionOnCubicFast) | **全20版完全一致** | 1 | 样条三次矩阵求值内核 |
| `f$b.class` (SplineData) | **全20版完全一致** | 1 | 样条节点速度数据结构 |
| `e.class` (MutableCircularList) | **全20版完全一致** | 1 | 可变循环列表 |
| `b.class` (CircularList) | **全20版完全一致** | 1 | 只读循环列表 |
| `g.class` (SNSCurveWithCorner) | **2 个变体** | 2 | 5.0.00~5.0.02 vs 5.0.03~5.3.04（拐角条件防御） |
| `CPointCurve.class` | **2 个变体** | 2 | 5.0.00~5.1.03 vs 5.2.00~5.3.04（内部类混淆名重命名） |
| `GVector2.class` | **2 个变体** | 2 | 5.0.00~5.1.03 vs 5.2.00~5.3.04（基础向量类） |

### 3.2 变体类全量 64 位 SHA-256 哈希清单 (自动提取)

- **`com/live2d/graphics/curve/g.class`**:
  - **Variant A** (5.0.00, 5.0.01, 5.0.02):
    `bf210f3fee8577f54471fb8517ff1c33f29ebd57973cef3f5d9b6bcd064905a8`
  - **Variant B** (5.0.03 ~ 5.3.04, 17个版本):
    `d381dcbed97f2075cdffecd472948c51facb3718e8dfd8c204d0858c1b2a5786`

- **`com/live2d/graphics/curve/CPointCurve.class`**:
  - **Variant A** (5.0.00 ~ 5.1.03, 11个版本):
    `eebdc5c6bceca7b82a7d2e22632ef36dfa02ca26e2c411057cce9dd689ea0d84`
  - **Variant B** (5.2.00 ~ 5.3.04, 9个版本):
    `4d26b313febc3c7cb8c89e96432540d844ec4a4283eebc09cbb8ee495f225433`

- **`com/live2d/graphics3d/type/GVector2.class`**:
  - **Variant A** (5.0.00 ~ 5.1.03, 11个版本):
    `c6fc13cdbc80d13a2a2eb236d494a0242bd76fb1c03668ba4cfbbcfd940058fe`
  - **Variant B** (5.2.00 ~ 5.3.04, 9个版本):
    `91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31`

- **不变核心类 64 位 SHA-256**:
  - `h.class`: `573886ce1d8c581b4eb814ab9745671704319b466691b8d98a562dca237cd1c0`
  - `ExtendedInterpolationType.class`: `63da6a244788d6f89e0123fdc98fcefb28c46c2e85680fb9c3dc729785e3cb62`
  - `f.class`: `44c84d1cd9200a43be1e8156d1356b5abf9a9e7548f3c5a5d994bdfb9006e675`
  - `e.class`: `2662cde89a706c3b1e3fcd264b295c9913b0a94712ea5c42e3c3273d8a38b3b2`
  - `CPointCurve$CCurvePt.class`: `2dcdd8472f27a29a05cc0ce1244fc4c3e23486293baba8f699e49618fa0dccd2`

---

## 4. 差分探针实测数据（全 20 版校准：单版 2,411 项断言，全量 48,220 项断言）

探针源码：`validation/extended-interpolation-performance/sns-reuse-audit/SnsReuseDifferentialProbe.java`。
复核脚本：`validation/extended-interpolation-performance/sns-reuse-audit/run_matrix_probe.sh`。
执行日志：`validation/extended-interpolation-performance/sns-reuse-audit/probe-20version-matrix.log`。

### 4.1 探针六大用例构成与断言计数校准
探针单版本运行总共执行 **2,411 项断言**，精确分解如下：
1. **Suite 1: 多顶点交替测试**（模拟 `NativeMeshInterpolationBridge` 顶点大循环，12 顶点 × 4 轮迭代 × 5 采样帧 × 4 间隔 = **960 项**一致性断言）。
2. **Suite 2: 动态源长度测试**（在同一复用实例上按 2 -> 6 -> 3 -> 8 -> 2 -> 4 -> 12 -> 5 -> 2 -> 9 -> 3 极差切换点数，验证列表收缩与扩展无历史残留 = **225 项**一致性断言）。
3. **Suite 3: 退化几何与极端数值测试**（全重合点、连续重合点、水平/垂直共线点、`1e6` 大坐标、`1e-5` 微位移、负坐标 = **85 项**一致性断言）。
4. **Suite 4: 输出 Vector 脏数据复用与返回值引用同一性验证**（4 类脏预填 × 3 间隔 × 5 采样点 = 60 测试步；每步包含：1项 `result == outVec` 引用同一性断言 + 1项 `result` 与 `outVec` 读取值一致性断言 + 1项 rawbits 一致性断言 = **180 项**断言）。
5. **Suite 5: 负向安全断言：通用共享缓存下的闭->开污染漏洞复现**（实测污染导致位发散，断言发散触发符合预期 = **1 项**断言）。
6. **Suite 6: 正向安全断言：纯开放专用工作区跨调用复用验证**（模拟 10 次跨调用批次，每次 8 顶点 × 3 间隔 × 4 采样点 = **960 项**一致性断言）。
- **单版本合计断言**：`960 + 225 + 85 + 180 + 1 + 960 = 2,411 项断言`（其中正向一致性断言 2,410 项，负向污染验证断言 1 项）。
- **全 20 版本总断言**：`20 × 2,411 = 48,220 项断言`。

### 4.2 20 版本全量执行矩阵

| 官方版本 | MANIFEST SHA-256 前缀 | 正向一致性断言 (Suite 1~4, 6) | 位发散数量 | Suite 4 引用同一性 | Suite 5 漏洞复现断言 | 综合断言总数 | 测试结果 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **5.0.00** | `42ee222a05478f48` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.01** | `1aa93b1d9d8da88e` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.02** | `39e73c8238acba52` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.03** | `05bc9b872527fed3` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.04** | `7298c5731525a2bd` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.05** | `1225893a466d4864` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.0.06** | `cbb9d9ecddb9be02` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.1.00** | `cdda37ed4105cf5e` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.1.01** | `ddb1f5569bc8da49` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.1.02** | `0567130093015c96` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.1.03** | `a3d916077bd7a176` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.2.00** | `25c41c81ddb3af88` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.2.01** | `c1b70ca8c571c4f4` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.2.02** | `ba1f31a1846490c3` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.2.03** | `bcc6e34f448be33d` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.3.00** | `c89dcaf17b6bfad7` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.3.01** | `694bb49c66ddcf15` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.3.02** | `988ef6a8b5fede84` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.3.03** | `bd0a23b9f21a5627` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |
| **5.3.04** | `c9bb3e9960bf4990` | 2410 项全过 | **0** | 100% IDENTICAL | CONFIRMED | 2411 项 | **PASS** |

---

## 5. 生产代码修改建议 (Production Guidance)

针对主代理后续实施改动的准则建议：
1. **作用域严格锁定在单个 `tryUpdate` 调用内**：
   在顶点大循环前创建单个局部 `curve`：
   ```java
   Object curve = (type == sns) ? calculator.invokeExact(type) : null;
   ```
   在顶点大循环内复用 `initialize.invokeExact(curve, ...)`；方法返回后自然丢弃，不跨调用缓存。
2. **不采用跨调用 / 全局共享缓存**：
   鉴于官方 `g` 类中 `b` 与 `c` 字段在闭合曲线下的未重置缺陷，严禁为了微弱的包装对象节省而构建跨调用共享池。
3. **继续保留 `if (result != null)` 消费契约**：
   保持现有 `Object result = evaluate.invokeExact(...); if (result != null) { ... }` 逻辑，既能复用 `output` 避免内存分配，又能严密防御官方潜在的 `null` 逃逸。
