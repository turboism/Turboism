"""Constants, paths, regular expressions, and static mappings for compatibility matrix."""
from __future__ import annotations

import re
from pathlib import Path

from turboism_dev.paths import repo_root

LANGUAGES = ("en", "zh", "ja", "ko")

GENERATOR_VERSION = 1
MATRIX_FORMAT = "turboism.compatibility-matrix/v1"

REPO_ROOT = repo_root()

VERIFICATION_DIR = Path("build/generated/verification")
FAMILIES_DIR = Path("scripts/verification-sources/families")
MATRIX_JSON = Path("compatibility/cubism/compatibility-matrix.json")
DOCS_DIR = Path("compatibility/cubism")
REVIEWED_HOST_ARTIFACTS = Path(
    "compatibility/cubism/reviewed-releases.json"
)
SDK_MAIN = Path("sdk/src/main/java")
RUNTIME_MAIN = Path("runtime/src/main/java")
TEMPLATES_DIR = Path("scripts/verification-sources/templates")
COMMAND_ENUMS = (
    "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java",
    "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorParameterizedCommand.java",
    "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommand.java",
)
INTERCEPTOR = Path(
    "runtime/src/main/java/dev/turboism/core/plugin/context/"
    "CubismEditorApiAvailabilityInterceptor.java"
)
CLASS_PINS_DIR = Path("runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins")
CAMPAIGN_JOBS = Path("validation/cubism-version-matrix/campaign-jobs.json")
CAMPAIGN_INVENTORY = Path("validation/cubism-version-matrix/inventory.json")
MONOTONICITY_EXCEPTIONS = Path("scripts/version-monotonicity-exceptions.json")
VERSION_BOUNDARIES = Path("compatibility/cubism/version-boundaries.json")
OFFICIAL_TIMELINE = Path("compatibility/cubism/official-feature-timeline.json")
PLUGIN_DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")

BLOCK_BEGIN = "<!-- compatibility:begin -->"
BLOCK_END = "<!-- compatibility:end -->"

README_HEADINGS = {
    "README.md": "## Requirements and compatibility",
    "README_zh.md": "## 要求与兼容性",
    "README_ja.md": "## 要件と互換性",
}
README_LOCALE = {"README.md": "en", "README_zh.md": "zh", "README_ja.md": "ja"}
DOC_SUFFIX = {"en": "", "zh": "_zh", "ja": "_ja", "ko": "_ko"}

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
ANNOTATION_RE = re.compile(
    r"@(?:dev\.turboism\.sdk\.)?CubismEditor\b(?:\s*\((.*?)\))?", re.DOTALL
)
QUOTED_VERSIONS_RE = re.compile(r'"(\d+\.\d+\.\d+)"')
IMPORT_RE = re.compile(r"^import\s+([\w.]+)\s*;", re.MULTILINE)
SDK_IMPORT_RE = re.compile(
    r"^import\s+(dev\.turboism\.sdk\.[\w.]+)\s*;", re.MULTILINE
)
FQCN_REF_RE = re.compile(r"\bdev\.turboism\.sdk\.[A-Z][\w.]*")
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*;", re.MULTILINE)
CAPABILITY_CONSTANT_RE = re.compile(
    r'\b\w*CAPABILITY\w*\s*=\s*"((?:cubism|ui|turboism)\.[\w.-]+)"'
)
SUPPORTED_SET_RE = re.compile(
    r"\b(\w*SUPPORTED\w*_VERSIONS)\s*=\s*Set\.of\(([^)]*)\)"
)
CLASS_PIN_LOAD_RE = re.compile(r'ClassPinTable\.load\("([\w.-]+)"\)')
ENUM_ENTRY_RE = re.compile(
    r"^ {4}([A-Z][A-Z0-9_]*)\s*\((.*?)\)\s*[,;]", re.MULTILINE | re.DOTALL
)
TYPE_DECL_RE = re.compile(
    r"(?:public|protected|private|final|abstract|static|sealed|strictfp|\s)+"
    r"(?:class|interface|enum|@interface)\s+(\w+)"
)
MEMBER_RE = re.compile(
    r"(?:@[\w.]+(?:\([^)]*\))?\s*)*"
    r"(?:public|protected|private|final|abstract|static|default|synchronized|"
    r"strictfp|<[\w,\s?]+>|\s)*"
    r"([\w.$]+(?:<[\w.<>\[\],?\s]*>)?(?:\[\])?)\s+(\w+)\s*\("
)

