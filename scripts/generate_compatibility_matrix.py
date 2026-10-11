#!/usr/bin/env python3
"""Generate the Cubism Editor compatibility matrix from reviewed repository data.

Single source of truth: every version set in the emitted artifacts is parsed
from data that already gates runtime admission — never hand-written:

- ``compatibility/cubism/verification/*.json`` — static verification records;
  a capability is *verified* on a version when its record admits it there.
- ``scripts/verification-sources/families/*.json`` — verification-source
  family roster and record membership.
- ``runtime/.../ReviewedHostArtifacts.java`` — the canonical reviewed version
  list (``CUBISM_*_VERSION`` constants).
- ``@CubismEditor`` annotations across ``sdk/src/main/java`` — SDK surface
  availability (type and accessor/method level).
- ``EditorCommand`` / ``EditorParameterizedCommand`` / ``EditorFileCommand``
  enum version sets — only commands admitted on a strict subset are listed.
- Selector contracts and host profiles in ``runtime/`` and
  ``scripts/verification-sources/templates`` (``SUPPORTED_VERSIONS`` /
  ``SUPPORTED_CUBISM_VERSIONS`` sets declared next to a literal capability id,
  plus ``ClassPinTable.load`` pin tables) — the runtime admission layer that
  narrows verified capabilities. Contracts that only *reference* another
  contract's capability constant are variant/reduced forms and never narrow.
- ``plugins/*/src/main/resources/META-INF/turboism/plugin.json`` plus plugin
  Java sources — each first-party plugin's declared capabilities and the SDK
  surfaces it actually references, which determine the versions on which the
  plugin's features can resolve.
- ``plugins/*/.../*-coverage.json`` — per-endpoint SDK coverage manifests
  (for example the MCP SDK coverage table).
- ``validation/cubism-version-matrix/campaign-jobs.json`` — latest FINAL
  real-host result per task x version, mapped onto plugins and capability
  groups through the task alias tables below.

Outputs (all deterministic — no timestamps — so ``--check`` byte-compares):

- ``compatibility/cubism/compatibility-matrix.json`` — machine-readable
  matrix for the docs site and plugin directory.
- ``compatibility/cubism/plugins{,_zh,_ja,_ko}.md`` — user view: one row per
  first-party plugin with its supported range and per-version feature deltas.
- ``compatibility/cubism/capabilities{,_zh,_ja,_ko}.md`` — developer view:
  capability-group x version matrix, SDK/command constraint tables, runtime
  admission pins, and the monotonicity note.
- A ``<!-- compatibility:begin --> ... <!-- compatibility:end -->`` block in
  every ``plugins/*/README*.md`` compatibility section.

The only hand-maintained tables here are *name* mappings (plugin capability
ids -> verification capability ids, campaign task names -> plugins/groups,
and feature label translations). They never carry version data: versions are
always resolved from the sources above, so a version-coverage change flows
through regeneration without touching this file.

Usage:
  generate_compatibility_matrix.py [--root REPO]
  generate_compatibility_matrix.py --check [--root REPO]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

GENERATOR_VERSION = 1
MATRIX_FORMAT = "turboism.compatibility-matrix/v1"

REPO_ROOT = Path(__file__).resolve().parents[1]

VERIFICATION_DIR = Path("compatibility/cubism/verification")
FAMILIES_DIR = Path("scripts/verification-sources/families")
MATRIX_JSON = Path("compatibility/cubism/compatibility-matrix.json")
DOCS_DIR = Path("compatibility/cubism")
REVIEWED_HOST_ARTIFACTS = Path(
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
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
VERSION_CONSTANT_RE = re.compile(
    r'CUBISM_\d+_\d+_\d+_VERSION\s*=\s*"(\d+\.\d+\.\d+)"'
)
ANNOTATION_RE = re.compile(
    r"@(?:dev\.turboism\.sdk\.)?CubismEditor\s*\((.*?)\)", re.DOTALL
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
}

STRINGS = {
    "en": {
        "plugins_title": "Cubism Editor compatibility — first-party plugins",
        "plugins_intro": (
            "Every row is generated from the reviewed compatibility data in this "
            "repository. *Supported versions* lists the exact reviewed Cubism "
            "Editor artifacts on which the plugin's declared capabilities "
            "resolve; *Feature deltas* names functions that exist only on a "
            "subset of versions."
        ),
        "col_plugin": "Plugin",
        "col_versions": "Supported versions",
        "col_deltas": "Feature deltas",
        "col_host": "Real-host validation",
        "all_versions": "all reviewed versions",
        "full_feature": "full feature set",
        "no_cubism": "initializes without Cubism",
        "license_note_title": "FREE / PRO licence note",
        "license_note": (
            "Every Cubism Editor install on this machine starts under the FREE "
            "licence: saving then stays subject to the FREE parameter-count "
            "limit. Choosing the PRO trial lifts that limit. Turboism plugin "
            "compatibility is unaffected by the licence tier — the limit "
            "constrains what the editor may save, not which APIs the runtime "
            "admits."
        ),
        "validation_legend": (
            "Real-host column: the latest FINAL campaign result per task and "
            "version in `validation/cubism-version-matrix/campaign-jobs.json`. "
            "A version without a finished probe stays unlisted — static review "
            "is the baseline everywhere."
        ),
        "host_pass": "host-verified",
        "host_fail": "known failing",
        "host_unknown": "inconclusive",
        "generated_notice": "Generated by `scripts/generate_compatibility_matrix.py`; regenerate after version-coverage changes (`--check` is enforced by `devCheck`).",
        "only_versions": "only on",
        "not_on": "not on",
        "readme_intro": "**Cubism Editor version support (generated):**",
        "readme_more": "Full matrix",
    },
    "zh": {
        "plugins_title": "Cubism Editor 兼容性 —— 第一方插件",
        "plugins_intro": (
            "下表全部由仓库内已审核的兼容性数据生成。**支持版本**列出插件声明能力"
            "可解析的精确审核 Editor 构件；**版本差异**说明仅在部分版本上可用的"
            "功能。"
        ),
        "col_plugin": "插件",
        "col_versions": "支持版本",
        "col_deltas": "版本差异",
        "col_host": "实机验证",
        "all_versions": "全部审核版本",
        "full_feature": "全部功能",
        "no_cubism": "可在无 Cubism 下初始化",
        "license_note_title": "FREE / PRO 许可说明",
        "license_note": (
            "本机上所有 Cubism Editor 安装均以 FREE 许可启动：此时保存受 FREE"
            " 参数上限约束；选择 PRO 试用后不受该上限限制。Turboism 插件的兼容性"
            "与许可层级无关——上限约束的是编辑器允许保存的内容，而非运行时准入"
            "的 API。"
        ),
        "validation_legend": (
            "实机验证列：取自 `validation/cubism-version-matrix/campaign-jobs.json`"
            " 中每个任务×版本的最新 FINAL 结果。未列出表示该版本尚无完成的实机"
            "探测——静态审核始终是所有版本的基线。"
        ),
        "host_pass": "实机验证通过",
        "host_fail": "已知失败",
        "host_unknown": "结果不确定",
        "generated_notice": "由 `scripts/generate_compatibility_matrix.py` 生成；版本覆盖变化后请重新生成（`--check` 已由 `devCheck` 强制执行）。",
        "only_versions": "仅",
        "not_on": "不适用于",
        "readme_intro": "**Cubism Editor 版本支持（自动生成）：**",
        "readme_more": "完整矩阵",
    },
    "ja": {
        "plugins_title": "Cubism Editor 互換性 — ファーストパーティプラグイン",
        "plugins_intro": (
            "すべての行はリポジトリ内のレビュー済み互換性データから生成されています。"
            "**対応バージョン**はプラグインの宣言ケイパビリティが解決される正確な"
            "レビュー済み Editor 成果物を示し、**バージョン差分**には一部バージョン"
            "でのみ利用可能な機能を記載しています。"
        ),
        "col_plugin": "プラグイン",
        "col_versions": "対応バージョン",
        "col_deltas": "バージョン差分",
        "col_host": "実機検証",
        "all_versions": "全レビュー済みバージョン",
        "full_feature": "全機能",
        "no_cubism": "Cubism なしでも初期化可能",
        "license_note_title": "FREE / PRO ライセンスについて",
        "license_note": (
            "本機上のすべての Cubism Editor インストールは FREE ライセンスとして"
            "起動します。この場合、保存は FREE のパラメータ数上限の対象です。PRO"
            " トライアルを選択すると上限が解除されます。Turboism プラグインの"
            "互換性はライセンス階層の影響を受けません。上限はランタイムが許可する"
            " API ではなく、エディタが保存できる内容に適用されます。"
        ),
        "validation_legend": (
            "実機検証列：`validation/cubism-version-matrix/campaign-jobs.json` の各"
            "タスク×バージョンの最新 FINAL 結果。未記載のバージョンは完了した実機"
            "プローブがないことを意味し、静的レビューが常に基線です。"
        ),
        "host_pass": "実機検証済み",
        "host_fail": "既知の失敗",
        "host_unknown": "不確定",
        "generated_notice": "`scripts/generate_compatibility_matrix.py` により生成。バージョンカバレッジ変更後は再生成してください（`--check` は `devCheck` で強制されます）。",
        "only_versions": "のみ",
        "not_on": "対象外",
        "readme_intro": "**Cubism Editor バージョン対応（自動生成）：**",
        "readme_more": "完全なマトリクス",
    },
    "ko": {
        "plugins_title": "Cubism Editor 호환성 — 퍼스트파티 플러그인",
        "plugins_intro": (
            "모든 행은 이 리포지토리의 검토된 호환성 데이터에서 생성됩니다. "
            "**지원 버전**은 플러그인이 선언한 capability가 해석되는 정확한 검토 "
            "Editor 아티팩트를 나열하고, **버전별 차이**에는 일부 버전에서만 "
            "사용 가능한 기능을 표시합니다."
        ),
        "col_plugin": "플러그인",
        "col_versions": "지원 버전",
        "col_deltas": "버전별 차이",
        "col_host": "실기 검증",
        "all_versions": "모든 검토 버전",
        "full_feature": "전체 기능",
        "no_cubism": "Cubism 없이도 초기화 가능",
        "license_note_title": "FREE / PRO 라이선스 안내",
        "license_note": (
            "이 시스템의 모든 Cubism Editor 설치는 FREE 라이선스로 시작합니다. 이 "
            "경우 저장은 FREE 파라미터 수 제한을 받습니다. PRO 체험을 선택하면 제한이"
            " 해제됩니다. Turboism 플러그인 호환성은 라이선스 등급과 무관하며, 제한은"
            " 런타임이 허용하는 API가 아니라 편집기가 저장할 수 있는 내용에 적용됩니다."
        ),
        "validation_legend": (
            "실기 검증 열: `validation/cubism-version-matrix/campaign-jobs.json`의 "
            "태스크×버전별 최신 FINAL 결과입니다. 표시되지 않은 버전은 완료된 실기 "
            "프로브가 없다는 뜻이며, 정적 검토가 항상 기준선입니다."
        ),
        "host_pass": "실기 검증 통과",
        "host_fail": "알려진 실패",
        "host_unknown": "불확실",
        "generated_notice": "`scripts/generate_compatibility_matrix.py`로 생성됨. 버전 커버리지 변경 후 다시 생성하세요(`--check`는 `devCheck`에서 강제됨).",
        "only_versions": "에서만",
        "not_on": "제외",
        "readme_intro": "**Cubism Editor 버전 지원(자동 생성):**",
        "readme_more": "전체 매트릭스",
    },
}

DEV_STRINGS = {
    "en": {
        "title": "Cubism Editor compatibility — capability matrix",
        "intro": (
            "Generated view of which verified capabilities and SDK surfaces are "
            "admitted on each reviewed Cubism Editor artifact."
        ),
        "matrix_heading": "Capability groups × reviewed versions",
        "col_group": "Capability group",
        "legend": (
            "Legend: ✓ every capability in the group admitted · ◐ partially "
            "admitted · ✗ nothing admitted on that version. Admission = the "
            "static verification record admits the capability on that version "
            "AND every selector contract or class-pin gate that narrows it "
            "admits the version."
        ),
        "fn_missing": "no verified record",
        "fn_narrowed": "verified but narrowed by contract/pins (adaptation pending)",
        "sdk_heading": "SDK surfaces with version restrictions",
        "sdk_note": (
            "Only surfaces whose `@CubismEditor` resolves to a strict subset of "
            "the reviewed set are listed. Unlisted SDK APIs are available on "
            "every reviewed version, still subject to capability admission."
        ),
        "col_api": "API",
        "col_kind": "Kind",
        "col_versions": "Versions",
        "commands_heading": "Editor commands available on a subset of versions",
        "commands_note": (
            "Only commands whose admitted set differs from the full reviewed "
            "set are listed. The runtime gate is `EditorCommand#supports`."
        ),
        "col_command": "Command",
        "col_enum": "Enum",
        "pins_heading": "Runtime admission pins",
        "pins_note": (
            "Selector contracts and class-pin tables that narrow verified "
            "capabilities to specific versions (the feature exists but "
            "adaptation is pending or genuinely unavailable elsewhere)."
        ),
        "col_file": "Source",
        "col_symbol": "Symbol",
        "monotonicity_heading": "Version monotonicity",
        "monotonicity": (
            "Within one minor line, a newer patch is a superset of an older "
            "patch: capabilities and commands admitted on `5.x.y` stay admitted "
            "on `5.x.y'` for `y' > y`. Cross-line differences (for example "
            "`5.2.03` versus `5.3.00`) are genuine host boundaries, not "
            "regressions."
        ),
        "query_heading": "Querying availability at runtime",
        "query": (
            "Plugins should not branch on the version string; query the "
            "capability instead. Every `CubismFacade` accessor returns a "
            "sentinel whose `isAvailable()` reports admission, "
            "`EditorCommand#supports(String)` / `supportedVersions()` expose "
            "per-command sets, and annotated surfaces fail closed with "
            "`CubismEditorApiUnavailableException` when the host does not "
            "admit them."
        ),
        "tasks_heading": "Real-host campaign tasks",
        "tasks_note": (
            "Latest FINAL result per task × version from "
            "`validation/cubism-version-matrix/campaign-jobs.json`, grouped by "
            "capability area."
        ),
        "col_task": "Task",
    },
    "zh": {
        "title": "Cubism Editor 兼容性 —— 能力矩阵",
        "intro": "由审核数据生成的能力/SDK 表面在各已审核 Editor 构件上的准入视图。",
        "matrix_heading": "能力族 × 已审核版本",
        "col_group": "能力组",
        "legend": (
            "图例：✓ 组内全部能力均准入 · ◐ 部分准入 · ✗ 该版本无准入。准入 ="
            " 静态验证记录在该版本承认该能力，且所有收窄它的选择器契约/类 pin"
            " 也承认该版本。"
        ),
        "fn_missing": "无已验证记录",
        "fn_narrowed": "已验证但被契约/pin 收窄（待适配）",
        "sdk_heading": "带版本约束的 SDK 表面",
        "sdk_note": (
            "仅列出 `@CubismEditor` 解析为已审核集合真子集的表面；未列出的 SDK"
            " API 在全部已审核版本上可用（仍受能力准入约束）。"
        ),
        "col_api": "API",
        "col_kind": "种类",
        "col_versions": "版本",
        "commands_heading": "仅在部分版本可用的 Editor 命令",
        "commands_note": (
            "仅列出版本集合与完整已审核集不同的命令；运行时以 "
            "`EditorCommand#supports` 为准。"
        ),
        "col_command": "命令",
        "col_enum": "枚举",
        "pins_heading": "运行时准入 pin",
        "pins_note": (
            "将已验证能力收窄到特定版本的选择器契约与类 pin 表（功能存在，"
            "但适配尚未完成或在其余版本上确实不可用）。"
        ),
        "col_file": "来源",
        "col_symbol": "符号",
        "monotonicity_heading": "版本单调性原则",
        "monotonicity": (
            "同一小版本线内，新补丁是旧补丁的超集：在 `5.x.y` 上准入的能力与"
            "命令，在 `5.x.y'`（`y' > y`）上同样准入。跨小版本线的差异（如 "
            "`5.2.03` 与 `5.3.00`）是真实宿主边界而非回退。"
        ),
        "query_heading": "运行时能力查询",
        "query": (
            "插件不应按版本号分支，而应查询能力：`CubismFacade` 的各访问器"
            "返回带 `isAvailable()` 的哨兵对象；`EditorCommand#supports(String)`"
            " / `supportedVersions()` 暴露逐命令版本集合；带注解的表面在宿主"
            "未准入时以 `CubismEditorApiUnavailableException` 失败关闭。"
        ),
        "tasks_heading": "实机验证任务",
        "tasks_note": (
            "取自 `validation/cubism-version-matrix/campaign-jobs.json` 中每个"
            "任务×版本的最新 FINAL 结果，按能力域归组。"
        ),
        "col_task": "任务",
    },
    "ja": {
        "title": "Cubism Editor 互換性 — ケイパビリティマトリクス",
        "intro": "レビュー済みデータから生成した、各レビュー済み Editor 成果物で許可されるケイパビリティ/SDK サーフェスのビュー。",
        "matrix_heading": "ケイパビリティグループ × レビュー済みバージョン",
        "col_group": "ケイパビリティグループ",
        "legend": (
            "凡例: ✓ グループ内の全ケイパビリティが許可 · ◐ 部分的に許可 · ✗ "
            "そのバージョンでは不許可。許可 = 静的検証レコードがそのバージョンで"
            "ケイパビリティを認め、かつ縮小するセレクタコントラクト/クラスピンも"
            "そのバージョンを認める。"
        ),
        "fn_missing": "検証済みレコードなし",
        "fn_narrowed": "検証済みだがコントラクト/pin で縮小（適合待ち）",
        "sdk_heading": "バージョン制約付き SDK サーフェス",
        "sdk_note": (
            "`@CubismEditor` がレビュー済み集合の真部分集合に解決される"
            "サーフェスのみを列挙。未掲載の SDK API は全レビュー済みバージョンで"
            "利用可能（ケイパビリティ許可には従う）。"
        ),
        "col_api": "API",
        "col_kind": "種別",
        "col_versions": "バージョン",
        "commands_heading": "一部バージョンでのみ利用可能な Editor コマンド",
        "commands_note": (
            "許可集合が全レビュー済み集合と異なるコマンドのみを列挙。実行時の"
            "ゲートは `EditorCommand#supports`。"
        ),
        "col_command": "コマンド",
        "col_enum": "列挙",
        "pins_heading": "ランタイム許可ピン",
        "pins_note": (
            "検証済みケイパビリティを特定バージョンに絞り込むセレクタ"
            "コントラクトとクラスピンテーブル（機能は存在するが適合が未完了、"
            "または他バージョンでは利用不可）。"
        ),
        "col_file": "ソース",
        "col_symbol": "シンボル",
        "monotonicity_heading": "バージョン単調性の原則",
        "monotonicity": (
            "同一マイナーライン内では新しいパッチは古いパッチの上位集合です。"
            "`5.x.y` で許可されるケイパビリティとコマンドは `5.x.y'`（`y' > y`）"
            "でも許可されます。マイナーラインを跨ぐ差異（例: `5.2.03` と "
            "`5.3.00`）は回帰ではなく実際のホスト境界です。"
        ),
        "query_heading": "実行時のケイパビリティ照会",
        "query": (
            "プラグインはバージョン文字列で分岐せず、ケイパビリティを照会して"
            "ください。`CubismFacade` の各アクセサは `isAvailable()` を持つ"
            "センチネルを返し、`EditorCommand#supports(String)` / "
            "`supportedVersions()` がコマンドごとの集合を公開し、注釈付き"
            "サーフェスはホストが許可しない場合 "
            "`CubismEditorApiUnavailableException` でフェイルクローズします。"
        ),
        "tasks_heading": "実機検証タスク",
        "tasks_note": (
            "`validation/cubism-version-matrix/campaign-jobs.json` のタスク×"
            "バージョン別最新 FINAL 結果をケイパビリティ領域別に集約。"
        ),
        "col_task": "タスク",
    },
    "ko": {
        "title": "Cubism Editor 호환성 — capability 매트릭스",
        "intro": "검토 데이터에서 생성한, 검토된 Editor 아티팩트별로 허용되는 capability/SDK 표면 뷰.",
        "matrix_heading": "capability 그룹 × 검토 버전",
        "col_group": "capability 그룹",
        "legend": (
            "범례: ✓ 그룹 내 모든 capability 허용 · ◐ 부분 허용 · ✗ 해당 버전에서 "
            "불허. 허용 = 정적 검증 레코드가 그 버전에서 capability를 인정하고, 이를 "
            "축소하는 선택자 계약/클래스 핀도 그 버전을 인정함."
        ),
        "fn_missing": "검증된 레코드 없음",
        "fn_narrowed": "검증됐으나 계약/핀으로 축소(적응 대기)",
        "sdk_heading": "버전 제약이 있는 SDK 표면",
        "sdk_note": (
            "`@CubismEditor`가 검토 집합의 진부분 집합으로 해석되는 표면만 "
            "나열합니다. 나열되지 않은 SDK API는 모든 검토 버전에서 사용 가능"
            "합니다(capability 허용에는 따름)."
        ),
        "col_api": "API",
        "col_kind": "종류",
        "col_versions": "버전",
        "commands_heading": "일부 버전에서만 사용 가능한 Editor 명령",
        "commands_note": (
            "허용 집합이 전체 검토 집합과 다른 명령만 나열합니다. 런타임 게이트는 "
            "`EditorCommand#supports`입니다."
        ),
        "col_command": "명령",
        "col_enum": "열거형",
        "pins_heading": "런타임 허용 핀",
        "pins_note": (
            "검증된 capability를 특정 버전으로 좁히는 선택자 계약과 클래스 핀 "
            "테이블(기능은 존재하지만 적응이 미완료이거나 다른 버전에서는 사용 불가)."
        ),
        "col_file": "소스",
        "col_symbol": "심볼",
        "monotonicity_heading": "버전 단조성 원칙",
        "monotonicity": (
            "같은 마이너 라인 안에서 새 패치는 이전 패치의 상위 집합입니다. "
            "`5.x.y`에서 허용된 capability와 명령은 `5.x.y'`(`y' > y`)에서도 "
            "허용됩니다. 마이너 라인을 넘는 차이(예: `5.2.03`과 `5.3.00`)는 "
            "회귀가 아닌 실제 호스트 경계입니다."
        ),
        "query_heading": "런타임 capability 조회",
        "query": (
            "플러그인은 버전 문자열로 분기하지 말고 capability를 조회하세요. "
            "`CubismFacade`의 각 접근자는 `isAvailable()`이 있는 센티널을 반환하고, "
            "`EditorCommand#supports(String)` / `supportedVersions()`가 명령별 "
            "집합을 노출하며, 어노테이션이 있는 표면은 호스트가 허용하지 않으면 "
            "`CubismEditorApiUnavailableException`으로 실패 차단됩니다."
        ),
        "tasks_heading": "실기 검증 태스크",
        "tasks_note": (
            "`validation/cubism-version-matrix/campaign-jobs.json`의 태스크×버전별 "
            "최신 FINAL 결과를 capability 영역별로 집계."
        ),
        "col_task": "태스크",
    },
}


def version_key(version: str) -> tuple[int, int, int]:
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)


def sort_versions(versions) -> list[str]:
    return sorted(versions, key=version_key)


def compact_ranges(versions, reviewed: list[str]) -> str:
    """Render a subset of the reviewed list as compact ranges.

    Adjacency follows the reviewed order, so ``5.0.06`` and ``5.1.00`` collapse
    into one range just like consecutive patches.
    """
    chosen = set(versions)
    ordered = [v for v in reviewed if v in chosen]
    if not ordered:
        return "—"
    if ordered == reviewed:
        return f"{reviewed[0]}–{reviewed[-1]}"
    index = {v: i for i, v in enumerate(reviewed)}
    runs: list[list[str]] = [[ordered[0]]]
    for v in ordered[1:]:
        if index[v] == index[runs[-1][-1]] + 1:
            runs[-1].append(v)
        else:
            runs.append([v])
    return ", ".join(r[0] if len(r) == 1 else f"{r[0]}–{r[-1]}" for r in runs)


def localized(key: str, locale: str, fallback: str) -> str:
    labels = (
        FEATURE_LABELS.get(key)
        or FEATURE_LABELS.get(key.rstrip("()"))
        or GROUP_LABELS.get(key)
    )
    if labels is None:
        return fallback
    return labels.get(locale) or labels["en"]


def load_reviewed_versions(root: Path) -> list[str]:
    text = (root / REVIEWED_HOST_ARTIFACTS).read_text(encoding="utf-8")
    seen: list[str] = []
    for version in VERSION_CONSTANT_RE.findall(text):
        if version not in seen:
            seen.append(version)
    if not seen:
        raise ValueError("ReviewedHostArtifacts declares no CUBISM_*_VERSION constants")
    return seen


def load_families(root: Path) -> tuple[dict[str, list[str]], dict[str, str]]:
    """Return (family -> record basenames, record basename -> family)."""
    families: dict[str, list[str]] = {}
    record_family: dict[str, str] = {}
    for path in sorted((root / FAMILIES_DIR).glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        family = doc.get("family")
        if not family or family == "compatibility-catalog":
            continue
        records = doc.get("records", [])
        families[family] = records
        for record in records:
            record_family[record] = family
    return families, record_family


def load_capabilities(root: Path) -> dict[str, dict]:
    """capability id -> {verified: {version: status}, records, families}."""
    caps: dict[str, dict] = {}
    _, record_family = load_families(root)
    for path in sorted((root / VERIFICATION_DIR).glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        version = doc.get("cubismVersion")
        status = doc.get("status")
        family = record_family.get(path.name)
        for cap in doc.get("capabilityIds", []):
            entry = caps.setdefault(
                cap, {"verified": {}, "records": {}, "families": set()}
            )
            entry["verified"][version] = status
            entry["records"][version] = path.name
            if family:
                entry["families"].add(family)
    for entry in caps.values():
        entry["families"] = sorted(entry["families"])
    return caps


def load_contract_gates(root: Path) -> dict[str, list[dict]]:
    """capability id -> narrowing version sets from selector contracts.

    Only contracts that declare their capability id as a literal in the same
    file count: contracts that *reference* another contract's capability
    constant are variant/reduced forms (for example the no-alpha-composition
    inspector contract) and never narrow the base capability.
    """
    gates: dict[str, list[dict]] = {}
    for base in (TEMPLATES_DIR, RUNTIME_MAIN):
        base_dir = root / base
        if not base_dir.is_dir():
            continue
        for path in sorted(base_dir.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            cap_ids = set(CAPABILITY_CONSTANT_RE.findall(text))
            if not cap_ids:
                continue
            rel = str(path.relative_to(root))
            for symbol, body in SUPPORTED_SET_RE.findall(text):
                versions = sorted(
                    set(QUOTED_VERSIONS_RE.findall(body)), key=version_key
                )
                if not versions:
                    continue
                for cap in cap_ids:
                    gates.setdefault(cap, []).append(
                        {"source": f"{rel}:{symbol}", "versions": versions}
                    )
    return gates


def load_required_capabilities(root: Path) -> dict[str, list[str]]:
    """SDK class FQN -> capability ids the availability interceptor requires."""
    path = root / INTERCEPTOR
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8")
    editor_read = re.search(r"editorRead\s*=\s*Set\.of\(([^)]*)\)", text)
    editor_read_caps = (
        QUOTED_VERSIONS_RE.findall(editor_read.group(1)) if editor_read else []
    )
    required: dict[str, list[str]] = {}
    for match in re.finditer(
        r"required\.put\(\s*([\w.]+)\.class,\s*(editorRead|Set\.of\(([^)]*)\))\s*\)",
        text,
    ):
        cls, inline = match.group(1), match.group(3)
        caps = editor_read_caps if inline is None else QUOTED_VERSIONS_RE.findall(inline)
        required[cls] = sorted(set(caps))
    return required


def _parse_annotation_args(args: str, reviewed: list[str]) -> set[str]:
    """Resolve one @CubismEditor argument body to a reviewed-version subset."""
    exact: list[str] = []
    value_match = re.search(r"(?:value\s*=\s*)?\{([^}]*)\}", args)
    if value_match:
        exact = QUOTED_VERSIONS_RE.findall(value_match.group(1))
    elif args.strip().startswith('"'):
        exact = QUOTED_VERSIONS_RE.findall(args)
    from_match = re.search(r'from\s*=\s*"([^"]*)"', args)
    to_match = re.search(r'to\s*=\s*"([^"]*)"', args)
    if exact and not from_match and not to_match:
        selected = set(exact)
    else:
        lo = from_match.group(1) if from_match else ""
        hi = to_match.group(1) if to_match else ""
        selected = {
            v
            for v in reviewed
            if (not lo or version_key(v) >= version_key(lo))
            and (not hi or version_key(v) <= version_key(hi))
        }
        if exact:
            selected &= set(exact)
    exclude_match = re.search(r"exclude\s*=\s*\{([^}]*)\}", args)
    if exclude_match is None:
        exclude_match = re.search(r'exclude\s*=\s*"([^"]+)"', args)
    if exclude_match:
        selected -= set(QUOTED_VERSIONS_RE.findall(exclude_match.group(1)))
    return selected


def _strip_java(text: str) -> str:
    """Blank comments and literals while preserving offsets."""
    def repl(match: re.Match) -> str:
        return "".join("\n" if c == "\n" else " " for c in match.group(0))

    return re.sub(
        r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|\'(?:\\.|[^\'\\])*\'",
        repl,
        text,
        flags=re.DOTALL,
    )


def _type_spans(stripped: str) -> list[tuple[str, int, int]]:
    """(name, decl start, body end) for every type declaration, brace-matched."""
    spans: list[tuple[str, int, int]] = []
    for match in TYPE_DECL_RE.finditer(stripped):
        name = match.group(1)
        brace = stripped.find("{", match.end())
        if brace == -1:
            continue
        # Ensure no ';' precedes the brace (a field/prototype, not a body).
        if ";" in stripped[match.end() : brace] or ")" in stripped[match.end() : brace]:
            continue
        depth = 0
        pos = brace
        while pos < len(stripped):
            if stripped[pos] == "{":
                depth += 1
            elif stripped[pos] == "}":
                depth -= 1
                if depth == 0:
                    break
            pos += 1
        spans.append((name, match.start(), pos))
    return spans


def parse_sdk_annotations(root: Path, reviewed: list[str]) -> tuple[list[dict], dict[str, list[dict]]]:
    """Parse every @CubismEditor in the SDK.

    Returns (entries, accessor_map). Entries carry {api, kind, versions, file};
    accessor_map maps a method's return-type FQN to the annotated accessors
    producing it.
    """
    entries: list[dict] = []
    accessor_map: dict[str, list[dict]] = {}
    sdk_dir = root / SDK_MAIN
    for path in sorted(sdk_dir.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        rel = str(path.relative_to(root))
        package_match = PACKAGE_RE.search(text)
        package = package_match.group(1) if package_match else ""
        imports = {m.split(".")[-1]: m for m in IMPORT_RE.findall(text)}
        spans = _type_spans(_strip_java(text))
        for match in ANNOTATION_RE.finditer(text):
            versions = _parse_annotation_args(match.group(1), reviewed)
            tail = text[match.end() : match.end() + 400].lstrip()
            enclosing = [s for s in spans if s[1] < match.start() < s[2]]
            enclosing.sort(key=lambda s: s[1])
            owner_chain = [s[0] for s in enclosing]
            type_match = re.match(
                r"(?:public\s+|protected\s+|private\s+|final\s+|abstract\s+|"
                r"static\s+|sealed\s+|non-sealed\s+)*"
                r"(?:class|interface|enum|@interface)\s+(\w+)",
                tail,
            )
            if type_match and not enclosing:
                api = f"{package}.{type_match.group(1)}"
                kind = "type"
            elif type_match:
                api = f"{package}." + ".".join(owner_chain + [type_match.group(1)])
                kind = "type"
            else:
                member_match = MEMBER_RE.match(tail)
                if not member_match:
                    continue
                return_type, method_name = member_match.groups()
                owner = ".".join(owner_chain) if owner_chain else path.stem
                api = f"{package}.{owner}#{method_name}()"
                kind = "method"
                bare = re.sub(r"<.*>", "", return_type).rstrip("[]")
                return_fqn = imports.get(bare)
                if return_fqn is None:
                    if "." in bare:
                        return_fqn = bare
                    elif bare[:1].isupper() and package:
                        # Same-package or nested type.
                        return_fqn = f"{package}.{bare}"
                if return_fqn:
                    accessor_map.setdefault(return_fqn, []).append(
                        {"api": api, "versions": versions}
                    )
            entries.append(
                {"api": api, "kind": kind, "versions": sorted(versions, key=version_key), "file": rel}
            )
    return entries, accessor_map


def load_commands(root: Path, reviewed: list[str]) -> list[dict]:
    """Enum constants admitted on a strict subset of the reviewed versions."""
    commands: list[dict] = []
    full = set(reviewed)
    for rel in COMMAND_ENUMS:
        path = root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        enum_name = Path(rel).stem
        for match in ENUM_ENTRY_RE.finditer(text):
            name, body = match.group(1), match.group(2)
            versions = set(QUOTED_VERSIONS_RE.findall(body))
            if not versions and "Set.of" not in body:
                continue
            if versions != full:
                commands.append(
                    {
                        "enum": enum_name,
                        "name": name,
                        "versions": sorted(versions, key=version_key),
                    }
                )
    return commands


def load_class_pins(root: Path) -> dict[str, dict]:
    """pin name -> {versions, loadedBy} derived from ClassPinTable.load sites."""
    loaded_by: dict[str, str] = {}
    runtime_dir = root / RUNTIME_MAIN
    if runtime_dir.is_dir():
        for path in sorted(runtime_dir.rglob("*.java")):
            for pin in CLASS_PIN_LOAD_RE.findall(path.read_text(encoding="utf-8")):
                loaded_by.setdefault(pin, str(path.relative_to(root)))
    pins: dict[str, dict] = {}
    pins_dir = root / CLASS_PINS_DIR
    if pins_dir.is_dir():
        for path in sorted(pins_dir.glob("*.json")):
            doc = json.loads(path.read_text(encoding="utf-8"))
            versions = sorted(
                (k for k in doc if VERSION_RE.match(k)), key=version_key
            )
            pins[path.stem] = {
                "versions": versions,
                "loadedBy": loaded_by.get(path.stem),
            }
    return pins


def load_runtime_gates(root: Path) -> list[dict]:
    """SUPPORTED_* sets that do not share a file with a capability-id literal
    (feature-level pins rather than capability contracts)."""
    gates: list[dict] = []
    dirs = [root / RUNTIME_MAIN, root / "plugins"]
    for base in dirs:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if "/src/test/" in str(path):
                continue
            text = path.read_text(encoding="utf-8")
            if CAPABILITY_CONSTANT_RE.search(text):
                continue
            for symbol, body in SUPPORTED_SET_RE.findall(text):
                versions = sorted(
                    set(QUOTED_VERSIONS_RE.findall(body)), key=version_key
                )
                if versions:
                    gates.append(
                        {
                            "file": str(path.relative_to(root)),
                            "symbol": symbol,
                            "versions": versions,
                        }
                    )
    return gates


def resolve_declared_capability(
    declared: str, cap_index: dict[str, dict]
) -> dict:
    """Map a plugin.json capability id to verification capability ids + pins.

    Exact matches win; otherwise the curated alias table applies; otherwise a
    token-subset fallback tries the id's tail tokens against known ids.
    Unknown capability ids resolve to nothing rather than the empty set.
    """
    if declared in cap_index:
        return {"capabilities": [declared], "pins": [], "source": "exact"}
    alias = DECLARED_CAPABILITY_ALIASES.get(declared)
    if alias:
        caps = [c for c in alias.get("capabilities", []) if c in cap_index]
        return {
            "capabilities": caps,
            "pins": alias.get("pins", []),
            "source": "alias",
        }
    noise = {"cubism", "turboism", "ui"}
    tokens = set(declared.split(".")) - noise
    if tokens:
        matches = sorted(
            cid for cid in cap_index if tokens <= set(cid.split(".")) - noise
        )
        if 0 < len(matches) <= 4:
            return {"capabilities": matches, "pins": [], "source": "tokens"}
    return {"capabilities": [], "pins": [], "source": "unmapped"}


def capability_group(cap_id: str) -> str:
    for prefix, group in CAPABILITY_GROUP_RULES:
        if cap_id.startswith(prefix):
            return group
    return "misc"


def admitted_versions(
    cap_id: str,
    caps: dict[str, dict],
    gates: dict[str, list[dict]],
) -> set[str]:
    """Verified versions minus literal-contract narrowing."""
    verified = {
        v
        for v, status in caps.get(cap_id, {}).get("verified", {}).items()
        if status == "VERIFIED_STATIC"
    }
    gate_versions = [
        set(g["versions"]) for g in gates.get(cap_id, []) if g["versions"]
    ]
    if gate_versions:
        # Multiple contracts covering one capability are alternative admission
        # paths, so their sets union before intersecting the verified set.
        admitted = verified & set.union(*gate_versions)
        return admitted if admitted else verified
    return verified


def load_campaign(root: Path, reviewed: list[str]) -> dict[str, dict[str, dict]]:
    """task -> version -> latest FINAL job result (file order wins).

    Jobs record versions in three shapes observed in the campaign history:
    dotted (``"5.3.01"``), unpadded dotted (``"5.20.1"``), and bare matrix
    codes (``"5301"``). Normalization compares digit sequences against the
    reviewed versions, so a bare string can never resolve to an unreviewed
    version.
    """
    path = root / CAMPAIGN_JOBS
    if not path.is_file():
        return {}
    by_digits = {re.sub(r"\D", "", v): v for v in reviewed}

    def normalize_version(raw) -> str | None:
        if not raw:
            return None
        return by_digits.get(re.sub(r"\D", "", str(raw)))

    doc = json.loads(path.read_text(encoding="utf-8"))
    latest: dict[str, dict[str, dict]] = {}
    for job in doc.get("jobs", []):
        if job.get("status") != "FINAL":
            continue
        task = job.get("task")
        version = normalize_version(job.get("version"))
        if not task or not version:
            continue
        result = job.get("result") or job
        validation = result.get("validationStatus")
        terminal = result.get("terminalState")
        if validation is None:
            validation = {"succeeded": "PASS", "failed": "FAIL"}.get(terminal)
        latest.setdefault(task, {})[version] = {
            "validationStatus": validation,
            "terminalState": terminal,
            "round": job.get("round"),
            "jobId": job.get("jobId"),
        }
    return latest


def task_base(task: str) -> str:
    return re.sub(r"_\d{4}$", "", task)


def load_plugins(root: Path) -> list[dict]:
    plugins: list[dict] = []
    for descriptor in sorted((root / "plugins").glob(f"*/{PLUGIN_DESCRIPTOR}")):
        doc = json.loads(descriptor.read_text(encoding="utf-8"))
        # descriptor = plugins/<name>/src/main/resources/META-INF/turboism/plugin.json
        module = descriptor.parents[5]
        java_dir = module / "src/main/java"
        sdk_refs: set[str] = set()
        java_texts: list[str] = []
        if java_dir.is_dir():
            for source in sorted(java_dir.rglob("*.java")):
                text = source.read_text(encoding="utf-8")
                java_texts.append(text)
                sdk_refs |= set(SDK_IMPORT_RE.findall(text))
                sdk_refs |= set(FQCN_REF_RE.findall(text))
        coverage: list[dict] = []
        manifest_dir = module / "src/main/resources/META-INF/turboism"
        if manifest_dir.is_dir():
            for manifest in sorted(manifest_dir.glob("*-coverage.json")):
                cov_doc = json.loads(manifest.read_text(encoding="utf-8"))
                coverage.extend(cov_doc.get("entries", []))
        plugins.append(
            {
                "directory": module.name,
                "id": doc.get("id"),
                "name": doc.get("name"),
                "requiresCubism": bool(
                    doc.get("environment", {}).get("requiresCubism")
                ),
                "delivery": doc.get("delivery", ""),
                "capabilities": doc.get("capabilities", []),
                "sdkRefs": sorted(sdk_refs),
                "javaTexts": java_texts,
                "coverage": coverage,
            }
        )
    return plugins


def _note(key: str, declared, versions, reviewed_set, source, **extra) -> dict:
    note = {
        "key": key,
        "declaredCapability": declared,
        "versions": sorted(versions, key=version_key),
        "missingOn": sorted(reviewed_set - set(versions), key=version_key),
        "source": source,
    }
    note.update(extra)
    return note


def build_matrix(root: Path) -> dict:
    reviewed = load_reviewed_versions(root)
    reviewed_set = set(reviewed)
    caps = load_capabilities(root)
    gates = load_contract_gates(root)
    required_caps = load_required_capabilities(root)
    sdk_entries, accessor_map = parse_sdk_annotations(root, reviewed)
    type_annotations = {
        e["api"]: set(e["versions"]) for e in sdk_entries if e["kind"] == "type"
    }
    commands = load_commands(root, reviewed)
    class_pins = load_class_pins(root)
    runtime_gates = load_runtime_gates(root)
    campaign = load_campaign(root, reviewed)

    admitted = {cap: admitted_versions(cap, caps, gates) for cap in caps}

    groups: dict[str, list[str]] = {}
    for cap in caps:
        groups.setdefault(capability_group(cap), []).append(cap)

    plugins: list[dict] = []
    for plugin in load_plugins(root):
        functional_sets: list[set[str]] = []
        feature_notes: list[dict] = []
        unmapped: list[str] = []
        for declared in plugin["capabilities"]:
            resolved = resolve_declared_capability(declared, caps)
            cap_sets = [admitted[c] for c in resolved["capabilities"]]
            pin_sets = [
                set(class_pins[pin]["versions"])
                for pin in resolved["pins"]
                if pin in class_pins
            ]
            sets = cap_sets + pin_sets
            if not sets:
                if not declared.startswith(NON_FUNCTIONAL_PREFIXES):
                    unmapped.append(declared)
                continue
            available = set.intersection(*sets)
            if not declared.startswith(NON_FUNCTIONAL_PREFIXES):
                functional_sets.append(available)
            if available and available != reviewed_set:
                key = (
                    resolved["capabilities"][0]
                    if len(resolved["capabilities"]) == 1
                    else declared
                )
                feature_notes.append(
                    _note(key, declared, available, reviewed_set, "capability")
                )
        # Capability namespaces named after the plugin directory are the
        # plugin's feature itself (e.g. cubism.protected-export.*).
        for cap in caps:
            if f".{plugin['directory']}." in f".{cap}.":
                functional_sets.append(admitted[cap])
                if admitted[cap] != reviewed_set:
                    feature_notes.append(
                        _note(cap, None, admitted[cap], reviewed_set, "family-prefix")
                    )
        all_java = "\n".join(plugin["javaTexts"])
        seen_keys: set[str] = set()
        ref_set = set(plugin["sdkRefs"])
        # Accessor api -> the return type it produces; a facade method whose
        # return type the plugin references is already counted via that type.
        accessor_return: dict[str, str] = {}
        for return_fqn, accessors in accessor_map.items():
            for accessor in accessors:
                accessor_return[accessor["api"]] = return_fqn
        for ref in plugin["sdkRefs"]:
            constraints: list[tuple[str, set[str]]] = []
            if ref in type_annotations:
                constraints.append((ref, type_annotations[ref]))
            for accessor in accessor_map.get(ref, []):
                method_name = accessor["api"].split("#")[-1].rstrip("()")
                if not re.search(rf"\.{re.escape(method_name)}\s*\(", all_java):
                    continue
                # The surfaced feature is the accessor's return type, so the
                # note keys on the referenced type rather than the accessor.
                constraints.append((ref, set(accessor["versions"])))
            for entry in sdk_entries:
                if entry["kind"] != "method" or entry["api"].split("#")[0] != ref:
                    continue
                if accessor_return.get(entry["api"]) in ref_set:
                    # Accessor methods are already counted via the type they
                    # return; listing them again would duplicate the note.
                    continue
                member = entry["api"].split("#")[-1].rstrip("()")
                if re.search(rf"\.{re.escape(member)}\s*\(", all_java):
                    constraints.append((entry["api"], set(entry["versions"])))
            req = required_caps.get(ref)
            if req:
                narrowed = set(reviewed)
                for cap in req:
                    narrowed &= admitted.get(cap, set())
                if narrowed != reviewed_set:
                    constraints.append((ref, narrowed))
            for api, versions in constraints:
                if not versions or versions == reviewed_set or api in seen_keys:
                    continue
                seen_keys.add(api)
                feature_notes.append(
                    _note(api, None, versions, reviewed_set, "sdk-api")
                )
        coverage_groups: dict[tuple[str, str], dict] = {}
        for entry in plugin["coverage"]:
            versions = set(entry.get("supportedVersions", [])) & reviewed_set
            if not versions or versions == reviewed_set:
                continue
            endpoint = entry.get("endpoint") or "?"
            operation = entry.get("operation") or "?"
            grouped = coverage_groups.setdefault(
                endpoint, {"versions": set(versions), "methods": [], "ops": []}
            )
            grouped["versions"] &= versions
            grouped["ops"].append(operation)
            method = entry.get("sdkMethod", "")
            if method:
                head = re.sub(r"\(.*", "", method)
                grouped["methods"].append(head)
        for endpoint, grouped in coverage_groups.items():
            feature_notes.append(
                _note(
                    endpoint,
                    None,
                    grouped["versions"],
                    reviewed_set,
                    "coverage-manifest",
                    endpoint=endpoint,
                    operations=sorted(set(grouped["ops"])),
                    methods=sorted(set(grouped["methods"])),
                )
            )
        if functional_sets:
            supported = set.union(*functional_sets)
            full_feature = set.intersection(*functional_sets)
        else:
            supported = set(reviewed)
            full_feature = set(reviewed)
        merged: dict[str, dict] = {}
        for note in feature_notes:
            key = note["key"]
            existing = merged.get(key)
            if existing is None:
                merged[key] = dict(note)
            else:
                existing["versions"] = sorted(
                    set(existing["versions"]) & set(note["versions"]),
                    key=version_key,
                )
                existing["missingOn"] = sorted(
                    reviewed_set - set(existing["versions"]), key=version_key
                )
                existing["source"] = f'{existing["source"]}+{note["source"]}'
        tasks = {
            task: versions
            for task, versions in campaign.items()
            if TASK_PLUGIN_ALIASES.get(task_base(task)) == plugin["directory"]
        }
        plugins.append(
            {
                "id": plugin["id"],
                "name": plugin["name"],
                "directory": plugin["directory"],
                "requiresCubism": plugin["requiresCubism"],
                "supportedVersions": sorted(supported, key=version_key),
                "fullFeatureVersions": sorted(full_feature, key=version_key),
                "partialFeatures": sorted(
                    merged.values(),
                    key=lambda n: (len(n["versions"]), n["key"]),
                ),
                "unmappedCapabilities": sorted(unmapped),
                "hostValidation": tasks,
            }
        )

    capability_rows = [
        {
            "id": cap,
            "group": capability_group(cap),
            "families": caps[cap]["families"],
            "verified": sorted(caps[cap]["verified"], key=version_key),
            "admitted": sorted(admitted[cap], key=version_key),
            "narrowedBy": [
                {"source": g["source"], "versions": g["versions"]}
                for g in gates.get(cap, [])
            ],
        }
        for cap in sorted(caps)
    ]

    sdk_constraints = [
        {
            "api": e["api"],
            "kind": e["kind"],
            "versions": e["versions"],
            "file": e["file"],
        }
        for e in sdk_entries
        if set(e["versions"]) != reviewed_set
    ]

    return {
        "format": MATRIX_FORMAT,
        "generatorVersion": GENERATOR_VERSION,
        "generator": "scripts/generate_compatibility_matrix.py",
        "reviewedVersions": reviewed,
        "capabilities": capability_rows,
        "capabilityGroups": [
            {
                "id": group,
                "capabilities": sorted(members),
                "label": GROUP_LABELS.get(group, {}),
            }
            for group, members in sorted(groups.items())
        ],
        "commands": commands,
        "sdkApiConstraints": sorted(sdk_constraints, key=lambda e: e["api"]),
        "runtimeGates": sorted(runtime_gates, key=lambda g: (g["file"], g["symbol"])),
        "classPins": [
            {"name": name, "versions": pin["versions"], "loadedBy": pin["loadedBy"]}
            for name, pin in sorted(class_pins.items())
        ],
        "plugins": plugins,
        "hostValidation": {task: v for task, v in sorted(campaign.items())},
        "taskPluginAliases": TASK_PLUGIN_ALIASES,
        "taskGroupAliases": TASK_GROUP_ALIASES,
    }


def _display_fallback(key: str) -> str:
    if "#" in key:
        owner, _, member = key.partition("#")
        return f"{owner.split('.')[-1]}#{member}"
    for prefix in ("dev.turboism.sdk.", "cubism.", "turboism."):
        if key.startswith(prefix):
            return key[len(prefix):]
    return key


def _feature_text(note: dict, locale: str, reviewed: list[str]) -> str:
    label = localized(note["key"], locale, _display_fallback(note["key"]))
    s = STRINGS[locale]
    versions = note["versions"]
    if not versions:
        return f"{label} —"
    if len(versions) <= len(reviewed) // 2:
        return f"{label} {s['only_versions']} {compact_ranges(versions, reviewed)}"
    return f"{label} {s['not_on']} {compact_ranges(note['missingOn'], reviewed)}"


def _host_cell(tasks: dict[str, dict], reviewed: list[str], locale: str) -> str:
    if not tasks:
        return "—"
    passed: set[str] = set()
    failed: set[str] = set()
    unknown: set[str] = set()
    for versions in tasks.values():
        for version, result in versions.items():
            status = result.get("validationStatus") or ""
            if status == "PASS":
                passed.add(version)
            elif status.startswith("FAIL"):
                failed.add(version)
            else:
                unknown.add(version)
    s = STRINGS[locale]
    parts = []
    if passed:
        parts.append(f"{s['host_pass']} {compact_ranges(passed, reviewed)}")
    if failed - passed:
        parts.append(f"{s['host_fail']} {compact_ranges(failed - passed, reviewed)}")
    if unknown - passed - failed:
        parts.append(
            f"{s['host_unknown']} {compact_ranges(unknown - passed - failed, reviewed)}"
        )
    return "<br>".join(parts) if parts else "—"


def render_plugins_doc(matrix: dict, locale: str) -> str:
    s = STRINGS[locale]
    reviewed = matrix["reviewedVersions"]
    lines = [
        f"# {s['plugins_title']}",
        "",
        f"> {s['generated_notice']}",
        "",
        s["plugins_intro"],
        "",
        f"## {s['license_note_title']}",
        "",
        s["license_note"],
        "",
        "| " + " | ".join(
            [s["col_plugin"], s["col_versions"], s["col_deltas"], s["col_host"]]
        ) + " |",
        "|---|---|---|---|",
    ]
    for plugin in matrix["plugins"]:
        versions = plugin["supportedVersions"]
        if versions == reviewed:
            version_text = s["all_versions"]
        elif versions:
            version_text = compact_ranges(versions, reviewed)
        else:
            version_text = "—"
        notes = []
        if not plugin["requiresCubism"]:
            notes.append(s["no_cubism"])
        full = plugin["fullFeatureVersions"]
        if full and full != versions:
            notes.append(
                f"{s['full_feature']}: {compact_ranges(full, reviewed)}"
            )
        notes.extend(
            _feature_text(note, locale, reviewed)
            for note in plugin["partialFeatures"][:5]
        )
        deltas = "<br>".join(notes) if notes else "—"
        host = _host_cell(plugin["hostValidation"], reviewed, locale)
        lines.append(
            f"| `{plugin['directory']}` | {version_text} | {deltas} | {host} |"
        )
    lines += ["", s["validation_legend"], ""]
    return "\n".join(lines)


def render_capabilities_doc(matrix: dict, locale: str) -> str:
    s = DEV_STRINGS[locale]
    reviewed = matrix["reviewedVersions"]
    lines = [
        f"# {s['title']}",
        "",
        f"> {STRINGS[locale]['generated_notice']}",
        "",
        s["intro"],
        "",
        f"## {s['matrix_heading']}",
        "",
        s["legend"],
        "",
        "| " + " | ".join([s["col_group"], *reviewed]) + " |",
        "|" + "---|" * (len(reviewed) + 1),
    ]
    cap_by_id = {c["id"]: c for c in matrix["capabilities"]}
    footnotes: list[str] = []
    for group in matrix["capabilityGroups"]:
        group_caps = group["capabilities"]
        cells = []
        for version in reviewed:
            admitted_n = sum(
                1 for c in group_caps if version in cap_by_id[c]["admitted"]
            )
            if admitted_n == len(group_caps):
                cells.append("✓")
            elif admitted_n == 0:
                cells.append("✗")
            else:
                cells.append("◐")
        for cap in group_caps:
            row = cap_by_id[cap]
            verified = set(row["verified"])
            admitted = set(row["admitted"])
            missing = [v for v in reviewed if v not in verified]
            narrowed = [v for v in verified if v not in admitted]
            label = localized(cap, locale, cap)
            display = f"`{cap}`" if label == cap else f"`{cap}` ({label})"
            if missing:
                footnotes.append(
                    f"{display}: {s['fn_missing']} "
                    f"{compact_ranges(missing, reviewed)}"
                )
            if narrowed:
                footnotes.append(
                    f"{display}: {s['fn_narrowed']} "
                    f"{compact_ranges(narrowed, reviewed)}"
                )
        label = localized(group["id"], locale, group["id"])
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    lines.append("")
    if footnotes:
        lines.extend(f"- {note}" for note in footnotes)
        lines.append("")
    lines += [
        f"## {s['sdk_heading']}",
        "",
        s["sdk_note"],
        "",
        f"| {s['col_api']} | {s['col_kind']} | {s['col_versions']} |",
        "|---|---|---|",
    ]
    for entry in matrix["sdkApiConstraints"]:
        lines.append(
            f"| `{entry['api']}` | {entry['kind']} | "
            f"{compact_ranges(entry['versions'], reviewed)} |"
        )
    lines += [
        "",
        f"## {s['commands_heading']}",
        "",
        s["commands_note"],
        "",
        f"| {s['col_command']} | {s['col_enum']} | {s['col_versions']} |",
        "|---|---|---|",
    ]
    for command in matrix["commands"]:
        version_text = (
            compact_ranges(command["versions"], reviewed)
            if command["versions"]
            else "—"
        )
        lines.append(f"| `{command['name']}` | {command['enum']} | {version_text} |")
    lines += [
        "",
        f"## {s['pins_heading']}",
        "",
        s["pins_note"],
        "",
        f"| {s['col_file']} | {s['col_symbol']} | {s['col_versions']} |",
        "|---|---|---|",
    ]
    for pin in matrix["classPins"]:
        lines.append(
            f"| class-pins/`{pin['name']}.json` | `ClassPinTable` | "
            f"{compact_ranges(pin['versions'], reviewed)} |"
        )
    for gate in matrix["runtimeGates"]:
        lines.append(
            f"| `{gate['file']}` | `{gate['symbol']}` | "
            f"{compact_ranges(gate['versions'], reviewed)} |"
        )
    lines += [
        "",
        f"## {s['monotonicity_heading']}",
        "",
        s["monotonicity"],
        "",
        f"## {s['query_heading']}",
        "",
        s["query"],
        "",
        f"## {s['tasks_heading']}",
        "",
        s["tasks_note"],
        "",
        "| " + " | ".join([s["col_task"], *reviewed]) + " |",
        "|" + "---|" * (len(reviewed) + 1),
    ]
    group_rows: dict[str, dict[str, str]] = {}
    for task, versions in matrix["hostValidation"].items():
        group = TASK_GROUP_ALIASES.get(task_base(task), task_base(task))
        cells = group_rows.setdefault(group, {})
        for version, result in versions.items():
            mark = {"PASS": "✓", "FAIL": "✗"}.get(
                result.get("validationStatus"), "?"
            )
            if cells.get(version) != "✓":
                cells[version] = mark
    for group in sorted(group_rows):
        cells = [group_rows[group].get(v, "") for v in reviewed]
        lines.append(f"| `{group}` | " + " | ".join(cells) + " |")
    lines.append("")
    return "\n".join(lines)


def readme_block(plugin: dict, locale: str, reviewed: list[str]) -> str:
    s = STRINGS[locale]
    versions = plugin["supportedVersions"]
    version_text = (
        s["all_versions"]
        if versions == reviewed
        else compact_ranges(versions, reviewed)
    )
    doc_name = f"plugins{DOC_SUFFIX[locale]}.md"
    parts = [
        BLOCK_BEGIN,
        f"{s['readme_intro']} {version_text}.",
    ]
    parts.extend(
        f"- {_feature_text(note, locale, reviewed)}"
        for note in plugin["partialFeatures"][:3]
    )
    parts.append(
        f"{s['readme_more']}: [compatibility matrix](../../compatibility/cubism/{doc_name})"
    )
    parts.append(BLOCK_END)
    return "\n".join(parts)


def update_readme(
    text: str, plugin: dict, readme_name: str, reviewed: list[str]
) -> str:
    """Insert or replace the generated compatibility block inside the
    compatibility section of one README variant."""
    locale = README_LOCALE[readme_name]
    heading = README_HEADINGS[readme_name]
    block = readme_block(plugin, locale, reviewed)
    begin = text.find(BLOCK_BEGIN)
    end = text.find(BLOCK_END)
    if begin != -1 and end != -1 and end > begin:
        return text[:begin] + block + text[end + len(BLOCK_END):]
    heading_pos = text.find(heading)
    if heading_pos == -1:
        # Development-only READMEs use a lighter template; locate any heading
        # whose text mentions compatibility in this locale.
        fallback = re.search(
            r"^##\s+.*(?:兼容|互換|compatib|호환)[^\n]*$",
            text,
            re.MULTILINE | re.IGNORECASE,
        )
        if fallback:
            heading_pos = fallback.start()
        else:
            return text.rstrip() + "\n\n" + block + "\n"
    next_heading = text.find("\n## ", heading_pos + 4)
    if next_heading == -1:
        return text.rstrip() + "\n\n" + block + "\n"
    return text[:next_heading].rstrip() + "\n\n" + block + "\n" + text[next_heading:]


def render_all(root: Path) -> dict[str, str]:
    matrix = build_matrix(root)
    outputs: dict[str, str] = {
        str(MATRIX_JSON): json.dumps(matrix, ensure_ascii=False, indent=1) + "\n"
    }
    for locale in ("en", "zh", "ja", "ko"):
        suffix = DOC_SUFFIX[locale]
        outputs[str(DOCS_DIR / f"plugins{suffix}.md")] = render_plugins_doc(
            matrix, locale
        )
        outputs[str(DOCS_DIR / f"capabilities{suffix}.md")] = (
            render_capabilities_doc(matrix, locale)
        )
    for plugin in matrix["plugins"]:
        module = root / "plugins" / plugin["directory"]
        for readme_name in README_HEADINGS:
            path = module / readme_name
            if not path.is_file():
                continue
            outputs[str(path.relative_to(root))] = update_readme(
                path.read_text(encoding="utf-8"),
                plugin,
                readme_name,
                matrix["reviewedVersions"],
            )
    return outputs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    outputs = render_all(root)
    if args.check:
        stale = [
            rel
            for rel, content in sorted(outputs.items())
            if not (root / rel).is_file()
            or (root / rel).read_text(encoding="utf-8") != content
        ]
        if stale:
            sys.stderr.write(
                "compatibility artifacts are stale; regenerate with "
                "`python3 scripts/generate_compatibility_matrix.py`:\n"
            )
            for rel in stale:
                sys.stderr.write(f"  {rel}\n")
            return 1
        return 0
    for rel, content in sorted(outputs.items()):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    sys.stderr.write(f"wrote {len(outputs)} files\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
