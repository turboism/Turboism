# 拓展插值刷新范围调查证据清单 (Refresh Scope Evidence)

本目录包含针对 Cubism Editor 5.3.04 及历史 20 个版本（5.0.00–5.3.04）在刷新范围、调用点、旋转变形器行为与 P3 独立优化审计方面的可复核探针源码与原始日志。

## 目录文件说明

1. `RotationDeformerScalingProbe.java` & `RotationDeformerScalingProbe.log`
   - 目标：测量 `CRotationDeformerForm` 在 ELLIPSE / SNS_CURVE 两种插值下随插入点（1, 4, 16, 64）增长的单次耗时与单次内存分配量。
   - 确认点：
     - `getAllPointRef().size()` 恒等于 1（仅包含旋转中心原点 `(originX, originY)`，无网格顶点数组）；
     - 几何点循环仅执行 1 轮；在 64 插入点（131 形态）时，单次耗时仅 0.089 ms (ELLIPSE) / 0.308 ms (SNS)，分配量仅 25.3 KB (ELLIPSE) / 34.6 KB (SNS)；
     - 证明旋转变形器不存在与 Warp 同类的几何顶点级膨胀瓶颈（比 Warp 小 1–2 个数量级）。

2. `FirstOrNullAuditProbe.java` & `FirstOrNullAuditProbe.log`
   - 目标：审计 `updateInterpolatedForms_exe` 首行 `firstOrNull(getAllExtendedKeyforms())` 的优化方案。
   - 核心逻辑：以 `!normal.isEmpty()` 作为保护，而非 `firstOrNull != null`，确保非空首 null 的极端边缘情况不落入 morph；
   - 确认点：
     - 在 Normal+Morph、Morph-only、Non-empty Normal with null head、Both Empty 全部 4 种分支场景下，优化方案产出的对象引用与官方完全一致；
     - 在 151 个形态规模下，单次调用耗时从 802.6 ns 降至 27.2 ns，堆分配从 1,288 B 降至 0 B（100% 消除该处堆分配）。

3. `scan-update-calls-20versions.py` & `scan-update-calls-20versions.log`
   - 目标：使用 `javap -c -p` 扫描 `/opt/dev/projects/turboism/cubism-ref/` 下 20 个正式版本 Live2D_Cubism.jar 内所有包含该方法名的类，并核验其中对 `updateInterpolatedForms(Z)Z` 的字节码调用点；
   - 确认点：
     - 在 5.0.00 至 5.3.04 全部 20 个版本中，`CExtendedInterpolationExtension.setupFormStructure` 与 `ExtendedInterpolationUtil`（`l.class`）两处调用点入栈的实参均为 `iconst_1`（即字面量 `true`）；
     - 证明在已知的原生静态调用链中，`forceUpdate` 参数被硬编码为 `true`。

## 复核命令

环境要求：JDK 17+、Python 3.10+，官方参考目录 `/opt/dev/projects/turboism/cubism-ref`。
**注意**：为防止官方类在进程当前工作目录下生成临时文件（如 `data/`），运行探针时请将工作目录置于 `/tmp` 下。

```sh
# 1. 扫描 20 版本字节码调用点
python3 validation/extended-interpolation-performance/refresh-evidence/scan-update-calls-20versions.py

# 2. 编译并运行旋转变形器探针
repo_path="$PWD"
mkdir -p /tmp/refresh-probe-verify/bin
cd /tmp/refresh-probe-verify
javac -cp "/opt/dev/projects/turboism/cubism-ref/5.3.04/*" -d bin \
  "$repo_path"/validation/extended-interpolation-performance/refresh-evidence/RotationDeformerScalingProbe.java
java -cp "bin:/opt/dev/projects/turboism/cubism-ref/5.3.04/*" \
  dev.turboism.probe.RotationDeformerScalingProbe

# 3. 编译并运行 firstOrNull 审计探针
javac -cp "/opt/dev/projects/turboism/cubism-ref/5.3.04/*" -d bin \
  "$repo_path"/validation/extended-interpolation-performance/refresh-evidence/FirstOrNullAuditProbe.java
java -cp "bin:/opt/dev/projects/turboism/cubism-ref/5.3.04/*" \
  dev.turboism.probe.FirstOrNullAuditProbe

# 4. 清理临时运行目录
cd /
rm -rf /tmp/refresh-probe-verify
```
