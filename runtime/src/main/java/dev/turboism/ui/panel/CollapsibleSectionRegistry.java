package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;

/**
 * 「注册自己的可折叠区域、往区域里放自己的控件」的框架侧注册接口。
 * 遵循框架惯例：注册句柄为 {@link Registration}（close 撤销注册）、
 * 重复 id 拒绝（IllegalStateException）、插入序排列。
 */
public interface CollapsibleSectionRegistry {

    /**
     * 注册一个可折叠分区；分区按注册顺序纵向堆叠。
     *
     * @param contribution 分区描述，非 null
     * @return 注册句柄；{@code close()} 幂等移除该分区
     * @throws NullPointerException  contribution 为 null
     * @throws IllegalStateException id 重复
     */
    Registration register(CollapsibleSectionContribution contribution);

    /** 已注册分区 id，按插入序。 */
    List<String> sectionIds();

    /**
     * 分区当前是否展开。
     *
     * @throws IllegalArgumentException 未知 id
     */
    boolean isExpanded(String sectionId);

    /**
     * 设置分区展开状态。
     *
     * @throws IllegalArgumentException 未知 id
     */
    void setExpanded(String sectionId, boolean expanded);
}
