"""Constants, paths, versions, and obfuscation variant tables for cubism_version_matrix."""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

from turboism_dev.paths import repo_root
from turboism_dev.versions import cubism_version_key

ROOT = repo_root()
version_key = cubism_version_key

def cubism_ref_dir() -> Path:
    """Locate cubism-ref/ — it lives in the primary checkout, not worktrees."""
    configured = os.environ.get("TURBOISM_CUBISM_REF")
    if configured:
        candidate = Path(configured).expanduser().resolve()
        if not candidate.is_dir():
            raise SystemExit("configured TURBOISM_CUBISM_REF is not a directory")
        return candidate
    local = ROOT / "cubism-ref"
    if local.is_dir():
        return local
    common = subprocess.run(
        ["git", "-C", str(ROOT), "rev-parse", "--git-common-dir"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    candidate = Path(common).resolve().parent / "cubism-ref"
    if candidate.is_dir():
        return candidate
    raise SystemExit(
        "cubism-ref not found in worktree or primary checkout; set TURBOISM_CUBISM_REF"
    )


REF_DIR = cubism_ref_dir()
VERIFICATION_DIR = ROOT / "build/generated/verification"
PACK_DIR = ROOT / "build/generated/draft/mapping-packs"
PROFILE_DIR = ROOT / "build/generated/draft/profiles"
GENERATE = ROOT / "scripts/generate_verification_sources.py"
DEPS_SOURCE = (
    ROOT / "runtime/src/main/java/dev/turboism/mapping/verification/"
    "CapabilitySelectorDependencies.java"
)

REVIEWED = ("5.2.03", "5.3.02", "5.3.03")
TARGET_VERSIONS = (
    "5.0.00",
    "5.0.01",
    "5.0.02",
    "5.0.03",
    "5.0.04",
    "5.0.05",
    "5.0.06",
    "5.1.00",
    "5.1.01",
    "5.1.02",
    "5.1.03",
    "5.2.00",
    "5.2.01",
    "5.2.02",
    "5.3.00",
    "5.3.01",
    "5.3.04",
)
VERIFIED_AT = "2026-09-27T00:00:00Z"
EDITOR_MODEL_SLICE = "adapter.editor-model.readwrite"

# Declared builds read from each exact jar's release declaration class
# (``com/live2d/cubism/h.class``; ``g.class`` on 5.0.x) via the same ldc/putstatic
# ``<clinit>`` scan CubismEditorReleaseDetector performs.
DECLARED_BUILDS = {
    "5.0.00": 500000005,
    "5.0.01": 500010000,
    "5.0.02": 500020000,
    "5.0.03": 500030001,
    "5.0.04": 500040000,
    "5.0.05": 500050001,
    "5.0.06": 500060001,
    "5.1.00": 501000004,
    "5.1.01": 501010000,
    "5.1.02": 501020001,
    "5.1.03": 501030000,
    "5.2.00": 502000003,
    "5.2.01": 502010000,
    "5.2.02": 502020000,
    "5.2.03": 502030002,
    "5.3.00": 503000005,
    "5.3.01": 503010000,
    "5.3.02": 503020001,
    "5.3.03": 503030001,
    "5.3.04": 503040001,
}

MODEL_BINDING = frozenset(
    {
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model.class",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value",
    }
)

# (alias, target version) -> selector field overrides. Some members keep their
# semantic contract but get a different obfuscated name on one exact version;
# the variant is seeded from the newest reviewed definition, re-verified on the
# target jar, and only adopted when the canonical definition fails.
MEMBER_VARIANTS = {
    ("object-context-menu.workspace.selector", "5.3.04"): {
        "memberName": "ap",
        "mappingId": "cubism.mapping.v5_3_04.object_context_menu.method.workspace_selector",
    },
    # 5.0.x main-frame view keeps the same anchor slot but binds it to the
    # Nizima page button (a plain CButton); the Home-dialog button only exists
    # from 5.1.00 on, where q() returns CIconButton.
    **{
        ("cubism.ui-main-toolbar.main-frame-view.home-button", version): {
            "descriptor": "()Lcom/live2d/ui/control/CButton;",
        }
        for version in (
            "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06"
        )
    },
    # GEditableMesh2#getGlIndices$default exists on every reviewed jar, but the
    # Kotlin default-args marker class is com/live2d/util/h/a on 5.0.00-5.0.02
    # (javap of the exact artifacts), com/live2d/util/i/a on 5.0.03-5.2.03 and
    # com/live2d/util/j/a from 5.3.00 on. Source triangulation needs the
    # default-indices read on the two oldest 5.0 patches too.
    **{
        ("cubism.editor-model.editable-mesh.gl-indices-default", version): {
            "descriptor": (
                "(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;"
                "Lcom/live2d/util/h/a;ZILjava/lang/Object;)[I"
            ),
        }
        for version in ("5.0.00", "5.0.01", "5.0.02")
    },
}


# Reviewed obfuscation renames on 5.0.x/5.1.x (bytecode evidence noted per
# cluster; every variant was checked against the exact target jar):
#
# - parameter palette operation class is ``k`` on 5.0.00-5.0.02 and ``m`` on
#   5.0.03-5.1.03 (``s`` on 5.2+); the operation instance surfaces through
#   ``CEPaletteCtrl_Param$ViewCtrlParam#b``.
# - the parameter validator accessor is ``c(op)`` returning ``a/i`` (5.0.00-02),
#   ``a/j`` (5.0.03-04) or ``a/k`` (5.0.05-06), and ``e(op)`` returning ``a/m``
#   on 5.1.x (``a/n`` on 5.2+); the property editor stays ``a/b`` via ``a(op)``.
# - the refresh Function0 lambda bound into ``a/b#a(...Function0)Z`` is ``aD``
#   (5.0.00-02), ``aG`` (5.0.03-06), ``aM`` (5.1.00-01) or ``aQ`` (5.1.02-03).
# - the parameter-group handler behind ``CParameterGroup#getHandler`` is ``f``
#   (5.0.x), ``g`` (5.1.00-01) or ``h`` (5.1.02-03).
# - ``ModelHandler``'s group move entry is ``a`` on 5.0/5.1 (``b`` on 5.2+;
#   bodies identical).
# - the atlas edit-layer container behind ``atlasEditor/a/a#a()`` is ``impl/O``
#   (5.0.00-04) or ``impl/Q`` (5.0.05-5.1.03) (``impl/S`` on 5.2+).
# - the eval flags class behind ``ICMvTrack_Instance#updateTrackInstance_testImpl``
#   is ``track/E`` on 5.0/5.1 (``track/F`` on 5.2+).
# - ``CEDockWrapper#updateWindowMenuItem`` is ``updatePaletteMenuCheck`` on
#   5.0.00-5.0.05 and the tab-popup append on ``ui/menu/k`` is ``b`` on 5.0.x
#   (``c`` on 5.1+; bodies identical).
V50_LO = ("5.0.00", "5.0.01", "5.0.02")
V50_MID = ("5.0.03", "5.0.04")
V50_HI = ("5.0.05", "5.0.06")
V50_ALL = V50_LO + V50_MID + V50_HI
V51_LO = ("5.1.00", "5.1.01")
V51_HI = ("5.1.02", "5.1.03")
V51_ALL = V51_LO + V51_HI
V50_51 = V50_ALL + V51_ALL

_OP_BY_VERSION = {
    **{v: "k" for v in V50_LO},
    **{v: "m" for v in V50_MID + V50_HI + V51_ALL},
}
_VALIDATOR_BY_VERSION = {
    **{v: ("c", "i") for v in V50_LO},
    **{v: ("c", "j") for v in V50_MID},
    **{v: ("c", "k") for v in V50_HI},
    **{v: ("e", "m") for v in V51_ALL},
}
_CALLBACK_BY_VERSION = {
    **{v: "aD" for v in V50_LO},
    **{v: "aG" for v in V50_MID + V50_HI},
    **{v: "aM" for v in V51_LO},
    **{v: "aQ" for v in V51_HI},
}
_GROUP_HANDLER_BY_VERSION = {
    **{v: "f" for v in V50_ALL},
    **{v: "g" for v in V51_LO},
    **{v: "h" for v in V51_HI},
}
_ATLAS_IMPL_BY_VERSION = {
    **{v: "O" for v in V50_LO + V50_MID},
    **{v: "Q" for v in V50_HI + V51_ALL},
}

_PARAM_OP = "com/live2d/cubism/view/palette/parameter"


def _editor_variants() -> dict:
    out = {}
    for version in V50_51:
        tag = version.replace(".", "_")
        op = _OP_BY_VERSION[version]
        accessor, validator = _VALIDATOR_BY_VERSION[version]
        callback = _CALLBACK_BY_VERSION[version]
        handler = _GROUP_HANDLER_BY_VERSION[version]
        impl = _ATLAS_IMPL_BY_VERSION[version]

        def add(alias, mapping, **overrides):
            out[(alias, version)] = {"mappingId": mapping, **overrides}

        add(
            "cubism.editor-model.parameter-operation.class",
            f"cubism.mapping.v{tag}.editor_model.class.parameter_operation",
            ownerInternalName=f"{_PARAM_OP}/{op}",
        )
        add(
            "cubism.editor-model.parameter-operation.property-editor",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_property_editor",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName="a",
            descriptor=f"(L{_PARAM_OP}/{op};)L{_PARAM_OP}/a/b;",
        )
        add(
            "cubism.editor-model.parameter-operation.validator",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_validator",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName=accessor,
            descriptor=f"(L{_PARAM_OP}/{op};)L{_PARAM_OP}/a/{validator};",
        )
        add(
            "cubism.editor-model.parameter-operation.refresh",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_refresh",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName="a",
            descriptor="(Z)V",
        )
        add(
            "cubism.editor-model.parameter-operation.rows",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_rows",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName="f",
            descriptor="()Lcom/live2d/type/CArrayList;",
        )
        add(
            "cubism.editor-model.parameter-operation.row-layout",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_row_layout",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName="b",
            descriptor="()Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;",
        )
        add(
            "cubism.editor-model.parameter-row-container.children",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_row_children",
            ownerInternalName="com/live2d/ui/container/CContainer",
            memberName="getChildren",
            descriptor="()Ljava/util/List;",
        )
        add(
            "cubism.editor-model.parameter-group-row.layout",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_group_row_layout",
            ownerInternalName="com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget",
            memberName="getRowLayout",
            descriptor="()Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;",
        )
        add(
            'cubism.editor-model.parameter-single-row.slider',
            f"cubism.mapping.v{tag}.editor_model.method.parameter_single_row_slider",
            ownerInternalName='com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget',
            memberName='getSliderFloat',
            descriptor='()Lcom/live2d/ui/control/CSlidableFloat;',
            requiredAccessFlags=1,
            forbiddenAccessFlags=8,
        )
        add(
            'cubism.editor-model.parameter-single-row.paint',
            f"cubism.mapping.v{tag}.editor_model.field.parameter_single_row_paint",
            ownerInternalName='com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget',
            memberName='paintComp',
            descriptor='Lcom/live2d/ui/control/CCustomPaintComponent;',
            requiredAccessFlags=2,
            forbiddenAccessFlags=8,
        )
        add(
            'cubism.editor-model.parameter-double-row.slider',
            f"cubism.mapping.v{tag}.editor_model.method.parameter_double_row_slider",
            ownerInternalName='com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget',
            memberName='getSliderFloat',
            descriptor='()Lcom/live2d/ui/control/CSlidableFloat;',
            requiredAccessFlags=1,
            forbiddenAccessFlags=8,
        )
        add(
            'cubism.editor-model.parameter-double-row.paint',
            f"cubism.mapping.v{tag}.editor_model.field.parameter_double_row_paint",
            ownerInternalName='com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget',
            memberName='paintComp',
            descriptor='Lcom/live2d/ui/control/CCustomPaintComponent;',
            requiredAccessFlags=2,
            forbiddenAccessFlags=8,
        )
        add(
            'cubism.editor-model.parameter-double-row.secondary-slider',
            f"cubism.mapping.v{tag}.editor_model.method.parameter_double_row_secondary_slider",
            ownerInternalName='com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget',
            memberName='getSliderFloatSec',
            descriptor='()Lcom/live2d/ui/control/CSlidableFloat;',
            requiredAccessFlags=1,
            forbiddenAccessFlags=8,
        )
        add(
            "cubism.editor-model.parameter-operation.set-value",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_set_value",
            ownerInternalName=f"{_PARAM_OP}/{op}",
            memberName="a",
            descriptor="(Lcom/live2d/cubism/doc/model/param/CParameterSource;F)V",
        )
        add(
            "cubism.editor-model.parameter-palette-view.operation",
            f"cubism.mapping.v{tag}.editor_model.method.parameter_operation",
            memberName="b",
            descriptor=f"()L{_PARAM_OP}/{op};",
        )
        add(
            "cubism.editor-model.parameter-refresh-callback.create",
            f"cubism.mapping.v{tag}.editor_model.constructor.parameter_refresh_callback",
            ownerInternalName=f"{_PARAM_OP}/{callback}",
            memberName="<init>",
            descriptor=f"(L{_PARAM_OP}/{op};)V",
        )
        add(
            "cubism.editor-model.parameter-validator.class",
            f"cubism.mapping.v{tag}.editor_model.class.parameter_validator",
            ownerInternalName=f"{_PARAM_OP}/a/{validator}",
        )
        for alias, member, desc, mid in (
            (
                "parameter-validator.allow-repeat",
                "a",
                "(Lcom/live2d/cubism/doc/model/param/CParameterSource;Z)Z",
                "parameter_allow_repeat",
            ),
            (
                "parameter-validator.default-change-affects-morph-target",
                "a",
                "(Lcom/live2d/cubism/doc/model/param/CParameterSource;F)Z",
                "parameter_default_affects_morph_target",
            ),
            (
                "parameter-validator.keys-outside-range",
                "a",
                "(Lcom/live2d/type/CParameterGuid;FF)Z",
                "parameter_keys_outside_range",
            ),
            (
                "parameter-validator.reject-type-change",
                "a",
                "(Lcom/live2d/cubism/doc/model/param/CParameterSource;ZZ)Z",
                "parameter_reject_type_change",
            ),
            (
                "parameter-validator.supports-type",
                "a",
                "(Z)Z",
                "parameter_supports_type",
            ),
            (
                "parameter-validator.valid-id",
                "b",
                "(Ljava/lang/String;)Z",
                "parameter_valid_id",
            ),
        ):
            add(
                f"cubism.editor-model.{alias}",
                f"cubism.mapping.v{tag}.editor_model.method.{mid}",
                ownerInternalName=f"{_PARAM_OP}/a/{validator}",
                memberName=member,
                descriptor=desc,
            )
        add(
            "cubism.editor-model.parameter-group-handler.class",
            f"cubism.mapping.v{tag}.editor_model.class.parameter-group-handler_class",
            ownerInternalName=f"com/live2d/cubism/doc/model/param/group/{handler}",
        )
        for alias, desc, mid in (
            (
                "parameter-group-handler.add-group-child",
                "(Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;I)Lcom/live2d/undo/ACUndoable;",
                "parameter-group-handler_add-group-child",
            ),
            (
                "parameter-group-handler.add-parameter-child",
                "(Lcom/live2d/cubism/doc/model/param/CParameterSource;I)Lcom/live2d/undo/ACUndoable;",
                "parameter-group-handler_add-parameter-child",
            ),
            (
                "parameter-group-handler.remove-descendant",
                "(Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;Lcom/live2d/cubism/doc/model/param/CParameterSet;ZZ)Lcom/live2d/undo/ACUndoable;",
                "parameter-group-handler_remove-descendant",
            ),
        ):
            add(
                f"cubism.editor-model.{alias}",
                f"cubism.mapping.v{tag}.editor_model.method.{mid}",
                ownerInternalName=f"com/live2d/cubism/doc/model/param/group/{handler}",
                memberName="a",
                descriptor=desc,
            )
        add(
            "cubism.editor-model.parameter-group.handler",
            f"cubism.mapping.v{tag}.editor_model.method.parameter-group_handler",
            memberName="getHandler",
            descriptor=f"()Lcom/live2d/cubism/doc/model/param/group/{handler};",
        )
        add(
            "cubism.editor-model.model-handler.move-parameter",
            f"cubism.mapping.v{tag}.editor_model.method.model-handler_move-parameter",
            memberName="a",
        )
        add(
            "cubism.texture-atlas.native.data.impl",
            f"cubism.mapping.v{tag}.editor_model.method.native_data_impl",
            memberName="a",
            descriptor=f"()Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/impl/{impl};",
        )
        add(
            "cubism.texture-atlas.native.impl.container",
            f"cubism.mapping.v{tag}.editor_model.method.native_impl_container",
            ownerInternalName=f"com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/{impl}",
            memberName="b",
            descriptor="()Ljp/noids/design/layer/core/ContainerLayer;",
        )
        add(
            "cubism.editor-model.eval-flags.create",
            f"cubism.mapping.v{tag}.editor_model.constructor.eval_flags_create",
            ownerInternalName="com/live2d/cubism/doc/animation/movie/track/E",
            memberName="<init>",
            descriptor="(Z)V",
        )
        add(
            "cubism.editor-model.track-instance.update",
            f"cubism.mapping.v{tag}.editor_model.method.track_instance_update",
            memberName="updateTrackInstance_testImpl",
            descriptor="(Lcom/live2d/cubism/view/context/CEViewContext;Lcom/live2d/cubism/doc/animation/movie/core/a;Lcom/live2d/cubism/doc/animation/movie/track/E;)V",
        )
    return out


MEMBER_VARIANTS.update(_editor_variants())

# 5.0.x dock/menu renames: updatePaletteMenuCheck (5.0.00-05) is the
# updateWindowMenuItem body, and the tab-popup append is b (c on 5.1+).
for _v in ("5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05"):
    MEMBER_VARIANTS[("cubism.ui-panel.dock.update-window-menu", _v)] = {
        "memberName": "updatePaletteMenuCheck",
        "mappingId": "cubism.mapping.v{}.ui_embedded_panel.dock_update_window_menu".format(
            _v.replace(".", "_")
        ),
    }
for _v in V50_ALL:
    MEMBER_VARIANTS[("cubism.ui-panel.dock-tab-popup.menu-append", _v)] = {
        "memberName": "b",
        "mappingId": "cubism.mapping.v{}.ui_embedded_panel.menu_append".format(
            _v.replace(".", "_")
        ),
    }
del _v




ALL_VERSIONS = tuple(
    sorted(
        TARGET_VERSIONS + REVIEWED, key=cubism_version_key
    )
)
CATALOG_SLICES = (
    "autobackup",
    "clipmask",
    "core-model-read",
    "editor-model",
    "performance-render-scene",
    "project-workspace",
    "ui-bounding-box-overlay",
    "ui-control-appearance",
    "ui-embedded-panel",
    "ui-main-toolbar",
    "ui-status-bar",
    "ui-top-menu",
    "workspace-control",
)
# Record filename suffix -> CubismSliceCatalog slice id (identical today, kept
# explicit so a future rename can never silently misroute candidates).
SLICE_OF = {name: name for name in CATALOG_SLICES}

FAMILY_JSON = ROOT / "scripts/verification-sources/families"
TEMPLATE_DIR = (
    ROOT / "scripts/verification-sources/templates/dev/turboism/mapping/verification"
)

TYPE_TOKEN = re.compile(r"L([^;]+);")
MAPPING_ID_VERSION = re.compile(r"(cubism\.mapping\.)v([0-9_]+)\.")
SELECTOR_IDENTITY = (
    "alias",
    "kind",
    "ownerInternalName",
    "memberName",
    "descriptor",
    "requiredAccessFlags",
    "forbiddenAccessFlags",
)