# Declared plugin capability ids whose tokens do not resolve to verification
# capabilityIds on their own. Values are name mappings only: the associated
# version sets are always re-derived from records, contracts and pin tables.
DECLARED_CAPABILITY_ALIASES: dict[str, dict[str, list[str]]] = {
    "cubism.deformer.read": {
        "capabilities": [
            "cubism.editor-model.edit.deformer.get-deformer-structure",
            "cubism.editor-model.part-tree.read",
        ]
    },
    "cubism.deformer.warp-mirror": {"capabilities": ["cubism.editor-model.warp-mirror"]},
    "cubism.deformer.alt-axis-mirror": {
        "capabilities": ["cubism.editor-model.warp-mirror"],
        "pins": ["warp-alt-mirror"],
    },
    "cubism.editor-commands.execute": {"capabilities": ["cubism.editor-ui.top-menu"]},
    "cubism.history.read": {"capabilities": ["cubism.editor-history.read"]},
    "cubism.history.write": {"capabilities": ["cubism.editor-history.move"]},
    "cubism.model.objects.read": {"capabilities": ["cubism.editor-model.objects.read"]},
    "cubism.model.objects.write": {
        "capabilities": ["cubism.editor-model.object-hierarchy.edit"]
    },
    "cubism.model.read": {"capabilities": ["cubism.editor-model.read"]},
    "cubism.model.write": {"capabilities": ["cubism.editor-model.write"]},
    "cubism.parameters.read": {
        "capabilities": [
            "cubism.editor-model.edit.parameter-structure.get-parameter-structure",
            "cubism.editor-model.parameter-groups.read",
        ]
    },
    "cubism.parameters.write": {
        "capabilities": [
            "cubism.editor-model.write",
            "cubism.editor-model.parameter-structure.write",
        ]
    },
    "cubism.parameter.read": {
        "capabilities": [
            "cubism.editor-model.edit.parameter-structure.get-parameter-structure",
            "cubism.editor-model.parameter-groups.read",
        ]
    },
    "cubism.parameter.write": {"capabilities": ["cubism.editor-model.write"]},
    "cubism.parameter.bindings.invert": {
        "capabilities": ["cubism.editor-model.parameter-bindings.invert"]
    },
    "cubism.parameter.bindings.transfer": {
        "capabilities": ["cubism.editor-model.parameter-bindings.transfer"]
    },
    "cubism.parameter-bindings.read": {
        "capabilities": ["cubism.editor-model.parameter-bindings.read"]
    },
    "cubism.parameter-bindings.write": {
        "capabilities": [
            "cubism.editor-model.art-mesh.parameter-bindings.write",
            "cubism.editor-model.warp-deformer.parameter-bindings.write",
            "cubism.editor-model.rotation-deformer.parameter-bindings.write",
        ]
    },
    "cubism.mesh.read": {"capabilities": ["cubism.geometry.read"]},
    "cubism.mesh.custom-tools": {
        "capabilities": ["cubism.mesh-tool.session", "ui.mesh-toolbar.contribute"]
    },
    "cubism.modeling.custom-tools": {
        "capabilities": ["cubism.modeling-tool.selection-brush"]
    },
    "cubism.mesh.mirror-axis-angle": {"pins": ["mesh-mirror"]},
    "cubism.psd.external-edit": {
        "capabilities": ["cubism.editor-model.psd.raw-image.replace"]
    },
    "cubism.psd.layer-relationship.read": {
        "capabilities": ["cubism.editor-model.psd.snapshot.read"]
    },
    "cubism.clipmask.replace-ordered-sources": {
        "capabilities": ["cubism.editor-model.clip-mask.write"]
    },
    "cubism.transaction.real-write-undo": {
        "capabilities": ["cubism.editor-model.edit.session.edit-begin"]
    },
    "cubism.texture-atlas.layout": {
        "capabilities": ["cubism.texture-atlas.layout.write"]
    },
    "cubism.texture-atlas.statistics": {
        "capabilities": ["cubism.editor-model.statistics.read"]
    },
    "ui.embedded-panel.contribute": {"capabilities": ["cubism.editor-ui.embedded-panel"]},
    "ui.menu.contribute": {"capabilities": ["cubism.editor-ui.top-menu"]},
    "ui.context-menu.contribute": {
        "capabilities": ["ui.object-context-menu.contribute"]
    },
    "ui.context-source.read": {"capabilities": ["ui.canvas.hint"]},
    "ui.mesh-edit.mirror-axis-angle": {"capabilities": ["ui.mesh-toolbar.contribute"]},
    "performance.stats.read": {"capabilities": ["turboism.performance.stats.read"]},
}

# Declared capability ids in these namespaces describe host/UI plumbing rather
# than the plugin's feature itself; they never decide the supported range,
# only the per-version feature deltas.
NON_FUNCTIONAL_PREFIXES = ("ui.", "file.", "action.", "mcp.", "automation.")

# Campaign task base names -> plugin directory (name mapping only).
TASK_PLUGIN_ALIASES = {
    "clipmask-viewer": "clipmask-viewer",
    "mcp": "mcp",
    "parameter": "parameter",
    "parameter-batch-transfer": "parameter-batch-transfer",
    "selection-brush": "selection-brush",
    "warp-deformer": "warp-deformer-alt-symmetry",
    "warp-deformer-alt-symmetry": "warp-deformer-alt-symmetry",
    "boundingbox-warp-mirror": "boundingbox-warp-mirror",
    "recent-preview": "recent-preview",
    "protected-export": "protected-export",
    "theme": "ui-theme",
    "backup": "webdav-backup",
    "backup-webdav": "webdav-backup",
    "fps": "perf-stats",
    "psd-clip-mask": "psd-clip-mask-import",
    "atlas": "atlas-maxrects-bssf",
    "atlas-nesting": "atlas-bitmask-nesting",
    "atlas-polygon": "atlas-dalsoo-polygon",
    "external-psd-edit-pipeline": "external-psd-edit",
    "external-psd-fixture-preparation": "external-psd-edit",
    "semantic-history-native-ui": "history-panel",
    "semantic-history-primary": "history-panel",
    "selection-brush-manual": "selection-brush",
    "selection-brush-coordinate-manual": "selection-brush",
    "selection-brush-round-manual": "selection-brush",
    "boundingbox-warp-mirror-manual": "boundingbox-warp-mirror",
    "acp": "acp",
}

