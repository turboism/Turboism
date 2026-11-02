# ArtPath 与增量更新：可行性分析（2026-11-02）

## 结论

ArtPath 在宿主管线中的结构与 glue 相同：输出由**当前 ArtMesh 的计算表单**派生，
路径自身的插值按对象受宿主跳过标志控制。因此增量复用（只复用 `deformedForm` 与
按对象的插值跳过）在原理上同样成立。但本地没有含 ArtPath 的工程可做实机对照，
`unsafe()` 目前仍整体拒绝 ArtPath；本次没有放开。

## 宿主管线证据（5.0.00 / 5.2.03 / 5.3.04）

几何更新里 ArtPath 有两遍：

1. `c(CModel, CParameterSet, List<CArtPath>, ...)` — 只做插值：
   `e && keyformGrid.c()` 为真时跳过，与 ArtMesh/Deformer 相同；没有 transform。
2. `a(CModel, List<CArtPath>, az)` — 对每个路径：

   - `CArtPath.setPostAffectedForm(null)` 清空上一帧的输出；
   - 若 `CArtPathSource.getExtensions()` 中有
     `CArtPathPointToArtMeshExtension`，先 `preparePostAffectedForm$cubism()`，
     再用 `PointInTriangle` 从 ArtMesh 的 `getCalculatedForm()` 取三角形内的点，
     写入**路径点**的 `CSplineCurvePoint.getPoint()`（`setX/setY`）。

`CArtPath.getCalculatedForm()` 依次回退 `postAffectedForm -> affectedForm ->
deformedForm -> localAnimatedForm -> interpolatedForm`，渲染使用该结果。整个
ArtPath 遍只读 ArtMesh，不写网格顶点；与 glue 一样，复用网格 `deformedForm`
不会让路径结果变旧。

## 仍需解决的问题

1. **探针覆盖**：`IncrementalUpdateBridge.digest()` 目前记录 `getDeformedForm()`
   与 `getAffectedForm()`；ArtPath 的实际输出是 `getPostAffectedForm()`。
   放开前必须把后者纳入 digest，否则交替 full/narrow 探针对路径是盲的。
   该 getter 在 20 个已审核版本的 `CArtPath` 上都存在，可作为新的已验证依赖。
2. **扩展白名单**：ArtPath 源码上可能挂其他 `ACExtension`
   （controller、autoYure 等）。当前更新遍只处理
   `CArtPathPointToArtMeshExtension`；未审核的扩展类型应继续让模型走原生。
3. **实机样本**：本地 3 个模型（含 763 MB 工程）诊断结果均为 `artPaths=0`。
   没有 ArtPath 工程就无法做 OFF/ON 坐标与像素对照，也就没有本仓库要求的
   实机验收证据。

## 放开时的最小改动

```text
unsafe(model):
  paths 全部满足：
    - getAllArtPaths() 是 List；
    - 每个路径 source 的 extensions 只含 CArtPathPointToArtMeshExtension（或为空）；
    - 新开关 turboism.optimization.incrementalUpdateArtPaths（默认 false）。
digest(drawable):
  CArtPath -> putForm(getPostAffectedForm())
验收:
  含 ArtPath 的工程上跑 Warp 拖动 + Undo/Redo，
  OFF/ON 的 getCalculatedForm()（路径为 postAffectedForm）逐位一致、像素一致，
  ON 复用 > 0，failures = 0；再跑 probe 交替 full/narrow 无 mismatch。
```

在这些条件满足前，ArtPath 模型继续完整原生回退，不影响现有 glue 开关。
