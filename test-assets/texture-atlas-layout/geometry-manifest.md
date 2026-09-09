# Geometry test assets

用户提供 `/tmp/geometry.7z`，仅用于当前测试，不再分发。压缩包11279490字节，SHA-256 `09851c72d91be8c51beb7868968ffddae509173fa038661251b64b8c610df0f2`。`7z t`通过；7个普通文件共34848642字节，文件名白名单核对通过，独立临时目录解包后按后缀归档，未覆盖不同内容文件。

**与Circle的区别：**用户说明Circle每个模型中的图像为大小相同的圆形；Geometry为大小和形状各异的几何图像。两种输入分布分开测试和报告。

所有PSD：2048×2048、8-bit RGB、4通道；直接解析PSD结构确认图层记录数与名称一致。CMO3头为CAFF、字节及复制哈希一致。宿主实际输入数和纹理页尺寸需实测，不以PSD尺寸代替。

| 路径（相对此用途目录） | 字节 | SHA-256 |
|---|---:|---|
| `cmo3/geometry/atlas_mapping_geometry_100.cmo3` | 694856 | `369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff` |
| `cmo3/geometry/atlas_mapping_geometry_500.cmo3` | 3564178 | `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b` |
| `cmo3/geometry/atlas_mapping_geometry_1000.cmo3` | 6701324 | `2f64a7f5fd4f2abc4cdd0036d1943f8382f30581228ea5f87143b5b367abd3e2` |
| `cmo3/geometry/atlas_mapping_geometry_2500.cmo3` | 24581494 | `4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b` |
| `psd/geometry/atlas_mapping_geometry_100.psd` | 946075 | `6aac4d59e30b27800a7eae4039a547f25f4fe6bd0c8db3a2b208180e2cee4f30` |
| `psd/geometry/atlas_mapping_geometry_500.psd` | 3267871 | `ad0c6ab31da87e2a05a497ea735a8878bb93035e7c9518938044cb0ba6117af1` |
| `psd/geometry/atlas_mapping_geometry_1000.psd` | 5850323 | `24f1e83ab7ebb5ea53fd6fb7ad3f55b1114d2ad3c0efe52c3b05a642b09d4dac` |
| `psd/geometry/atlas_mapping_geometry_2500.psd` | 13824015 | `ef210e2bfaf2d03ca770aed07a94d861fd9636d741ed5e33f49f5980e022b8b1` |

压缩包最初仅含100/500/1000三档CMO3与2500 PSD。用户随后单独提供 `/tmp/atlas_mapping_geometry_2500.cmo3`（非压缩包内文件），现已核对CAFF头、24581494字节及源/复制SHA-256一致并归档。用户说明该工程单张纹理含2500模型图像；实机仍须验证实际输入数。三档验证完成后，用户明确授权继续2500，原生、新算法各一次，无预热及自动重试。

测试前必须另建模型运行副本；输入资产与压缩包保持不变。二进制内容被本目录Git忽略规则排除。