# Campaign task base names -> capability group key for the developer matrix
# real-host table (name mapping only).
TASK_GROUP_ALIASES = {
    "core-acquisition": "core",
    "core-owned-moc": "core",
    "clipmask-viewer": "clipmask",
    "psd-clip-mask": "clipmask",
    "startup-suppression": "startup",
    "edit": "edit-session",
    "workspace": "workspace",
    "parameter": "parameter",
    "parameter-batch-transfer": "parameter",
    "animation-timeline": "animation",
    "texture-relations": "texture",
    "psd-export-observation": "psd",
    "protected-export": "protected-export",
    "fps": "performance",
    "theme": "editor-ui",
    "host-locale": "editor-ui",
    "status-bar": "plugin-ui",
    "recent-preview": "project-workspace",
    "selection-lag": "selection-edit",
    "mcp": "integration",
    "backup": "autobackup",
    "backup-webdav": "autobackup",
    "selection-brush": "modeling-tool",
    "atlas": "texture-atlas",
    "atlas-nesting": "texture-atlas",
}

# Ordered capabilityId prefix rules -> capability group key.
CAPABILITY_GROUP_RULES: tuple[tuple[str, str], ...] = (
    ("cubism.editor-model.edit.parameter-structure.", "parameter-structure-edit"),
    ("cubism.editor-model.edit.parameter-key.", "parameter-key-edit"),
    ("cubism.editor-model.edit.part-object.", "part-object-edit"),
    ("cubism.editor-model.edit.selection.", "selection-edit"),
    ("cubism.editor-model.edit.deformer.", "deformer-edit"),
    ("cubism.editor-model.edit.session.", "edit-session"),
    ("cubism.editor-model.parameter-", "parameter"),
    ("cubism.editor-model.part-", "part"),
    ("cubism.editor-model.object-hierarchy.", "object-hierarchy"),
    ("cubism.editor-model.art-mesh", "art-mesh"),
    ("cubism.editor-model.texture", "texture"),
    ("cubism.editor-model.psd.", "psd"),
    ("cubism.editor-model.animation", "animation"),
    ("cubism.editor-model.warp-mirror", "warp-mirror"),
    ("cubism.editor-model.physics", "physics"),
    ("cubism.editor-model.model-instance", "model-instance"),
    ("cubism.editor-model.auto-yure", "auto-yure"),
    ("cubism.editor-model.edit-level", "edit-level"),
    ("cubism.editor-model.undo", "undo"),
    ("cubism.editor-history.", "history"),
    ("cubism.editor-ui.", "editor-ui"),
    ("cubism.texture-atlas.", "texture-atlas"),
    ("cubism.clipmask.", "clipmask"),
    ("cubism.core.", "core"),
    ("cubism.autobackup.", "autobackup"),
    ("cubism.project.", "project-workspace"),
    ("cubism.workspace.", "workspace"),
    ("cubism.startup.", "startup"),
    ("cubism.protected-export.", "protected-export"),
    ("cubism.integration.", "integration"),
    ("cubism.mesh-tool.", "mesh-tool"),
    ("cubism.modeling-tool.", "modeling-tool"),
    ("cubism.geometry.", "geometry"),
    ("turboism.performance.", "performance"),
    ("ui.mesh-toolbar.", "mesh-toolbar"),
    ("ui.", "plugin-ui"),
)

