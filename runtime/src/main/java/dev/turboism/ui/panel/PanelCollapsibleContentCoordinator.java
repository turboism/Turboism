package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内容表协调器（方案 B）：维护每个目标 panel 一张注入分区内容表，渲染时把注入分区
 * 合成进既有 content，并记录每个注入分区的贡献者 pluginId 供按钮 action 路由。
 *
 * <p>A 与 B 是同一张内容表的两个入口：A = 插件在 {@code EmbeddedPanelContribution.content}
 * 里声明的分区（渲染器已支持）；B = {@code contributeCollapsibleSection} 运行时注入的分区。
 * 合成规则：A 分区永远保持在 content 中的声明位置，B 注入分区追加在后，B 之间按
 * {@code order} 升序、同 order 按 {@code sectionId} 字典序，与插件加载顺序无关。</p>
 *
 * <p>注入分区里的 {@link PanelView.Button} 必须路由到贡献者 pluginId 的 action registry，
 * 而不是目标 panel（core）的 registry。{@link #actionOwners(EmbeddedPanelId)} 递归遍历
 * 注入分区内容收集 {@code actionId → pluginId}。按钮 actionId 全局唯一（ActionRegistry
 * 按 actionId 注册）；冲突时后注册的覆盖先注册的（插入序 map，冲突仅在约定被破坏时发生）。</p>
 *
 * <p>目标 panel 注册状态由宿主安装生命周期显式驱动（无现成事件 API）：宿主
 * {@code install} 成功时调用 {@link #onPanelRegistered(EmbeddedPanelId)}，面板句柄
 * {@code close} 时调用 {@link #onPanelRemoved(EmbeddedPanelId)}。目标未注册时注入
 * 分区保持 pending（仍登记在内容表），注册后自动落位；注销后回到 pending，不丢不重。</p>
 *
 * <p>线程模型：synchronized 守卫内部状态（EDT 使用约定，调用方负责 EDT 调度）。</p>
 */
public final class PanelCollapsibleContentCoordinator {

    /**
     * 进程级共享实例：runtime 服务（per-plugin）与宿主 install 渲染之间没有其他
     * 接线点，注入表必须跨插件可见。
     */
    public static PanelCollapsibleContentCoordinator shared() {
        return SHARED;
    }

    private static final PanelCollapsibleContentCoordinator SHARED =
        new PanelCollapsibleContentCoordinator();

    /** 注入条目的注册归属：贡献者 pluginId + 注入贡献本身。 */
    private record OwnerSection(String pluginId, CollapsibleSectionContribution contribution) { }

    private final Object monitor = new Object();
    private final Set<EmbeddedPanelId> registeredPanels = new HashSet<>();
    /** 内容表：targetPanelId → sectionId → (pluginId, 注入贡献)。插入序 map：冲突时后注册覆盖。 */
    private final Map<EmbeddedPanelId, Map<String, OwnerSection>> sections =
        new HashMap<>();

    /**
     * 在内容表中登记一个注入分区（B 入口）。目标 panel 尚未注册时条目保持 pending，
     * 待宿主安装后自动落位。
     *
     * @param pluginId 贡献者插件 id；注入分区内按钮的 action 按此 id 路由回贡献者 registry
     * @param contribution 注入贡献，自带目标 panel、sectionId、order 与内容
     * @return 注销句柄；幂等，且只会移除本次注册自己的条目（同 sectionId 被他人覆盖时不误删）
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalStateException 同一目标 panel 上该 sectionId 已被登记
     */
    public Registration register(
        final String pluginId,
        final CollapsibleSectionContribution contribution
    ) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contribution, "contribution");
        final EmbeddedPanelId target = contribution.targetPanelId();
        final String sectionId = contribution.sectionId();
        synchronized (monitor) {
            final Map<String, OwnerSection> panelSections =
                sections.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
            if (panelSections.containsKey(sectionId)) {
                throw new IllegalStateException(
                    "collapsible section " + sectionId + " is already registered for panel \""
                        + target.value());
            }
            panelSections.put(sectionId, new OwnerSection(pluginId, contribution));
            return () -> unregister(target, sectionId, contribution);
        }
    }

    /**
     * 目标 panel 已由宿主安装。幂等；仅影响 pending/落位状态，不改变内容表。
     */
    public void onPanelRegistered(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            registeredPanels.add(panelId);
        }
    }

    /**
     * 目标 panel 已从宿主移除；注入分区回到 pending，内容表保留（不丢不重）。
     */
    public void onPanelRemoved(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            registeredPanels.remove(panelId);
        }
    }

    /** 目标 panel 当前是否已注册（pending 状态 = 未注册但内容表已登记）。 */
    public boolean isRegistered(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            return registeredPanels.contains(panelId);
        }
    }

    /**
     * 注入分区视图：order 升序、同 order 按 sectionId 字典序；每个注入分区渲染为
     * {@link PanelView.CollapsibleSection} 节点（title/expandedByDefault + 内容）。
     */
    public List<PanelView> injectedSections(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        final List<OwnerSection> snapshot;
        synchronized (monitor) {
            final Map<String, OwnerSection> panelSections = sections.get(panelId);
            if (panelSections == null) {
                return List.of();
            }
            snapshot = new ArrayList<>(panelSections.values());
        }
        snapshot.sort(Comparator
            .comparingInt((OwnerSection owner) -> owner.contribution().order())
            .thenComparing(owner -> owner.contribution().sectionId()));
        return snapshot.stream()
            .map(owner -> (PanelView) PanelView.collapsibleSection(
                owner.contribution().title(),
                owner.contribution().expandedByDefault(),
                owner.contribution().content()))
            .toList();
    }

    /**
     * 渲染合成：content 为 Column → 尾部追加注入分区；非 Column → 包一层
     * column(content, 注入分区...)。无注入时原样返回 content。
     */
    public PanelView merge(final EmbeddedPanelId panelId, final PanelView content) {
        Objects.requireNonNull(panelId, "panelId");
        Objects.requireNonNull(content, "content");
        final List<PanelView> injected = injectedSections(panelId);
        if (injected.isEmpty()) {
            return content;
        }
        final List<PanelView> children = new ArrayList<>(injected.size() + 2);
        if (content instanceof PanelView.Column column) {
            children.addAll(column.children());
        } else {
            children.add(content);
        }
        children.addAll(injected);
        return new PanelView.Column(children);
    }

    /**
     * 该面板全部注入分区内的按钮 actionId → 贡献者 pluginId 映射；递归遍历
     * Column/Row/CollapsibleSection 内容树收集 {@link PanelView.Button}。
     * 无注入按钮时返回空映射。
     */
    public Map<String, String> actionOwners(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        final List<OwnerSection> snapshot;
        synchronized (monitor) {
            final Map<String, OwnerSection> panelSections = sections.get(panelId);
            if (panelSections == null) {
                return Map.of();
            }
            snapshot = new ArrayList<>(panelSections.values());
        }
        final Map<String, String> owners = new HashMap<>();
        for (OwnerSection owner : snapshot) {
            collectButtonOwners(owner.contribution().content(), owner.pluginId(), owners);
        }
        return owners;
    }

    private static void collectButtonOwners(
        final PanelView view,
        final String pluginId,
        final Map<String, String> owners
    ) {
        if (view instanceof PanelView.Button button) {
            owners.put(button.actionId(), pluginId);
        } else if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        } else if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        } else if (view instanceof PanelView.CollapsibleSection section) {
            for (PanelView child : section.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        }
    }

    private void unregister(
        final EmbeddedPanelId target,
        final String sectionId,
        final CollapsibleSectionContribution contribution
    ) {
        synchronized (monitor) {
            final Map<String, OwnerSection> panelSections = sections.get(target);
            if (panelSections == null
                || panelSections.get(sectionId) == null
                || panelSections.get(sectionId).contribution() != contribution) {
                return; // 幂等：仅移除本注册自己的条目
            }
            panelSections.remove(sectionId);
            if (panelSections.isEmpty()) {
                sections.remove(target);
            }
        }
    }
}
