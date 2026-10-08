# T105 最终点向量复用工件实机预检

仅一次5303 FIFO任务，不重试、不替换；这是最终工件启动与受控输出预检，不是正式性能验收。使用最终T104生产工件678bb3fd4d9810ea7ec3171ebd2db4107a70648741836ce356da57c7ac2e5b80，包含既有native mesh组件与新增point局部复用；唯一canonical premain，无owned probes/constructor counters，不把局部overlay测试升级为最终包宿主证据。

沿用T095的受控画布输入插件b497、heavy模型、711来源、三轮、配置及设置只读探针。每轮实际画布中心无按钮MOUSE_MOVED，六条原字段输入快照；禁止attach，一份profile JFR与一项GC日志，不强制GC。按共享FIFO等待，用户窗口由用户正常关闭，不操作其他窗口。单次1200秒任务，失败保留停止，不补跑。

标准身份、模型不变、配置/真实渲染startup、最终部署SHA、正常退出/kernel安全清理全部必须通过。runtime必须有point PATCHED及point/edge OWNED_FINAL_DEFINITION_MATCH准入。2133有序完整输出与T092受控T057比较零差异，六条非空输入与六个独立CPU单位、sameJFR命令task/EDT/30字段绑定、每轮完整缓存刷新链必须审核。旧未受控参考378摘要/18数量FAIL保留，不以旧参考差异代替受控比较。CPU与耗时仅诊断记录，不授予性能验收。

前置T104最终三SDK12生命周期/callback场景、60 focused tests、devCheck及最终包审查通过；历史63场景只代表旧快照，cold logger red失败保留。T057交付不变，T088/T089/T097失败、T100旧门槛UNPROVEN保持。通过本预检后再冻结新候选正式受控A/B门槛：CPU回退≤0%、wall严格改善、配对peak RSS增长≤20%、自身基线peak增长≤80MiB、retained3−1≤64MiB；三版本/长期/LaneC仍开放。