GROUP_LABELS: dict[str, dict[str, str]] = {
    "parameter-structure-edit": {"en": "Parameter structure editing", "zh": "参数结构编辑", "ja": "パラメータ構造編集", "ko": "파라미터 구조 편집"},
    "parameter-key-edit": {"en": "Parameter key editing", "zh": "参数键编辑", "ja": "パラメータキー編集", "ko": "파라미터 키 편집"},
    "part-object-edit": {"en": "Part/object editing", "zh": "部件对象编辑", "ja": "パーツ・オブジェクト編集", "ko": "파트/오브젝트 편집"},
    "selection-edit": {"en": "Selection editing", "zh": "选区编辑", "ja": "選択編集", "ko": "선택 편집"},
    "deformer-edit": {"en": "Deformer editing", "zh": "变形器编辑", "ja": "デフォーマ編集", "ko": "디포머 편집"},
    "edit-session": {"en": "Edit session", "zh": "编辑会话", "ja": "編集セッション", "ko": "편집 세션"},
    "parameter": {"en": "Parameters & bindings", "zh": "参数与绑定", "ja": "パラメータとバインド", "ko": "파라미터와 바인딩"},
    "part": {"en": "Parts & inspector", "zh": "部件与检查器", "ja": "パーツとインスペクタ", "ko": "파트와 인스펙터"},
    "object-hierarchy": {"en": "Object hierarchy", "zh": "对象层级", "ja": "オブジェクト階層", "ko": "오브젝트 계층"},
    "art-mesh": {"en": "ArtMesh", "zh": "ArtMesh", "ja": "アートメッシュ", "ko": "아트메시"},
    "texture": {"en": "Textures & raw images", "zh": "纹理与原始图像", "ja": "テクスチャと raw 画像", "ko": "텍스처와 원본 이미지"},
    "psd": {"en": "PSD raw image", "zh": "PSD 原始图像", "ja": "PSD raw 画像", "ko": "PSD 원본 이미지"},
    "animation": {"en": "Animation & timeline", "zh": "动画与时间线", "ja": "アニメーションとタイムライン", "ko": "애니메이션과 타임라인"},
    "warp-mirror": {"en": "Warp deformer mirror", "zh": "曲面变形器镜像", "ja": "ワープデフォーマミラー", "ko": "워프 디포머 미러"},
    "physics": {"en": "Physics", "zh": "物理", "ja": "物理演算", "ko": "물리"},
    "model-instance": {"en": "Model instance read", "zh": "模型实例读取", "ja": "モデルインスタンス読み取り", "ko": "모델 인스턴스 읽기"},
    "auto-yure": {"en": "Auto sway (yure) read", "zh": "自动摆动读取", "ja": "自動揺れ読み取り", "ko": "자동 흔들림 읽기"},
    "edit-level": {"en": "Edit level", "zh": "编辑级别", "ja": "編集レベル", "ko": "편집 레벨"},
    "undo": {"en": "Undo revert", "zh": "撤销恢复", "ja": "元に戻す復帰", "ko": "실행 취소 복귀"},
    "history": {"en": "Editor history", "zh": "编辑历史", "ja": "編集履歴", "ko": "편집 기록"},
    "editor-ui": {"en": "Editor UI surfaces", "zh": "编辑器 UI 表面", "ja": "エディタ UI サーフェス", "ko": "에디터 UI 표면"},
    "texture-atlas": {"en": "Texture atlas", "zh": "纹理图集", "ja": "テクスチャアトラス", "ko": "텍스처 아틀라스"},
    "clipmask": {"en": "Clip mask", "zh": "剪贴蒙版", "ja": "クリッピングマスク", "ko": "클리핑 마스크"},
    "core": {"en": "Cubism Core", "zh": "Cubism Core", "ja": "Cubism Core", "ko": "Cubism Core"},
    "autobackup": {"en": "Auto backup", "zh": "自动备份", "ja": "自動バックアップ", "ko": "자동 백업"},
    "project-workspace": {"en": "Project read", "zh": "项目读取", "ja": "プロジェクト読み取り", "ko": "프로젝트 읽기"},
    "workspace": {"en": "Workspace", "zh": "工作区", "ja": "ワークスペース", "ko": "워크스페이스"},
    "startup": {"en": "Startup suppression", "zh": "启动抑制", "ja": "起動抑制", "ko": "시작 억제"},
    "protected-export": {"en": "Protected export", "zh": "受保护导出", "ja": "保護付きエクスポート", "ko": "보호된 내보내기"},
    "integration": {"en": "Integration bridges", "zh": "集成桥接", "ja": "統合ブリッジ", "ko": "통합 브리지"},
    "mesh-tool": {"en": "Mesh tool session", "zh": "网格工具会话", "ja": "メッシュツールセッション", "ko": "메시 도구 세션"},
    "modeling-tool": {"en": "Modeling custom tools", "zh": "建模自定义工具", "ja": "モデリングカスタムツール", "ko": "모델링 사용자 도구"},
    "geometry": {"en": "Geometry read", "zh": "几何读取", "ja": "ジオメトリ読み取り", "ko": "지오메트리 읽기"},
    "performance": {"en": "Performance stats", "zh": "性能统计", "ja": "パフォーマンス統計", "ko": "성능 통계"},
    "mesh-toolbar": {"en": "Mesh toolbar contribution", "zh": "网格工具栏贡献", "ja": "メッシュツールバー贡献", "ko": "메시 도구 모음 기여"},
    "plugin-ui": {"en": "Plugin UI surfaces", "zh": "插件 UI 表面", "ja": "プラグイン UI サーフェス", "ko": "플러그인 UI 표면"},
    "misc": {"en": "Other model surfaces", "zh": "其他模型表面", "ja": "その他のモデルサーフェス", "ko": "기타 모델 표면"},
}

