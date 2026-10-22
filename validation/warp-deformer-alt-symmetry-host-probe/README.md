# warp-deformer-alt-symmetry-host-probe

warp 变形器 Alt 对称操作宿主行为探针。

运行：`./build.sh` 编译。需要真实 Cubism Editor 宿主。

镜像 Hook 与按钮接口的多版本字节码回归检查（覆盖 class-pins 中全部审核版本）：

```sh
TURBOISM_CUBISM_REF_ROOT=/path/to/cubism-ref ./gradlew :runtime:warpAltMirrorArtifactTest
```

目录应包含每个版本的 `<version>/Live2D_Cubism.jar`。此任务逐版本校验类摘要、全部 Hook 目标和实际注入结果，以及按钮工厂、工具条分组和场景图接口；缺少任一版本或接口即失败。它不替代真实宿主中的按钮绘制、点击切换和镜像拖拽验证。
