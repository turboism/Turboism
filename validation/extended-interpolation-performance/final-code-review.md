# Cubism 拓展插值生产优化最终代码复核报告

- **复核结论**: **PASS**
- **复核配置**: Fast 模式，同 Project 同 Workspace，无新建工作树
- **复核性质**: 只读复核（未修改任何生产代码、未执行 Gradle 或 host 启动、无网络访问）
- **复核范围**:
  1. `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeFormLookupBridge.java`
  2. `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/StructureLookupPatch.java`
  3. `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.java`
- **参考依据**:
  - 官方目标源: `/tmp/cubism-extended-5304/src/com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension.java`
  - 官方依赖库: `/opt/dev/projects/turboism/cubism-ref/{version}/*.jar` (20个版本: 5.0.00 ~ 5.3.04)
  - 实施报告: `validation/extended-interpolation-performance/implementation.md`
  - 验证证据: `validation/extended-interpolation-performance/evidence/final-installed-matrix/summary.json`
  - SNS复用审计: `validation/extended-interpolation-performance/sns-reuse-audit/AUDIT_REPORT.md`

---

## 1. 核心热点详细复核

### 1.1 `NativeFormLookupBridge.java` (GUID 索引与作用域生命周期)

- **代码位置**: `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeFormLookupBridge.java` (L14-L149)
- **检查项与代码证据**:
  1. **65,536 上限溢出防御 (L78-L82)**:
     ```java
     if ((long) ordinary.size() + extended.size() > MAX_INDEXED_FORMS) {
         scope.declined = true;
         return UNSUPPORTED;
     }
     ```
     - 显式通过 `(long)` 转换相加，防御 32 位整型溢出。
     - 超限后标记 `scope.declined = true`，同一作用域后续调用立即返回 `UNSUPPORTED`（L58），直接回退官方线性查找，避免高密度网格产生不可控临时对象。
  2. **Ordinary First 去重与优先级语义 (L84-L92)**:
     ```java
     for (List<?> forms : List.of(ordinary, extended)) {
         for (Object form : forms) {
             if (!host.formType.isInstance(form)) return UNSUPPORTED;
             Object key = host.guid.invokeExact(form);
             if (key != null && key.getClass() != host.guidType) return UNSUPPORTED;
             index.putIfAbsent(key, form); // native first match; ordinary wins
         }
     }
     ```
     - 顺序迭代 `ordinary` 然后 `extended`；`putIfAbsent` 保留首次出现的 GUID。
     - 普通键（ordinary）优先于扩展键（extended）；同列表中首项优先。
     - 完全对齐官方 `setupFormStructure_Normal$local_findKeyform` (CExtendedInterpolationExtension.java:926-958) 中的短路匹配逻辑。
  3. **Null GUID 与类型安全 (L74, L88, L99)**:
     - 入参校验 `(guid != null && guid.getClass() != host.guidType)` 允许 `guid == null` 进入查表；
     - 键提取校验 `(key != null && key.getClass() != host.guidType)` 允许键为 null，`HashMap.putIfAbsent(null, form)` 容纳空键；
     - 若源对象中存在 null GUID，`index.get(null)` 返回首个匹配项；若无则返回 `null`，完全符合 Kotlin `Intrinsics.areEqual(form.getGuid(), cFormGuid)` 的空值等价性规范。
  4. **缓存生命周期与隔离性 (L35-L53, L107-L115)**:
     - 采用 `ThreadLocal<Scope> CURRENT` 跟踪当前调用栈；
     - `enter()` 返回 `Scope` 实例作为 token，支持嵌套调用（`scope.parent` 链式回滚）；
     - `exit(token)` 执行严格平衡校验（`CURRENT.get() != scope` 则抛异常），根节点完成即调用 `CURRENT.remove()`；
     - 不存在跨 rebuild 的静态/全局缓存引用，重建完成或异常退出后 Scope 立即释放，杜绝内存泄漏和陈旧形态数据残留。

### 1.2 `StructureLookupPatch.java` (ASM 字节码注入与 Finally 异常清理)

