# 全安装版本验证台账（进行中）

当前表不是最终支持承诺。静态记录、启动成功、验证工具缺口和能力不支持分别记录。
所有能力仍需与实际断言逐项关联；未验证不得填入支持或不支持。

基线：`7d7de1de4`；20 个安装版本；131 个静态能力标识；46 个可选 SDK 服务；30 个插件；140 个目录测试场景。

| 版本 | 精确静态记录能力数 | 无精确记录能力数 | 支持能力集 | 不支持能力集 | 状态 |
| --- | ---: | ---: | --- | --- | --- |
| 5.0.00 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.01 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.02 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.03 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.04 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.05 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.06 | 90 | 41 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.1.00 | 97 | 34 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.1.01 | 97 | 34 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.1.02 | 104 | 27 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.1.03 | 104 | 27 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.2.00 | 123 | 8 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.2.01 | 123 | 8 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.2.02 | 123 | 8 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.2.03 | 126 | 5 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.3.00 | 129 | 2 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.3.01 | 129 | 2 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.3.02 | 128 | 3 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.3.03 | 127 | 4 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.3.04 | 131 | 0 | 待逐项验证 | 待负向证据 | 进行中 |

完整能力、SDK 服务、插件、场景及安装摘要见 [inventory.json](inventory.json)。
窄范围实机历史证据见 [验证记录](README.md)，不能直接折算为全能力通过。

## 尚未登记为任务的验证脚本

- `scripts/preview/run-atlas-image-host-validation.sh`
- `scripts/preview/run-atlas-image-shadow-host-validation.sh`
- `scripts/preview/run-atlas-polygon-host-validation.sh`
- `scripts/preview/run-bounding-box-overlay-host-validation.sh`
- `scripts/preview/run-cubism-host-validation.sh`
- `scripts/preview/run-external-psd-edit-host-validation.sh`
- `scripts/preview/run-float-array-host-validation.sh`
- `scripts/preview/run-fx-host-validation.sh`
- `scripts/preview/run-graal-script-host-validation.sh`
- `scripts/preview/run-image-archive-host-validation.sh`
- `scripts/preview/run-mesh-edit-host-validation.sh`
- `scripts/preview/run-plugin-management-chooser-host-validation.sh`
- `scripts/preview/run-resource-host-validation.sh`
- `scripts/preview/run-settings-page-host-validation.sh`
- `scripts/preview/run-texture-upload-host-validation.sh`
- `scripts/preview/run-warp-position-host-validation.sh`
