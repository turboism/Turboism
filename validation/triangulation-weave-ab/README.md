# T029-TRIAB — 官方类织入 A/B 定论腿（离线切片）

冻结依据：`specs/020-atlas-image-parallelism/plan.md` 的 T029-TRIAB 节 + tasks.md 同名条目。
设计、javap 证据、门行为矩阵与 4 腿准入草案：`DESIGN.md`。

- 单一 aux agent `tri-weave-agent.jar`，两种模式：`dump-only`（基线腿）与
  `dump+weave`（woven 腿）。两腿间唯一差异是候选织入是否生效；采集代码完全一致。
- 候选变换复用 `validation/triangulation-k-membership` 的 `Weave.java`（泛化为
  `Weave.Config`，官方 owner/descriptor/锚点形状经配置注入）——单一实现，无分叉复制。
- 观测点：目标方法前 N 次返回（N≤4，`captureN`），导出有序边端点 index 序列的
  SHA-256 + 有界原始序列到 `{outputDir}` 专属隔离 sink。
- 形状拒织 / woven 模式缺 Helper → 同步写 `legStatus=INVALID` 状态文件，
  绝不静默降级为基线。
- 官方类只读：javap/编译期 classpath 核定签名；运行时门 = loader + class SHA-256 +
  codeSource canonical 精确匹配。

## 复现

```
bash validation/triangulation-weave-ab/build.sh   # 需要官方 jar 只读编译 classpath
bash validation/triangulation-weave-ab/run.sh     # 全场景自验（-Xverify:all 独立 JVM）
```

依赖 sha pin：kotlin-stdlib 1.7.21 (`d46a9d77…`)、ASM 9.7.1 (`8cadd43a…`)、
官方 Live2D_Cubism.jar 5.3.03 (`bd0a23b9…`) — 均沿用 run.sh 既有机制。
