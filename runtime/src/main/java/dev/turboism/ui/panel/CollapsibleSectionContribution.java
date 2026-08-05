package dev.turboism.ui.panel;

import javax.swing.JPanel;

/**
 * 框架侧可折叠分区注册描述：一个带标题、可收起/展开的内容区域。
 * 供 {@link CollapsibleSectionRegistry} 消费。
 */
public interface CollapsibleSectionContribution {

    /** 唯一 id；重复注册会被拒绝。 */
    String id();

    /** 分区标题。 */
    String title();

    /** 初始是否展开。 */
    boolean expandedByDefault();

    /** 分区内容面板（注册时会作为 CollapsibleSection 的内容放入）。 */
    JPanel content();
}