FEATURE_LABELS: dict[str, dict[str, str]] = {
    "dev.turboism.sdk.cubism.transaction.AuthoringTransactionService": {
        "en": "Authoring transactions (grouped undo writes)",
        "zh": "编辑事务（成组撤销写入）",
        "ja": "オーサリングトランザクション（グループ化されたアンドゥ書き込み）",
        "ko": "작성 트랜잭션(그룹 실행 취소 쓰기)",
    },
    "dev.turboism.sdk.cubism.edit.EditSessionService": {
        "en": "External edit-session API (edit())",
        "zh": "外部编辑会话 API（edit()）",
        "ja": "外部編集セッション API（edit()）",
        "ko": "외부 편집 세션 API(edit())",
    },
    "dev.turboism.sdk.cubism.mirror.WarpMirrorService": {
        "en": "Whole-object warp deformer mirror",
        "zh": "整体曲面变形器镜像",
        "ja": "オブジェクト全体のワープデフォーマミラー",
        "ko": "오브젝트 전체 워프 디포머 미러",
    },
    "dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService": {
        "en": "Polygon-aware atlas layout",
        "zh": "多边形图集布局",
        "ja": "ポリゴン対応アトラスレイアウト",
        "ko": "폴리곤 인식 아틀라스 레이아웃",
    },
    "dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession": {
        "en": "Native atlas editor session access",
        "zh": "原生纹理图集编辑器会话访问",
        "ja": "ネイティブアトラスエディタセッションへのアクセス",
        "ko": "네이티브 아틀라스 편집기 세션 액세스",
    },
    "dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi": {
        "en": "Native atlas editor UI contribution",
        "zh": "原生纹理图集编辑器 UI 贡献",
        "ja": "ネイティブアトラスエディタ UI 贡献",
        "ko": "네이티브 아틀라스 편집기 UI 기여",
    },
    "dev.turboism.sdk.cubism.model.AlphaComposition": {
        "en": "Alpha composition (blend modes)",
        "zh": "Alpha 合成（混合模式）",
        "ja": "アルファ合成（ブレンドモード）",
        "ko": "알파 합성(블렌드 모드)",
    },
    "dev.turboism.sdk.cubism.model.AutoYure": {
        "en": "Auto sway (yure)",
        "zh": "自动摇动（摆锤）",
        "ja": "自動揺れ",
        "ko": "자동 흔들림",
    },
    "dev.turboism.sdk.cubism.model.AutoYureBinding": {
        "en": "Auto sway bindings",
        "zh": "自动摇动绑定",
        "ja": "自動揺れバインド",
        "ko": "자동 흔들림 바인딩",
    },
    "dev.turboism.sdk.cubism.model.AutoYureConfig": {
        "en": "Auto sway config",
        "zh": "自动摇动配置",
        "ja": "自動揺れ設定",
        "ko": "자동 흔들림 설정",
    },
    "dev.turboism.sdk.cubism.model.YureDeformConfig": {
        "en": "Sway deformation config",
        "zh": "摇动变形配置",
        "ja": "揺れ変形設定",
        "ko": "흔들림 변형 설정",
    },
    "dev.turboism.sdk.cubism.model.Part#maskIds": {
        "en": "Part clipping mask IDs",
        "zh": "部件剪贴蒙版 ID",
        "ja": "パーツクリッピングマスク ID",
        "ko": "파트 클리핑 마스크 ID",
    },
    "dev.turboism.sdk.cubism.model.Part#setMaskIds": {
        "en": "Part clipping mask writes",
        "zh": "部件剪贴蒙版写入",
        "ja": "パーツクリッピングマスク書き込み",
        "ko": "파트 클리핑 마스크 쓰기",
    },
    "dev.turboism.sdk.cubism.model.Part#setOpacity": {
        "en": "Part opacity writes",
        "zh": "部件不透明度写入",
        "ja": "パーツ不透明度書き込み",
        "ko": "파트 불투명도 쓰기",
    },
    "dev.turboism.sdk.cubism.model.Part#alphaComposition": {
        "en": "Part alpha composition",
        "zh": "部件 Alpha 合成",
        "ja": "パーツアルファ合成",
        "ko": "파트 알파 합성",
    },
    "dev.turboism.sdk.cubism.model.Part#setAlphaComposition": {
        "en": "Part alpha composition writes",
        "zh": "部件 Alpha 合成写入",
        "ja": "パーツアルファ合成書き込み",
        "ko": "파트 알파合成 쓰기",
    },
    "dev.turboism.sdk.cubism.model.Drawable#setAlphaComposition": {
        "en": "Drawable alpha composition writes",
        "zh": "可绘制对象 Alpha 合成写入",
        "ja": "描画オブジェクトアルファ合成書き込み",
        "ko": "드로어블 알파 합성 쓰기",
    },
    "cubism.editor-model.auto-yure.read": {
        "en": "Auto sway (yure) read",
        "zh": "自动摇动读取",
        "ja": "自動揺れ読み取り",
        "ko": "자동 흔들림 읽기",
    },
    "cubism.editor-model.part-opacity.write": {
        "en": "Part opacity writes",
        "zh": "部件不透明度写入",
        "ja": "パーツ不透明度書き込み",
        "ko": "파트 불투명도 쓰기",
    },
    "cubism.editor-model.part-inspector.write": {
        "en": "Part inspector writes",
        "zh": "部件检查器写入",
        "ja": "パーツインスペクタ書き込み",
        "ko": "파트 인스펙터 쓰기",
    },
    "dev.turboism.sdk.cubism.backup.EditorAutoBackupService": {
        "en": "Editor auto-backup",
        "zh": "编辑器自动备份",
        "ja": "エディタ自動バックアップ",
        "ko": "에디터 자동 백업",
    },
    "dev.turboism.sdk.cubism.model.ModelTextures#rawImagePsdExport": {
        "en": "PSD raw-image export",
        "zh": "PSD 原始图像导出",
        "ja": "PSD raw 画像エクスポート",
        "ko": "PSD 원본 이미지 내보내기",
    },
    "dev.turboism.sdk.cubism.model.Part": {
        "en": "Part opacity / mask ids",
        "zh": "部件不透明度 / 遮罩写入",
        "ja": "パーツ不透明度 / マスク書き込み",
        "ko": "파트 불투명도 / 마스크 쓰기",
    },
    "dev.turboism.sdk.cubism.edit.SelectionOps": {
        "en": "Edit API selection ops",
        "zh": "编辑 API 选区操作",
        "ja": "編集 API の選択操作",
        "ko": "편집 API 선택 작업",
    },
    "dev.turboism.sdk.cubism.edit.PartObjectOps": {
        "en": "Edit API part/object ops",
        "zh": "编辑 API 部件/对象操作",
        "ja": "編集 API のパーツ・オブジェクト操作",
        "ko": "편집 API 파트/오브젝트 작업",
    },
    "dev.turboism.sdk.cubism.edit.ParameterStructureOps": {
        "en": "Parameter structure editing",
        "zh": "参数结构编辑",
        "ja": "パラメータ構造編集",
        "ko": "파라미터 구조 편집",
    },
    "dev.turboism.sdk.cubism.edit.ParameterKeyOps": {
        "en": "Parameter key editing",
        "zh": "参数键编辑",
        "ja": "パラメータキー編集",
        "ko": "파라미터 키 편집",
    },
    "dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService": {
        "en": "File-chooser history",
        "zh": "文件选择器历史",
        "ja": "ファイル選択履歴",
        "ko": "파일 선택기 기록",
    },
    "dev.turboism.sdk.cubism.hook.ModelFileHooks": {
        "en": "Model file hooks",
        "zh": "模型文件钩子",
        "ja": "モデルファイルフック",
        "ko": "모델 파일 후크",
    },
    "dev.turboism.sdk.cubism.hook.AnimationFileHooks": {
        "en": "Animation file hooks",
        "zh": "动画文件钩子",
        "ja": "アニメーションファイルフック",
        "ko": "애니메이션 파일 후크",
    },
    "dev.turboism.sdk.cubism.hook.ParameterHooks": {
        "en": "Parameter lifecycle hooks",
        "zh": "参数生命周期钩子",
        "ja": "パラメータライフサイクルフック",
        "ko": "파라미터 수명 주기 후크",
    },
    "dev.turboism.sdk.cubism.hook.EditorLifecycleHooks": {
        "en": "Editor lifecycle hooks",
        "zh": "编辑器生命周期钩子",
        "ja": "エディタライフサイクルフック",
        "ko": "에디터 수명 주기 후크",
    },
    "dev.turboism.sdk.cubism.physics.PhysicsEditorService": {
        "en": "Physics editor service",
        "zh": "物理编辑器服务",
        "ja": "物理演算エディタサービス",
        "ko": "물리 편집기 서비스",
    },
    "cubism.editor-history.semantic-read": {
        "en": "Semantic history decoding",
        "zh": "语义化历史解读",
        "ja": "履歴の意味的デコード",
        "ko": "기록의 의미적 디코딩",
    },
    "cubism.editor-ui.embedded-panel": {
        "en": "Embedded panel docking",
        "zh": "嵌入式面板停靠",
        "ja": "埋め込みパネル docking",
        "ko": "임베디드 패널 도킹",
    },
    "cubism.editor-ui.top-menu": {
        "en": "Top menu integration",
        "zh": "顶部菜单集成",
        "ja": "トップメニュー統合",
        "ko": "상단 메뉴 통합",
    },
    "cubism.editor-ui.main-toolbar": {
        "en": "Main toolbar integration",
        "zh": "主工具栏集成",
        "ja": "メインツールバー統合",
        "ko": "메인 도구 모음 통합",
    },
    "cubism.mesh-tool.session": {
        "en": "Mesh-edit tool session",
        "zh": "网格编辑工具会话",
        "ja": "メッシュ編集ツールセッション",
        "ko": "메시 편집 도구 세션",
    },
    "cubism.modeling-tool.selection-brush": {
        "en": "Modeling selection brush",
        "zh": "建模选区笔刷",
        "ja": "モデリング選択ブラシ",
        "ko": "모델링 선택 브러시",
    },
    "ui.mesh-toolbar.contribute": {
        "en": "Mesh toolbar contribution",
        "zh": "网格工具栏贡献",
        "ja": "メッシュツールバー贡献",
        "ko": "메시 도구 모음 기여",
    },
    "cubism.editor-model.psd.raw-image.replace": {
        "en": "PSD raw-image replace",
        "zh": "PSD 原始图像替换",
        "ja": "PSD raw 画像の置き換え",
        "ko": "PSD 원본 이미지 교체",
    },
    "cubism.editor-model.write": {
        "en": "Editor model writes",
        "zh": "编辑器模型写入",
        "ja": "エディタモデル書き込み",
        "ko": "에디터 모델 쓰기",
    },
    "cubism.editor-model.parameter-structure.write": {
        "en": "Parameter structure writes",
        "zh": "参数结构写入",
        "ja": "パラメータ構造書き込み",
        "ko": "파라미터 구조 쓰기",
    },
    "cubism.editor-model.object-hierarchy.edit": {
        "en": "Object hierarchy edits",
        "zh": "对象层级编辑",
        "ja": "オブジェクト階層編集",
        "ko": "오브젝트 계층 편집",
    },
    "cubism.editor-model.clip-mask.write": {
        "en": "Clip mask writes",
        "zh": "剪贴蒙版写入",
        "ja": "クリッピングマスク書き込み",
        "ko": "클리핑 마스크 쓰기",
    },
    "cubism.editor-model.edit.session.edit-begin": {
        "en": "Edit session begin",
        "zh": "编辑会话开启",
        "ja": "編集セッション開始",
        "ko": "편집 세션 시작",
    },
    "cubism.editor-model.statistics.read": {
        "en": "Model statistics read",
        "zh": "模型统计读取",
        "ja": "モデル統計読み取り",
        "ko": "모델 통계 읽기",
    },
    "cubism.autobackup.backup": {
        "en": "Auto-backup creation",
        "zh": "自动备份创建",
        "ja": "自動バックアップ作成",
        "ko": "자동 백업 생성",
    },
    "cubism.integration.external-app-settings.edit-toggle": {
        "en": "External-app editing toggle",
        "zh": "外部应用编辑开关",
        "ja": "外部アプリ編集トグル",
        "ko": "외부 앱 편집 토글",
    },
    "cubism.editor-model.model-instance.read": {
        "en": "Live model-instance read",
        "zh": "活动模型实例读取",
        "ja": "アクティブモデルインスタンス読み取り",
        "ko": "활성 모델 인스턴스 읽기",
    },
    "cubism.editor-history.read": {
        "en": "History read",
        "zh": "历史读取",
        "ja": "履歴読み取り",
        "ko": "기록 읽기",
    },
    "cubism.editor-model.psd.snapshot.read": {
        "en": "PSD snapshot read",
        "zh": "PSD 快照读取",
        "ja": "PSD スナップショット読み取り",
        "ko": "PSD 스냅샷 읽기",
    },
    "cubism.editor-model.warp-mirror": {
        "en": "Warp mirror",
        "zh": "曲面变形器镜像",
        "ja": "ワープミラー",
        "ko": "워프 미러",
    },
    "cubism.geometry.read": {
        "en": "Mesh geometry read",
        "zh": "网格几何读取",
        "ja": "メッシュジオメトリ読み取り",
        "ko": "메시 지오메트리 읽기",
    },
    "cubism.mesh.custom-tools": {
        "en": "Mesh-edit custom tools",
        "zh": "网格编辑自定义工具",
        "ja": "メッシュ編集カスタムツール",
        "ko": "메시 편집 사용자 도구",
    },
    "cubism.modeling.custom-tools": {
        "en": "Modeling custom tools",
        "zh": "建模自定义工具",
        "ja": "モデリングカスタムツール",
        "ko": "모델링 사용자 도구",
    },
    "cubism.mesh.mirror-axis-angle": {
        "en": "Mesh mirror axis-angle enhancement",
        "zh": "网格镜像轴角增强",
        "ja": "メッシュミラー軸角拡張",
        "ko": "메시 미러 축 각도 향상",
    },
    "cubism.deformer.alt-axis-mirror": {
        "en": "Alternate-axis warp mirror",
        "zh": "备选轴曲面镜像",
        "ja": "代替軸ワープミラー",
        "ko": "대체축 워프 미러",
    },
    "cubism.parameters.write": {
        "en": "Parameter writes",
        "zh": "参数写入",
        "ja": "パラメータ書き込み",
        "ko": "파라미터 쓰기",
    },
    "cubism.editor-commands.execute": {
        "en": "Editor command execution",
        "zh": "Editor 命令执行",
        "ja": "Editor コマンド実行",
        "ko": "Editor 명령 실행",
    },
    "cubism.psd.external-edit": {
        "en": "External PSD edit round-trip",
        "zh": "外部 PSD 编辑回写",
        "ja": "外部 PSD 編集ラウンドトリップ",
        "ko": "외부 PSD 편집 왕복",
    },
    "cubism.transaction.real-write-undo": {
        "en": "Real write-undo transactions",
        "zh": "真实写入撤销事务",
        "ja": "実書き込みアンドゥトランザクション",
        "ko": "실제 쓰기-실행 취소 트랜잭션",
    },
    "cubism.clipmask.replace-ordered-sources": {
        "en": "Ordered clip-mask source replace",
        "zh": "有序剪贴蒙版源替换",
        "ja": "順序付きクリッピングソース置換",
        "ko": "순서 있는 클리핑 소스 교체",
    },
    "cubism.texture-atlas.layout": {
        "en": "Texture-atlas layout",
        "zh": "纹理图集布局",
        "ja": "テクスチャアトラスレイアウト",
        "ko": "텍스처 아틀라스 레이아웃",
    },
    "cubism.protected-export.orchestration": {
        "en": "Protected-export orchestration",
        "zh": "受保护导出编排",
        "ja": "保護付きエクスポートオーケストレーション",
        "ko": "보호된 내보내기 오케스트레이션",
    },
    "dev.turboism.sdk.cubism.CubismFacade#authoringTransactions": {
        "en": "Authoring transactions (grouped undo writes)",
        "zh": "编辑事务（成组撤销写入）",
        "ja": "オーサリングトランザクション",
        "ko": "작성 트랜잭션",
    },
    "dev.turboism.sdk.cubism.CubismFacade#edit": {
        "en": "External edit-session API (edit())",
        "zh": "外部编辑会话 API（edit()）",
        "ja": "外部編集セッション API（edit()）",
        "ko": "외부 편집 세션 API(edit())",
    },
    "dev.turboism.sdk.cubism.CubismFacade#warpMirror": {
        "en": "Whole-object warp deformer mirror",
        "zh": "整体曲面变形器镜像",
        "ja": "オブジェクト全体のワープデフォーマミラー",
        "ko": "오브젝트 전체 워프 디포머 미러",
    },
    "dev.turboism.sdk.cubism.CubismFacade#textureAtlasPolygonLayouts": {
        "en": "Polygon-aware atlas layout",
        "zh": "多边形图集布局",
        "ja": "ポリゴン対応アトラスレイアウト",
        "ko": "폴리곤 인식 아틀라스 레이아웃",
    },
    "dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorSession": {
        "en": "Native atlas editor session access",
        "zh": "原生纹理图集编辑器会话访问",
        "ja": "ネイティブアトラスエディタセッションへのアクセス",
        "ko": "네이티브 아틀라스 편집기 세션 액세스",
    },
    "dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorUi": {
        "en": "Native atlas editor UI contribution",
        "zh": "原生纹理图集编辑器 UI 贡献",
        "ja": "ネイティブアトラスエディタ UI 贡献",
        "ko": "네이티브 아틀라스 편집기 UI 기여",
    },
    "dev.turboism.sdk.cubism.CubismFacade#history": {
        "en": "Editor history",
        "zh": "编辑历史",
        "ja": "編集履歴",
        "ko": "편집 기록",
    },
    "dev.turboism.sdk.cubism.model.ModelTextures#exportRawImagePsd": {
        "en": "Export PSD raw image",
        "zh": "导出 PSD 原图",
        "ja": "PSD 原画像のエクスポート",
        "ko": "PSD 원본 이미지 내보내기",
    },
    "dev.turboism.sdk.cubism.model.ModelTextures#replaceRawImagePsd": {
        "en": "Replace PSD raw image",
        "zh": "替换 PSD 原图",
        "ja": "PSD 原画像の置き換え",
        "ko": "PSD 원본 이미지 교체",
    },
    "dev.turboism.sdk.cubism.export.ExportSettingsContributionService": {
        "en": "Export settings contribution",
        "zh": "导出设置贡献",
        "ja": "エクスポート設定贡献",
        "ko": "내보내기 설정 기여",
    },
    "dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel": {
        "en": "Atlas editor panel",
        "zh": "图集编辑器面板",
        "ja": "アトラスエディタパネル",
        "ko": "아틀라스 편집기 패널",
    },
    "dev.turboism.sdk.cubism.command.EditorCommandService#available": {
        "en": "Editor command availability",
        "zh": "编辑器命令可用性",
        "ja": "Editor コマンド利用可否",
        "ko": "Editor 명령 사용 가능 여부",
    },
    "dev.turboism.sdk.cubism.command.EditorCommandService#execute": {
        "en": "Editor command execution",
        "zh": "编辑器命令执行",
        "ja": "Editor コマンド実行",
        "ko": "Editor 명령 실행",
    },
    "dev.turboism.sdk.cubism.backup.EditorAutoBackupService#artifacts": {
        "en": "Auto-backup artifacts",
        "zh": "自动备份工件",
        "ja": "自動バックアップ成果物",
        "ko": "자동 백업 아티팩트",
    },
    "dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupAfterSave": {
        "en": "Save-triggered auto-backup",
        "zh": "保存触发的自动备份",
        "ja": "保存時の自動バックアップ",
        "ko": "저장 시 자동 백업",
    },
    "turboism.textures.write": {
        "en": "MCP texture writes",
        "zh": "MCP 纹理写入",
        "ja": "MCP テクスチャ書き込み",
        "ko": "MCP 텍스처 쓰기",
    },
    "turboism.editor_commands.execute": {
        "en": "MCP editor command execution",
        "zh": "MCP 编辑器命令执行",
        "ja": "MCP Editor コマンド実行",
        "ko": "MCP Editor 명령 실행",
    },
    "turboism.transaction.execute": {
        "en": "MCP transaction execution",
        "zh": "MCP 编辑事务执行",
        "ja": "MCP トランザクション実行",
        "ko": "MCP 트랜잭션 실행",
    },
    "turboism://host/editor-commands": {
        "en": "MCP editor-command listing",
        "zh": "MCP 编辑器命令清单",
        "ja": "MCP Editor コマンド一覧",
        "ko": "MCP Editor 명령 목록",
    },
}



# Triage categories that denote a real capability boundary (the feature is
# absent on that host), versus noise that must never surface as a failure on
# the user-facing page.
BOUNDARY_TRIAGE = frozenset({"CAPABILITY_BOUNDARY", "ADMISSION_STATIC"})
PENDING_TRIAGE = frozenset(
    {
        "INFRA_RETRYABLE",
        "SYSTEMIC",
        "PROBE_DRIFT",
        "FIXTURE_UNSUITABLE",
        "UNKNOWN",
        "NO_EVIDENCE",
    }
)
