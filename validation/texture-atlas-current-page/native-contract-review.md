# Texture atlas native 合同独立核对（只读）

结论：可实现为 **一次 receiver 输入 → 当前一页 placement + 原生 overflow**。缩放是模型图像局部坐标到纹理像素的绝对比例；不是旧图集比例的增量。旋转/写回公式见 §3。不得调用全模型图像枚举或规划其他页。

## 证据口径

- 核对时间：2026-09-07；当前 worktree `${ATLAS_WORKTREE}`。主 Agent 正在并行实现，下述当前实现/测试行号冻结于 **`84beadfd404509036e17a076a7c022e150f2c6ad`**，不评价其未提交工作。
- **A** = `runtime/src/main/java/dev/turboism/adapter/cubism/textureatlas/VerifiedTextureAtlasNativeInvocationAdapter.java`，上述基线。
- **T** = `runtime/src/test/java/dev/turboism/adapter/cubism/textureatlas/TextureAtlasNativeInvocationCoordinatorTest.java`，上述基线。
- **L** = `${LEGACY_WORKTREE}/plugins/turboism.texture-atlas/src/CubismTextureAtlasLayoutTool.java`；已确认 HEAD=`3c2f0fbbbaafa689afa32334941950790e0624dd`。
- **D** = `${LEGACY_RESEARCH_ROOT}/research/decompiled/com/live2d/cubism/doc/modeling/ui/atlasEditor/`。`D/a/b.kt`、`D/a/a.kt` 可读；`D/a/c.kt:1–10`、`D/a/e.kt:1–11`、`D/impl/v.kt:1–10` 实为反编译失败记录，不能当算法源码。
- 因上述缺口，独立以 **离线 `javap -c -p -l`** 核对官方 JAR；未运行其中任何类。JAR 无相关 LineNumberTable，下面的 **BC 是方法内字节码偏移，不冒充源代码行号**。
- 原生缩写：`C`=`com.live2d.cubism.doc.modeling.ui.atlasEditor.a.c`；`B`=同包 `b`；`C.run`=`C.a(c$c)`；`C.rect`=`C.b(c$b,b)`；`C.place`=`C.a(c$b,b,jp.live2d.type_editor.i,boolean,int,int)`；`V.run`=`com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a.run()`。

## 1. 输入、rect 与已有 scale

**只读 `receiver.c.b()` / `DATA_ITEMS`；尺寸来自同一 `receiver.c.c()/d()`。** 原生 `V.run` BC 1–89 仅枚举这一个 `impl.S` 的 container children，逐项 `new B(editLayer)`，随后调用 `C.run`。不是 `CTextureManager.getAllModelImages()`，也没有遍历纹理页。对应 L:391–413；A:76–103。

| 模式 | 精确源几何 | 比例含义与证据 |
|---|---|---|
| `settings.c()==false`（mesh 范围） | `r=item.h()`：局部 mesh shapes 的联合 AABB；没有 shapes 时退到完整图像矩形 | **不含现有 LayerRef 图集平移/旋转/scale，也不含 DATA_CURRENT_SCALE**。D/a/b.kt:78–110；`B.h` BC 0–359。|
| `settings.c()==true`（完整模型图像） | `r=item.e()=(0,0,item.f(),item.g())`；`f/g` 是 CModelImage 原始宽高 | D/a/b.kt:64–75；`B.e` BC 0–31。模式只改变每个输入的几何范围，**不改变输入集合**。|

额外追根：`TAE__EditLayer_ModelImage.setupEditLayer()` BC 313–397、622–712，以 `materialLocalToBasicCoordTransform.createInverse()` 把 editable mesh 坐标变成 material-local shapes；没有套用当前 atlas LayerRef transform。

`item.c()` 是 wrapper 的 `layoutScale`，**不是从当前纹理变换提取的旧比例**：构造器固定 `d=1.0`，getter 只返回 d，无 setter（D/a/b.kt:36–53；`B.<init>` BC 49–54）。原生 `C.rect` BC 93–128 确实在原始宽高上再乘此 q，但正常上述 factory 产生的 q 恒为 1。因此不能说 `item.h()` 已乘 q；也不能把旧 DATA_SCALE 再乘进去。L:436、526–527 的 q 因子在普通输入为 1，并非已证实的“双重缩放 bug”。

## 2. requestedScale：自动和固定

