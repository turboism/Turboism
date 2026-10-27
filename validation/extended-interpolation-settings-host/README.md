# 拓展插值设置宿主验收

目标 5.3.04。载体通过仓库托管队列、任务前缀副本及官方 BAT 运行；不改官方安装或原始模型。测试配置固定英文界面，便于识别确认按钮；产品复选框有六种本地化文本。

`SettingsHostProbe` 使用真实注册的 `Ctrl+Alt+S` 设置动作，找到实际显示窗口中名为 `mesh-interpolation` 的 `JCheckBox`，切到其所在 Performance 页，点击复选框与 OK。它不直接调用配置保存服务冒充 UI 验收。

验收分两次隔离 Editor 启动：

1. `edit`：初始配置关闭，确认 UI 关闭，点击启用并确认保存。检查文件中偏好为 true，但启动快照仍为 false；保存该文件供第二次启动。
2. `restart`：使用第一次输出的同一配置，禁止新旧两种插值 JVM 覆盖。检查启动请求来自保存配置、UI 保持选中，加载官方惰性扩展类以触发哈希准入，再要求实际诊断为 `installed`。

`*.png` 中 `*-swing-paint.png` 是活体设置窗口的 Swing `printAll` 绘制，不能用于证明 GL 画面等价。Proton 下 `Robot` 屏幕抓图可能全黑，同目录保留的 Robot 图片不作为视觉通过依据。本探针不覆盖源点编辑、Undo/Redo、级联几何或模型保存重开。

```sh
bash validation/extended-interpolation-settings-host/build.sh
TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env \
  bash validation/extended-interpolation-settings-host/run.sh edit \
  --golden-prefix "${CUBISM_GOLDEN_PREFIX}" --prepare-dir /tmp/settings-edit-request
python3 scripts/preview/host_validation.py _enqueue-runner \
  --request /tmp/settings-edit-request/runner-request.json
# 仅当前一次 outcome 确认为 succeeded 后，从其 task state 复制 saved-config.json。
# 将其作为 restart 模式的第二个参数，再 prepare / 托管提交。
```

本机 `.proton` 不包含 5.3.04；`.proton-v2` 中工件 SHA 与已审阅目标完全一致，因此需显式指定该前缀。`_enqueue-runner` 是通用 Runner 的现有队列入口，仍执行输入快照、准入、cgroup 生命周期与清理；不能直接启动 Editor main。

证据保存在本地忽略目录 `evidence/`。已记录的失败：

- `missing-default-prefix`：预检未找到目标 JAR，没有启动 Editor。
- `readonly-staging-failure`：真实复选框已显示，任务配置从只读 prepared 输入继承了 0400，Windows 原子替换失败。已修正 Runner 只为任务配置副本补写权限；输入快照权限保持不变。
- `edit-pass`：修复后真实 UI 保存通过，启动快照未被当次编辑改变，原始模型与任务模型哈希均未变化，官方进程正常退出、cgroup 清理安全。
- `diagnostic-reset-failure`：重启读取了启用配置且 UI 选中，但旧构建后续启动阶段重建配置快照，清掉 premain 安装诊断；新构建应显式在各阶段共享启动配置，并在官方惰性类实际加载后复验。

- `restart-pass`：同一份 UI 保存配置在独立启动中生效，无新旧 JVM 覆盖；Performance 复选框保持选中，官方扩展类实际加载后诊断为 `installed / PATCHED_5304`。job `ca3e5c33-a0bd-4b90-898a-e91126930eae`，bundle SHA `1088b649960765631a5e062d4b874528d3b32499190f91973ff73de3125c6bdd`。官方工件身份、原始模型不变、正常退出及安全清理均通过。

设置编辑与重启闭环已通过。所有成功与失败均以队列 outcome 和探针 result 为准；这不替代几何交互验收。