- **代码位置**: `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/StructureLookupPatch.java` (L1-L93)
- **检查项与代码证据**:
  1. **Pinned Wrapper 与 Finally 异常保证 (L60-L84)**:
     - 原始方法 `setupFormStructure_Normal(ACParameterControllableSource;Z)V` 被重命名为私有 `BODY` (`turboism$setupFormStructure_Normal`)；
     - 生成同名公开外层包装方法，在进入时调用 `NativeFormLookupBridge.enter()` 将 token 存入局部变量槽位 3；
     - `tryCatchBlock(start, end, failure, null)` 包裹对 `BODY` 的 `INVOKESPECIAL` 调用；
     - 正常路径：调用 `exit(slot 3)` 后 `RETURN`；
     - 异常路径（`failure`）：捕获任意 `Throwable`，存入槽位 4，调用 `exit(slot 3)`，随后 `ALOAD 4; ATHROW` 重新抛出；
     - `end` 标签严格位于正常 `exit` 之前，避免 finally 内部异常发生递归捕获。
  2. **栈帧与类型系统正确性 (L66-L72, L48-L54)**:
     - 包装方法异常帧提供完整 `F_FULL` 描述：`4 locals [OWNER, SOURCE, INTEGER, "java/lang/Object"]`，`1 stack ["java/lang/Throwable"]`，类型与槽位布局完全匹配；
     - 查找注入点 `setupFormStructure_Normal$local_findKeyform`：
       ```java
       visitMethodInsn(INVOKESTATIC, BRIDGE, "find", ...);
       visitInsn(DUP);
       visitFieldInsn(GETSTATIC, BRIDGE, "UNSUPPORTED", ...);
       visitJumpInsn(IF_ACMPEQ, official);
       visitTypeInsn(CHECKCAST, FORM);
       visitInsn(ARETURN);
       visitLabel(official);
       visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"});
       visitInsn(POP);
       ```
       - 当查找返回 `UNSUPPORTED` 时，跳转至 `official`，通过 `F_SAME1` 栈帧平衡并 `POP` 栈顶，无缝回退至官方原有搜索代码，局部变量和操作数栈完全恢复至方法入口状态；
       - 当查找命中（包括原生合法的 `null`）时，直接 `CHECKCAST` 返回。
  3. **基数门禁 (L89)**:
     - `setups[0] != 1 || finds[0] != 1` 立即抛出 `IllegalArgumentException`，在装载期熔断变体不匹配风险。

### 1.3 `NativeMeshInterpolationBridge.java` (通用插值与开放式 SNS 复用)

- **代码位置**: `runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.java` (L1-L460)
- **检查项与代码证据**:
  1. **支持形态与曲线矩阵 (L146-L238, L317-L325)**:
     - 完整覆盖 `CArtMeshForm`、`CWarpDeformerForm` 以及 `CRotationDeformerForm`；
     - 完整覆盖 `ELLIPSE` 与 `SNS_CURVE`；对于 `LINEAR`、未知类型或非法参数直接拒绝前置返回 `false`。
  2. **变异前拒绝与别名安全网 (L317-L384)**:
     - 检查 `Float.isFinite` 覆盖 scale、knot、weight 及所有 source 坐标；
     - `IdentityHashMap<Object, Boolean> targetArrays` 校验：
       - 若目标形态共享位置数组或实例（且涉及插入形态），拒绝变异；
       - 若源形态引用与插入目标形态别名，拒绝变异；
       - 所有拒绝均发生在 L398 首次形态变异之前，确保拒绝后原生官方 fallback 不会读取到部分修改的脏数据。
  3. **SNS 曲线单次 Common 生命周期与无拐角开放约束 (L427-L463)**:
     - `Object snsCurve = type == sns ? calculator.invokeExact(type) : null;`
     - 单次 `update` 调用内分配单个计算器实例，跨当前形态的全部几何顶点循环复用；
     - 每次初始化调用严格传递开放无拐角参数：
       `initialize.invokeExact(curve, (List<?>) vectors, true, (List<?>) null, (List<?>) knots);`
       （2nd: `isOpen = true`, 3rd: `isCorners = null`）；
     - 避免了 `AUDIT_REPORT.md` 揭示的通用共享缓存下 `g.b` (`firstCornerIndex`) 与 `g.c` (`isPureClosed`) 字段未重置引发的“闭->开污染”漏洞；
     - 方法执行完毕后 `snsCurve` 随栈帧即刻丢弃，不存入任何跨调用或跨对象静态缓存。
  4. **JNI / dirty / setUpdated 与属性保留 (L258-L265, L398-L463)**:
     - 属性插值通过官方 `zero()`, `blend()`, `postBlend()` 逐个执行，保留不透明度、绘制顺序、正负缩放及乘法/屏幕色；
     - 坐标写回严格执行 `old * 0f + new * 1f`，完全保留浮点 signed zero 及位对齐；
     - Rotation 原点单独适配 `originX/originY`，写入后调用 `updated.invokeExact(...)` (`setUpdated()`)；
     - ArtMesh / Warp 坐标写入后逐顶点/逐形态触发 `updated.invokeExact(...)`，确保形态 `EditVersion` 严格递增且与官方增量完全一致。

