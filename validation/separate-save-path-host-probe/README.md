# separate-save-path-host-probe

探针验证普通插件的只读文件选择器历史契约。

先构建 SDK：`./gradlew :sdk:jar`。再用 `bash validation/separate-save-path-host-probe/build.sh` 独立构建探针。离线回归命令为 `bash validation/separate-save-path-host-probe/test.sh`。两条脚本均只依赖 SDK JAR 和 JDK，不依赖 runtime 或其他插件。

宿主 Runner 在启动前通过 `--home-file` 将 `fixtures/save-directory-history.properties` 放入隔离 home 的 shell 配置目录。两个相对路径是独立哨兵值。探针不打开这些路径，不执行保存。

探针必须读到这两个槽，并核对磁盘持久化。两个 setter 与 provider 注册必须分别抛出 `UnsupportedOperationException`。拒绝后，读取结果和持久化内容必须保持不变。服务不可用、空槽、错槽、静默接受写入均判失败。探针还保留全局配置迁移和导出分离开关断言。

结果采用 schemaVersion=2，并标记 `contract=plugin-read-only-seeded-history`。旧版写入往返证据不能证明此契约。此探针验证持久化读取和只读边界；真实保存对话框产生历史的行为仍需单独宿主证据。
