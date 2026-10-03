# T099 NMT启动路径失败，未执行原生命令

FIFO2656/job53e7abbc-de10-4e9e-9ce0-d2a3d149e0f5/runqueue-cfa3c19735874824aa8eeb54e3b8c454单次运行终态failed。46原始文件及39冻结输入SHA核验，failure/cleanup/退出/identity/fixture原判定全部保留，不将事后部分hash一致升级为标准PASS。canonical DRIVER_BLOCKED、driver-error明示in-process NMT summary unavailable；console明示Native Memory Tracking did not setup properly, using wrong launcher?。原生CPU0条/page0条，候选没有提交；控制器已退出，禁止重启或补该协议的候选。

实际Cubism JVM17.0.3.1+2-LTS通过JAVA_TOOL_OPTIONS收到NMT flag，却未正确初始化。Linux离线JVM为17.0.20+8，新的环境重现尝试实际接受了env NMT，因错误期望disabled拒绝而assertion失败；该失败保留，说明先前本机离线通过不足以证明旧Windows运行时的启动兼容性。没有原生算法错误或CPU/RSS收益可由本次证明。T088/T097/旧参考FAIL、T057交付及全部开放验收不变。

下一步独立直接启动可行性预检：官方BAT在Java命令前调用ProxyConfig.bat，且通过MAXMEMORY传入-XX:MaxRAMPercentage=100。新pre-launch hook仅在新任务克隆的该配置保留全部原始bytes后追加同MaxRAMPercentage加NMT summary，官方BAT与golden不改；配置和BAT固定SHA/参数位置/任务路径核验，不改通用runner，不改变正式性能门槛。需离线拒绝/保留控制后冻结新protocol，再按FIFO单次验证；不得修改或重跑T099。