---

## 2. 真实版本矩阵覆盖与测试证据

依据 `evidence/final-installed-matrix/summary.json` 及版本测试日志（如 `5.3.04.log`）：
- **覆盖版本范围**: 5.0.00 ~ 5.0.06、5.1.00 ~ 5.1.03、5.2.00 ~ 5.2.03、5.3.00 ~ 5.3.04（全部收录的 **20 个稳定发布版**）。
- **执行结果**:
  - 每版本独立通过 **164 个 common 差分/拒绝用例**（连续 4 轮源点扰动，浮点 raw bits、EditVersion、颜色属性 100% 匹配）；
  - 每版本独立通过 **18 个多轴结构重建用例**（1/2/3 轴，ArtMesh/Warp/Rotation × 椭圆/SNS，验证重复 GUID 优先级、缺失回退、65,536 超限降级与异常作用域清理）；
  - **单版本运行计数**（各版本日志独立验证一致）：每版本包含 **3,444 次成功桥接调用**、**36 次前置拒绝**、**38 次索引构建**、**14,773 次索引查找**，0 次 VerifyError 或运行时发散（非 20 版累加总和）。

---

## 3. 有限测试边界与诚实标注

按照 SDD 主线与工程规范，本轮复核确认以下未覆盖或保留的边界条件，明确不做无边界外推：
1. **不声明全量工程测试全部通过（非日常验证门节点）**:
   本报告聚焦上述 3 个新热点及 20 版本安装器矩阵；不代表仓库全量测试（如 bootstrap 套件）此前全绿。Bootstrap 曾出现的 8 处测试失败系结构 helper 旧 start stub 引起，已交由 UI 代理修复，全量门禁交由父代理在最终验收节点统一运行。
2. **未完成 Editor UI 真实交互验收 (P0/P5 待完成)**:
   当前验证基于无头（headless）独立 JVM 配合官方真实 JAR 运行时进行内核级与安装器级差分验证；未覆盖真实的鼠标拖拽交互帧率、Canvas 重绘渲染、Undo/Redo 历史栈以及项目保存重开场景。
3. **多轴高阶笛卡尔网格的大量分配仍然保留 (P4 待完成)**:
   本轮优化解决的是形态 GUID 匹配（从 $O(N)$ 降至 $O(1)$）及单次 common 内部顶点曲线包装分配；三轴高插入点（如 6,832 个派生形态）下官方 dense grid 的固有分配并未削减，不能宣传为已根除内存膨胀。
4. **性能开关默认保持关闭**:
   `NativeOptimizationPolicy` 与 `NativeMeshInterpolationBridge` 默认处于关闭状态（`enabled = false`），安全门禁与工件 SHA 校验完整有效，未擅自推向生产默认生效。
5. **刷新范围未激进裁减**:
   本轮未强行将官方调用链中的 `force = true` 覆盖为 `false`，充分尊重了父 Part 派发和复合变形成形的保守性设计。

---

## 4. 结论与签发

当前拓展插值生产优化实现严谨、边界清晰、防御充分、字节码与生命周期管理完全契合官方语义。
**最终评审裁定: PASS (无需阻断性修改)**。
