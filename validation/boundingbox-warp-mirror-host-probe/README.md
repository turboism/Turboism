# boundingbox-warp-mirror-host-probe

warp 变形器镜像时包围盒更新的宿主行为探针。

运行：`./build.sh` 编译。需要真实 Cubism Editor 宿主。

通过 `scripts/preview/run-boundingbox-warp-mirror-host-validation.sh` 运行时会自动启用
`-Dturboism.validation.boundingBoxOverlay.buttonPositions=true`，输出自动点击所需的
`BBOX_OVERLAY_BUTTON_RECT` 坐标。手动运行探针时也需添加此 JVM 参数；日常运行默认不输出这些验证日志。
