package com.live2d.ui.treeTable;

/**
 * Test fixture mirroring the exact generic tree-table node class pinned by the
 * {@code ui-control-appearance} verification records (alias
 * {@code cubism.ui-control-appearance.part.node-source}): Cubism 5.2.03 exposes {@code h()}
 * and 5.3.02 exposes {@code i()} as the node→source accessor {@code ()Ljava/lang/Object;}.
 * See {@code cubism-ref/verification/cubism-5.2.03-ui-control-appearance.json} (mappingId
 * {@code cubism.mapping.5_2.ui_control_appearance.method.node_source}) and
 * {@code cubism-ref/verification/cubism-5.3.02-ui-control-appearance.json} (mappingId
 * {@code cubism.mapping.5_3_02.ui_control_appearance.method.node_source}).
 */
public final class c {

    private final Object source;

    public c(final Object source) {
        this.source = source;
    }

    /** 5.2.03 node→source accessor. */
    public Object h() {
        return source;
    }

    /** 5.3.02 node→source accessor. */
    public Object i() {
        return source;
    }
}
