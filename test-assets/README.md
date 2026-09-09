# Test Assets

此目录专门存放人工提供的测试 PSD 和 Cubism 模型。目录使用英文命名，先按测试用途划分，再按文件类型分类。

## 目录结构

```text
test-assets/
├── README.md
└── texture-atlas-layout/
    ├── README.md
    ├── psd/
    └── cmo3/
```

- `texture-atlas-layout/`：纹理图集当前页自动排版、缩放、旋转、并行和性能对照测试。
- 新增测试用途时，使用小写英文及连字符命名，例如 `mesh-editing/`；内部同样设置 `README.md`、`psd/` 和 `cmo3/`。
- 只放允许用于测试的文件副本，不移动或覆盖原始工作文件。
- 大型模型、PSD 及模型配套资源默认不提交 Git；说明文档可提交。