- `requestedScale > 0`：**固定绝对比例 s=requestedScale**。原生将缓冲区限宽高设为 `atlasW/s, atlasH/s`（C.run BC 81–140），结束仍直接用 requestedScale（708–727）；放不下的条目进入 overflow，不因想全放入而偷偷缩小。
- `requestedScale <= 0`：自动。原生先在可增长缓冲区布局，再按占用范围匹配页宽高，封顶 1（C.run BC 604–772）；数学意图为 `s=min(1,atlasW/usedW,atlasH/usedH)`，实际分支有 float 宽高比转换。**0 不是最终比例，也不等于固定 1。** 自动搜索可以替换启发式，但不应搜索整个纹理集。
- UI 固定值是百分数÷100，自动写 0：原生 `a.f.c(f,ActionEvent)` BC 66–107；可读 UI 线索 D/a/g.kt:16–22。
- 旧 DATA_CURRENT_SCALE 仅作回滚快照；新 s 不与其相乘。有限性/正有效输出比例应先校验；不要将 NaN/Infinity 当自动 sentinel。
- native 自动算法并非 legacy 二分搜索。是否追求“所选单页 packer 下最大可行 s”、精度/迭代次数是新实现策略，不是从原生获得的保证。

## 3. 可直接实现的 transform/scale 公式

令 `r=(rx,ry,rw,rh)` 为 §1 的**未取整源矩形**，`p=(px,py)` 为最终纹理像素中**内容 AABB 左上角，已排除 padding**，s 为 §2 的最终统一缩放。列向量，Java AffineTransform concatenate 为右乘。

```text
不旋转： M = T(px,py) · S(s) · T(-rx,-ry)
旋转：   M = T(px,py) · S(s) · T(rh,0) · R(+π/2) · T(-rx,-ry)

等价 native/legacy 分解：
M = S(s) · T(px/s,py/s) · [T(rh,0) · R(+π/2)] · T(-rx,-ry)
```

对应可直接构造的 `new AffineTransform(m00,m10,m01,m11,m02,m12)`：

```java
// no rotation
new AffineTransform(s, 0, 0, s, px - s*rx, py - s*ry);
// rotated +90° (screen y-down: clockwise)
new AffineTransform(0, s, -s, 0, px + s*(ry + rh), py - s*rx);
```

- 点变换：非旋转 `(px+s*(u-rx), py+s*(v-ry))`；旋转 `(px+s*(rh-(v-ry)), py+s*(u-rx))`。旋转后 AABB 为 `(px,py,s*rh,s*rw)`；非旋转为 `(px,py,s*rw,s*rh)`。
- **不是绕图片中心转；不是先把最终像素 p 再乘一次 s。** 先归一到源左上角、旋转、按原始源高度补偿，再缩放/平移。补偿用 raw rh，不是旋转后高、ceil(rh)、pack 高或含 margin 高。
- 原生构造证据：C.place BC 0–24 选源 rect；35–70 加缓冲 margin/位置；73–102 `translate(rh,0); rotate(π/2)`；105–135 `translate(-rx,-ry)`。C.run BC 848–875 再做 `S(s).concatenate(pos)` 并存 item。L:1174–1187、1195–1246 与这些顺序相符。
- CAffine 真正继承 Java AffineTransform；`CAffine.concatenate(CAffine)` BC 6–14 调父类 concatenate，`CAffine$a.a(double,double)` BC 0–18 调 `AffineTransform.getScaleInstance`。无额外隐藏反转。
- **整体替换** item/LayerRef 的 local→texture 变换；不要 concatenate 旧图集 M。旧变换可能非单位矩阵，但 native 本来也覆盖它。
- 对齐主 Agent 最新告知的 SDK 合同：取 `s=plan.scale()`，placement 宽高为 `ceil(inputWidth*s), ceil(inputHeight*s)`，旋转后交换。若输入已做 `inputWidth=ceil(rw)`，这个整数包围框可能比 M 的精确几何 AABB 稍大，属于保守装箱；**源 raw rh 仍须独立保留供旋转补偿**。不要改成 placement 高度作 pivot，也不能用 placement 整数宽/源宽反推非均匀 s。若每侧 padding=m，p 应为 pack 外框起点+m。native 自己的 margin/8px buffer 启发式不保证与该像素 padding 策略逐像素一致。
- 例：r=(10,20,80,40)，p=(7,11)，s=.5，旋转矩阵为 `(0,.5,-.5,0,37,6)`，四角 AABB 恰为 `(7,11,20,40)`。

## 4. DATA_SCALE 与写回：必须同步三种输出

`DATA_SCALE / atlasData.a(double)` **只赋字段 b**（D/a/a.kt:28–30；原生 BC 0–5），不会缩放 item、layer 或容器。新 s 必须与下面实际矩阵一致：

