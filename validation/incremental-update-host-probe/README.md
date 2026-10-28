# incremental-update-host-probe

增量更新路径宿主行为探针。

运行：`./build.sh` 编译。需要真实 Cubism Editor 宿主。

## 撤销重建与版本兼容（2026-10-28）

Warp 绿色手柄拖动后撤销可在不改变文档时间戳、不调用插值入口的情况下，通过
`reinitInstance` 原地恢复表单。仅监听插值无法证明子 ArtMesh 的计算坐标仍可复用。
增量更新现在监听公共 `ACParameterControllableSource.reinitInstance` 入口，清除已完成和
待提交的基线、父子缓存及差分探针状态，同时恢复原生插值。下一次更新完整计算，随后
稳定帧恢复增量复用。帧内重建不会提交旧基线；关闭优化时移除新增回调并恢复重写类。

覆盖的 20 个版本：5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04。
逐版本固定公共源类、Warp/Rotation/ArtMesh 源类及继承链的 SHA-256；未知方法或字节码
不匹配时拒绝安装。安装器要求五个类、七个方法全部注入成功，卸载覆盖新增源类。

自动回归：

```sh
./gradlew :runtime:test --tests '*IncrementalUpdate*' \
  :bootstrap:test --tests '*IncrementalUpdate*'
TURBOISM_CUBISM_REF=/path/to/refs ./gradlew :runtime:legacyCubismEvidenceTest \
  --tests '*IncrementalUpdateArtifactTest*'
```

参考目录为 `<refs>/<version>/Live2D_Cubism.jar`，同目录及其子目录提供该版本的依赖 JAR。
允许版本目录使用符号链接。无授权宿主材料的版本单独跳过，不计为已验证。

本次 20 个真实 JAR 检查全部通过，覆盖类指纹、源重建继承调用链、依赖与方法签名、
五个类的真实字节码转换。离线测试覆盖无时间戳/插值回调的重建失效、帧内及异线程通知、
随后恢复复用，以及通知缺失、类型错误和异常时原生重建继续执行。这些结果不代表
20 个版本均已通过编辑器交互回归。

宿主交互验收：关闭两个镜像，开启增量更新，拖动 Warp 绿色手柄后分别撤销、重做，
检查 Warp、源坐标、子 ArtMesh 和多层子变形器一致恢复；稳定帧应再次出现复用。
再关闭增量更新做相同操作，比较结果。当前环境尚未完成这一原生 GUI 验收。

离线门禁结果：无图形模式 `devCheck` 通过；runtime 准入共 3981 项、零失败、8 项跳过。
真实版本回归 20 项全部通过、无跳过。runtime/bootstrap 的 `spotlessJavaCheck` 通过。
默认图形模式的首轮 `devCheck` 因既有截图测试阻塞于系统屏幕捕获而中止，未计为通过。
