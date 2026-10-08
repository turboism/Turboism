# T107 point准入回调检查分配归属

仅复用已保存T105录制，与T102已有T057/T093诊断对照；无新宿主、无生产修改。每份录制独立task/EDT/三命令/30 CPU字段绑定，全部输入SHA重核。采样权重是估计值，不是精确分配字节、CPU比例或正式配对因果证据。

| 诊断工件 | 全部采样权重MiB | point角点leaf MiB | callback栈遍历inclusive MiB | point入口callback栈遍历inclusive MiB |
| --- | ---: | ---: | ---: | ---: |
| T057 | 10617.53 | 5864.21 | 207.74 | 0 |
| T093 | 10636.29 | 5665.23 | 255.56 | 0 |
| T104 | 7212.14 | 27.91 | 2748.85 | 2513.03 |

point角点leaf是GVector2对象且最顶层分配方法为PointInTriangleD$a.a；inclusive callback要求同栈明确出现inTransformerCallback，point子集再要求enterPoint。集合有包含关系，不得相加。T104最大的对象采样权重为MemberName1562.73MiB和StackFrameInfo914.64MiB。完整样本栈保存于JSON：MemberName→StackFrameInfo→StackStreamFactory→StackWalker.walk→inTransformerCallback→Gate.acquire→enterPoint→实际SDK点查询→meshGenerator/actionManager。

当前源码Gate.acquire每次调用STACK.walk扫描ClassFileTransformer/JDK transform上下文，点查询新增了该热路径。角点复用符合预期采样下降，但全量录制下降不能抵消T106正式CPU/wall回退；独立录制不证明这一个检查就是T106回退的全部原因。

下一方向：降低高频point回调上下文检测成本，先证明完整transformer callback覆盖，再选择快路径；不可直接删除拒绝检查或放宽定义锁/撤销/完整native回退。T106正式FAIL保持，T057交付不变，目标未完成。