1. 对每个已放入输入调用 `ITEM_TRANSFORM(item, CAffine(M))`，即 B.a(CAffine) 只写 f（D/a/b.kt:60–62）。
2. 同步匹配该 item.editLayer 的 **LayerRef.setTransformToParent(editorAffine(M))**，不是只写 item 数据。native C.run BC 878–1027；L:1245–1261。找不到对应 layer 应拒绝/回滚，不应报 APPLIED。
3. `DATA_SCALE(data,s)`；原位替换 receiver overflow 的内容。未放入项不写新 transform；其他页、其他对象都不写。

特别注意：LayerRef setter 内部**保存 M 的逆矩阵并发 fireTransformChangedEvent**（BC 0–17）；getter `getTransformToParent` 再取逆返回 M（BC 0–7）。`jp.live2d.type_editor.a.b()` BC 0–13 调 createInverse。因此必须走 setter，不能直接写 LayerRef 内部 transform 字段；读回比较需容差，不能假设双重求逆逐 bit 相同。

native 在更新 LayerRef 前检查可逆且 `abs(det(M)) > 9.99999993922529e-9`（C.run BC 988–1027）；此处 det=s²。极小 s 不能仅 item 写成功就宣称视觉成功。建议写前一次性完成几何/成员/匹配/可逆校验，失败整体回滚 item f、LayerRef、data.b、overflow 原内容。A:228–246 已有该快照恢复形状，但 A:270–275 的基线成功读回仅核 item 和 overflow，**没有核 layer/data scale**。

## 5. overflow / 返回值 / 单页边界

- `receiver.i` 保存的是本次输入中未放入的 **原生 item 实例**，不是 textureId/跨页 placement。原生装不下分支直接 `i.add(item)`（C.a(c$b,b) BC 545–553、732–740）；写回循环跳过 overflow（C.run BC 836–845）。
- 原生调用方在 C.run 返回后读取 `C.a()`（直接返回 i），逐条执行当前 layer 的移除命令，然后刷新模型图像列表及当前视图：V.run BC 93–238。`jp.noids.design.layer.b.s.redo()` BC 40–63 确认最终 `ContainerLayer.removeChild`。**这是“交还未放入列表”，不是本 hook 去创建下一纹理页。** 不要在 adapter 提前删 child，更不要自己重新规划 overflow 的下一页。
- C.run 返回 true 表示算法正常完成，**不代表所有输入已放入**（BC 1092–1116）；非空输入甚至可以全 overflow。false 路径是无输入/取消等。当前 transformer 回调 handled=true 直接 IRETURN 1（`TextureAtlasAutoLayoutTransformer.java:181–186`）；host 的 V.run 甚至丢弃该 boolean（BC 104）后仍消费 i。
- 因而单页计划应表达当前页 placements 的子集；缺失输入归入 overflow，次序可保持原输入顺序；只允许 pageIndex=0。禁止为构造“完整 plan”规划页 1/2/... 后再把它们当 overflow。A 基线 `maxPages=32`（181）、要求所有 id（188–190）、将非零页作 overflow（196–207）是待替换的适配策略，不是 native 合同。
- L:1124–1130 清旧 i、1166–1170 加未放入项的意图正确；失败回退必须恢复原 i，避免部分写回污染随后运行的 native。

## 6. legacy 不能盲抄的点

1. **固定比例被偷偷降低（明确缺陷）**：L:466–469 固定 hi=lo=requestedScale；若放不下所有项，串行路径 L:556–558 又以 `scaleLo*0.8` 重排。这违反固定比例+overflow 合同。
2. **成功候选被后续试排破坏（明确引用别名缺陷）**：L:535–546 直接改共享 ItemInfo、保存含同一对象的 placed 列表；后一次失败 L:549–552 把全部对象位置/旋转/placed 清零；L:576、721–734 再 snapshot 的是被破坏状态，不是 bestScale 对应坐标。可能丢掉已找到的成功排版。应保存不可变/深拷贝结果，不能保存共享 mutable 列表。
3. **部分写回失败仍成功（明确缺陷）**：L:1154–1160 吞单项 Throwable 后继续写全局比例，executeLayout L:614 仍 true；还可能在 L:1258 因退化矩阵跳过视觉写回。相邻 `TextureAtlasBridge.java:139–143` 的 tryInvoke 返回 optional/null，不构成 setter 成功证明。新实现不应复制这种“数据成功/画布未动”的边界。
4. **q≠1 没有可靠合同（范围外）**：L:526–527 装箱乘 q，L:1234–1246 输出只乘 s；原生亦是装箱读 q、变换不乘 q。正常原生 factory q=1，故这不是正常流程的二次缩放问题；若有人反射改 wrapper.d，不能据 legacy 推导支持任意 q，更不能擅自把 q 当旧 atlas scale。

