package dev.turboism.adapter.cubism.profile;

/**
 * Host UI widget class names the auto-mesh selector row instantiates.
 *
 * <p>The names are identical across every reviewed host release; the row reflects them
 * through the host class loader, so the values stay pinned here instead of spreading
 * across feature code.</p>
 */
public final class CubismAutoMeshUiProfile {
    private CubismAutoMeshUiProfile() {}

    public static final String LABEL_TYPE = "com.live2d.ui.control.CLabel";
    public static final String COMBO_TYPE = "com.live2d.ui.control.CComboBox";
    public static final String BUTTON_TYPE = "com.live2d.ui.control.CButton";
    public static final String ROW_TYPE = "com.live2d.ui.container.CHBox";
    public static final String SPACER_TYPE = "com.live2d.ui.container.CSpacer";
    public static final String CONTAINER_TYPE = "com.live2d.ui.container.CContainer";
    public static final String WIDGET_TYPE = "com.live2d.ui.CWidget";
    public static final String VBOX_TYPE = "com.live2d.ui.container.CVBox";
    public static final String TITLED_BORDER_TYPE = "com.live2d.ui.border.CTitledBorder";
    public static final String BORDER_TYPE = "com.live2d.ui.border.CBorder";
    public static final String DLG_BUTTON_TYPE = "com.live2d.ui.control.CDlgButton";
    public static final String BUTTON_MENU_TYPE = "com.live2d.ui.menu.CButtonMenu";
    public static final String MENU_ITEM_TYPE = "com.live2d.ui.menu.CMenuItem";
    public static final String POPUP_MENU_TYPE = "com.live2d.ui.menu.k";
    public static final String SLIDABLE_INT_TYPE = "com.live2d.ui.control.CSlidableInt";
    public static final String SLIDABLE_FLOAT_TYPE = "com.live2d.ui.control.CSlidableFloat";
    public static final String CHECK_BOX_TYPE = "com.live2d.ui.control.CCheckBox";
    public static final String TEXT_FIELD_TYPE = "com.live2d.ui.control.CTextField";
    public static final String INSETS_TYPE = "com.live2d.type.CInsets";
    public static final String GROW_TYPE = "com.live2d.ui.container.a.c";
    public static final String ALIGNMENT_TYPE = "com.live2d.ui.a.a";

    /**
     * Container classes whose own live-update route re-places the selected ArtMeshes.
     *
     * <p>Each container declares one static entry that resolves the current Editor selection and
     * calls the guarded native funnel. The selector row calls that entry after it stores a change,
     * so a plugin selection behaves like the Editor's own modes.</p>
     */
    public static final String MODELING_PANEL_TYPE =
            "com.live2d.cubism.doc.model.extension.meshGenerator.ui.AutoMeshGenerate_DialogType$AutoGenerateMeshPanel";

    public static final String MODELING_POPUP_TYPE =
            "com.live2d.cubism.doc.model.extension.meshGenerator.ui.AutoMeshGenerate_PopupType$AutoGenerateMeshDialog";

    public static final String MESH_EDITOR_POPUP_TYPE =
            "com.live2d.cubism.doc.model.extension.meshGenerator.ui.AutoMeshGenerate_PopupType_InMeshEditor$AutoGenerateMeshDialog";

    /** Event type the live-update entries pass to the guarded funnel. */
    public static final String EVENT_TYPE = "com.live2d.ui.event.z";

    /** Direct-update event instance the live-update entries read from {@link #EVENT_TYPE}. */
    public static final String EVENT_DIRECT_UPDATE_FIELD = "d";

    /** Undo group type of the modeling-mode live-update entry on builds that carry it. */
    public static final String GROUP_UNDO_TYPE = "com.live2d.undo.GroupUndo";

    /** Completion callback type of the modeling-mode live-update entry on builds that carry it. */
    public static final String FUNCTION0_TYPE = "kotlin.jvm.functions.Function0";

    /** Inspector event type the modeling popup's live-update entry consumes. */
    public static final String INSPECTOR_EVENT_TYPE = "com.live2d.inspector.e";
}
