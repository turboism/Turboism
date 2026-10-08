# T052 局部边端点去重可行性

已完成官方字节码审查和自有原型；未改生产代码、未启动新实机。
官方5303 JAR扫描16992个类，直接引用 k 的类只有 c、h、TriangleList和k。
常量池扫描覆盖字段/方法/接口及方法句柄所引用的成员，不排除反射或JNI访问。

修正上一份报告中的保守假设：精确官方类型中 TriPoint.index 是private final，
final j 的两个端点引用也是private final，均无普通setter。公开可变列表仍让
通用缓存失效无法仅凭长度判断；本轮不采用全局 k 缓存。

候选379个 k.a(j,boolean) Java叶样本中363个（95.78%）直接来自
TriangleList.b()，占1125个三角化Java样本的32.27%。这个方法创建局部 k，
遍历原TriangleList，先调用三次边getter，再按顺序查询/追加；所有查询boolean
参数都为false。在返回前没有列表/迭代器暴露，没有remove。这给出了更窄的
优化区间：用构建方法内的无向端点集合决定首次追加，保留首次边对象及顺序。
样本占比不是额外可消除耗时或提速预测。

原型 diagnostic/EndpointMembershipSelfCheck.java 仅操作自有类型。通过32734项
检查，覆盖随机追加/删除差分、同端点不同对象、方向、极值/负索引、退化边、
局部构建首次对象身份/输出顺序、同长度列表替换、子列表/迭代器删除以及null
早返回/异常回退。原型通用Membership在列表/迭代器暴露后永久退回线性扫描；
生产候选方向是独立的局部builder，不是这个通用缓存。

Java17 -Xlint:all -Werror 编译及 -Xverify:all 执行通过。自有2048三角形strip
构建最终4097条有序边；24次预热，5次交替顺序对照中位耗时17.632→1.501ms，
下降91.49%。统计包含临时HashSet构建成本，但不是Cubism收益。HashSet装箱
增加分配，生产方案需同时测量分配/RSS，不能只看局部速度。

输入SHA及每轮数值见同名.json；原始审查材料、日志在
build/t052-endpoint-membership-r1/。未直接发布官方类字节码或修改官方安装。

下一生产切片应只接入TriangleList.b局部构建，精确验证三个查询/追加点及依赖
k/j/TriPoint类身份；保留三次原生边getter时序、失败回退、返回边顺序及对象
身份，不改变几何。之后做三版本形状/有序结果验证、冻结新工件、按共享FIFO
实机完整输出与CPU/内存对照。当前尚未实现transformer，也没有exact-host PASS。