## 7. 映射/验证可用性与明确缺口

- 三份 `compatibility/cubism/verification/cubism-{5.2.03,5.3.02,5.3.03}-editor-model.json` 都有 32 个 native selector，**没有 `native.item.scale` 的 selector**。A:37 定义字符串不代表 resolver 可用；`VerifiedCubism5303TextureAtlasSelectorContract.java:71–104` 也未要求它。正常 q=1 路径无须引入未知旧比例。
- **模型图像类型陷阱**：5.3.03 JSON:6043–6063 中 `ITEM_MODEL_RECT` 返回 CRect，而 `ITEM_RECT` 返回 GRectF；JSON:6164–6206 的 RECT_* getter owner 全是 GRectF。不能把 CRect 传给 GRectF getter。完整图像模式已证实源原点固定 0，直接用现有 ITEM_WIDTH/HEIGHT 即可；若走 e().toGRect() 则需另有经核准映射，当前这 32 项并未提供转换。
- 基线 T:26–81 覆盖 temporary state、不调用 persistent provider、失败回滚；T:84–151 覆盖连接切换、Throwable、嵌套。**不覆盖实际缩放、旋转、模型图像模式**：T:259–263 设置写死 false/false/1；T:280 源原点恒 0；T:283 把 e()/h() 伪装成同一种 Rect，无法捕获真实 CRect/GRectF 不匹配；T:304 setter 也未模拟 native 求逆/事件。
- 应补的 focused 验收（交主 Agent，不在本任务改测试）：负/非零源原点；分数 rh 旋转补偿；旧 layer scale≠1 但新固定 s 为绝对值；固定 .5/1/>1 与 overflow；自动 s≤1；两种模式不同几何；partial/全 overflow；其他页不动；写回中途失败恢复全部输出；layer 求逆读回容差与 det 门槛。
- 本次**已执行**：三份 JSON 的 targeted 解析；官方 JAR 哈希及指定类比对；关键方法离线反汇编；**48 组纯数学断言通过**（非零/负/分数原点、.125/.5/1/1.25、旋转/不旋转，验证拼接=显式矩阵、四角 AABB、det=s²）。这不是 native 动态执行或项目测试。
- 本次**未执行** Gradle/JUnit，以免生成/改动仓库 build 与缓存；也未启动、连接、探测或修改 Cubism 会话。现有 T 可作为 focused 扩展入口，不能由 static/fake 结果宣称 exact-host readiness。
- 指定的 `${PROJECT_ROOT}/.docs/oracle/texture-atlas-r9-c22f8ff/review.md:10,34–37,54` 审的是旧 persistent manager/Undo 真值修复，不是本 native-invocation 单页合同，也明确不等同 real-host readiness。交互、Undo/Redo、取消恢复、保存重开仍属**未做的实机验收**，本任务不授权执行。

### 精确 JAR 身份与复核方式

都只读自 `${GOLDEN_PREFIX}/pfx/drive_c/Program Files/Live2D Cubism <目录版本>/app/lib/Live2D_Cubism.jar`：

| profile（目录） | 本次实算 SHA-256 |
|---|---|
| 5.2.03（5.2） | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` |
| 5.3.02（5.3） | `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21` |
| 5.3.03（5.3.03） | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |

三版本 a/a、a/b、a/c、a/d、a/e、a/c$b、a/f、impl/v$a、CAffine、LayerRef、editor affine 的 class 字节逐个相同；其中 a/c SHA=`dc567866e1bd2c9fe6da8c80d155769d425fd793da2d140e6fa3eb5116d6f7b7`。CAffine companion 整类不同，但上述 scale 工厂方法规范化反汇编一致；EditLayer.setupEditLayer 核对到的 5.2→5.3.03 差异仅是 getGlIndices 默认参数辅助类 `util/i/a`→`util/j/a`，逆变换构造路径一致。不推广到未核验未来版本。

只读复核命令示例（不会启动 Cubism）：

```sh
javap -c -p -l -classpath "$JAR" 'com.live2d.cubism.doc.modeling.ui.atlasEditor.a.c'
javap -c -p -l -classpath "$JAR" 'com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a'
```

**交付边界：仅新增本 `/tmp` 报告；没有修改生产实现、SDK、测试或宿主文件。**
