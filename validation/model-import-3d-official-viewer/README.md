# 官方 Viewer 显示验证（中立姿态已观察）

`OfficialViewerCapture` 调用已安装 Cubism 5.3.02 的官方 Viewer 主入口，
打开实际导出的 `cube.model3.json`，观察同一进程中的主窗口并记录三帧截图与
Swing 组件树，然后发送普通窗口关闭事件。它拒绝 Turboism 类路径与 JVM Agent。
没有自制渲染器、模型求值器或对官方模型的反射写入。

已接入共享队列 `official-viewer:5302`，使用隔离 prefix/home 与私有 Xvfb。
官方资源闭包、实际进程与正常退出已核验；r2 截图已独立确认源贴图立方体正面显示。
定时截图仍标记为 `CAPTURED_NOT_RENDER_VERIFIED`，观察结果另记为
`NEUTRAL_POSE_DISPLAY_ONLY`，不代表多姿态验收。
r3 已通过标准 Swing 选择 `model.moc3` 节点并显示两个立方体角度参数。
新版观察器在私有显示中用普通鼠标点击滑条的 20% / 80% 位置，记录联合角度截图；
当前仅编译通过，尚未执行新版。点击位置不等于已读回的精确参数值，多姿态源参考比较仍待完成。不能在用户会话直接启动。

使用官方库编译 Java 17；运行依赖须取自官方 `CubismViewer5.bat` 声明的完整
classpath/native 路径，并增加此独立观察器 JAR。仅编译不证明依赖闭包或实机显示可用。
