# Owned MOC 生产 SDK 实机验证（部分能力）

这是全能力表中的一项证据，不是最终全版本支持表。所有未列版本仍待验证。

| Editor | 实测 Core | 原生 SDK 断言 | supervisor | 范围限制 |
|---|---|---|---|---|
| 5.3.04 | 6.0.513 | PASS：读取、update、独立实例、不可变副本、显式释放、父子级联关闭、关闭后拒绝操作 | PASS，身份/夹具/正常退出通过，cleanup safe | Haru MOC：42参数/19部件/84 drawable/97 deformer/0 Glue；未证明非空 Glue、Editor acquisition、完整 MCP/保存或 Mesa 优化 |

证据：[原生结果](mcp-evidence/5.3.04-allversions-owned-r5/core-owned-moc-result.properties)、[supervisor 终态摘录](mcp-evidence/5.3.04-allversions-owned-r5/lifecycle-result.json)。job `6b80a640-8264-44bf-91f0-d10590071d50`；prepared digest `3264ebb68c191f2336cb77dee80e9be8d559ad065b700983e3b5c753eb7f4384`。
