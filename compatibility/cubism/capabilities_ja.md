# Cubism Editor 互換性 — ケイパビリティマトリクス

> `scripts/generate_compatibility_matrix.py` により生成。バージョンカバレッジ変更後は再生成してください（`--check` は `devCheck` で強制されます）。

レビュー済みデータから生成した、各レビュー済み Editor 成果物で許可されるケイパビリティ/SDK サーフェスのビュー。

## ケイパビリティグループ × レビュー済みバージョン

凡例: ✓ グループ内の全ケイパビリティが許可 · ◐ 部分的に許可 · ✗ そのバージョンでは不許可。許可 = 静的検証レコードがそのバージョンでケイパビリティを認め、かつ縮小するセレクタコントラクト/クラスピンもそのバージョンを認める。

| ケイパビリティグループ | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| アニメーションとタイムライン | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| アートメッシュ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 自動揺れ読み取り | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 自動バックアップ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| クリッピングマスク | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Cubism Core | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| デフォーマ編集 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 編集レベル | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 編集セッション | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| エディタ UI サーフェス | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ジオメトリ読み取り | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 編集履歴 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 統合ブリッジ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| メッシュツールセッション | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| メッシュツールバー贡献 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| その他のモデルサーフェス | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| モデルインスタンス読み取り | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| モデリングカスタムツール | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| オブジェクト階層 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パラメータとバインド | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パラメータキー編集 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パラメータ構造編集 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パーツとインスペクタ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パーツ・オブジェクト編集 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| パフォーマンス統計 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 物理演算 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| プラグイン UI サーフェス | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| プロジェクト読み取り | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 保護付きエクスポート | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PSD raw 画像 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 選択編集 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 起動抑制 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| テクスチャと raw 画像 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| テクスチャアトラス | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 元に戻す復帰 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ワープデフォーマミラー | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ワークスペース | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

- `cubism.editor-model.animation-eval`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.animation-timeline.write`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.auto-yure.read` (自動揺れ読み取り): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.autobackup.backup` (自動バックアップ作成): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.autobackup.settings`: 検証済みレコードなし 5.0.00–5.0.06
- `cubism.editor-ui.embedded-panel` (埋め込みパネル docking): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.editor-ui.main-toolbar` (メインツールバー統合): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.editor-ui.top-menu` (トップメニュー統合): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.geometry.read` (メッシュジオメトリ読み取り): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-history.semantic-read` (履歴の意味的デコード): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.integration.edit-api-bridge.dispatch`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.integration.external-app-settings.edit-toggle` (外部アプリ編集トグル): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.mesh-tool.session` (メッシュ編集ツールセッション): 検証済みレコードなし 5.0.00–5.1.03
- `ui.mesh-toolbar.contribute` (メッシュツールバー贡献): 検証済みレコードなし 5.0.00–5.0.06
- `ui.mesh-toolbar.contribute` (メッシュツールバー贡献): 検証済みだがコントラクト/pin で縮小（適合待ち） 5.1.00–5.1.03
- `cubism.editor-model.default-keyform-lock.write`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.native-control-appearance.write`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.write` (エディタモデル書き込み): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.model-instance.read` (アクティブモデルインスタンス読み取り): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.modeling-tool.selection-brush` (モデリング選択ブラシ): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.object-hierarchy.apply-deformer-to-children`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.parameter-combined.write`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.parameter-definition.write`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.parameter-structure.write` (パラメータ構造書き込み): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.add-parameter`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.add-parameter-group`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter-group`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.edit-parameter`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.edit-parameter-group`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.move-parameter`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.move-parameter-group`: 検証済みレコードなし 5.0.00–5.1.01
- `cubism.editor-model.part-inspector.write` (パーツインスペクタ書き込み): 検証済みレコードなし 5.0.00–5.2.03
- `cubism.editor-model.part-opacity.write` (パーツ不透明度書き込み): 検証済みレコードなし 5.0.00–5.2.03
- `cubism.protected-export.orchestration` (保護付きエクスポートオーケストレーション): 検証済みレコードなし 5.0.00–5.0.06
- `cubism.editor-model.psd.raw-image.export`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.editor-model.psd.raw-image.replace` (PSD raw 画像の置き換え): 検証済みレコードなし 5.0.00–5.1.03
- `cubism.texture-atlas.auto-layout-hook`: 検証済みレコードなし 5.0.00–5.1.03
- `cubism.texture-atlas.native-layout-invocation`: 検証済みレコードなし 5.0.00–5.1.03

## バージョン制約付き SDK サーフェス

`@CubismEditor` がレビュー済み集合の真部分集合に解決されるサーフェスのみを列挙。未掲載の SDK API は全レビュー済みバージョンで利用可能（ケイパビリティ許可には従う）。

| API | 種別 | バージョン |
|---|---|---|
| `dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#edit()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorSession()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorUi()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasPolygonLayouts()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#artifacts()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupAfterSave()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupNow()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#registerSyncTarget()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#settings()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#statuses()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#updateSettings()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.command.EditorCommandService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.edit.CancelSource` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.DeformerOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditAlphaBlend` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditArtMeshData#EditArtMeshData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditCancelledException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditColorBlend` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditDeformerAttachMode` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditGlueData#EditGlueData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditLabelColor#EditLabelColor()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditLabelColorType` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectData` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectKind` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectNode#EditObjectNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectSnapshot#EditObjectSnapshot()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterGroupNode#EditParameterGroupNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterKeyCondition#EditParameterKeyCondition()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterNode#EditParameterNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterStructureEntry` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditPartData#EditPartData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditRectangle#EditRectangle()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditRotationDeformerData#EditRotationDeformerData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSession` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionCloseResult#EditSessionCloseResult()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionOpenResult#EditSessionOpenResult()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionOptions#EditSessionOptions()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionService` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionState` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditUnavailableException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditWarpDeformerData#EditWarpDeformerData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.ParameterKeyOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.ParameterStructureOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.PartObjectOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.SelectionOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContribution#ExportSettingsContribution()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContributionService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContributionService.Unavailable` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsDecision#ExportSettingsDecision()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.Outcome` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshBrush` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditParticipation` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditUiService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditor` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshToolContext` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshToolRegistry` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AlphaComposition` | type | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AutoYure` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AutoYureBinding` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AutoYureConfig` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Deformers#applyToChildren()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Drawable#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelInstance` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#exportRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#replaceRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#alphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#maskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setMaskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setOpacity()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.YureDeformConfig` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingBrush` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingToolContext` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingToolRegistry` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.psd.PsdEditFile` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.recentfile.RecentFileService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.transaction.AuthoringTransactionService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation` | type | 5.2.00–5.3.04 |

## 一部バージョンでのみ利用可能な Editor コマンド

許可集合が全レビュー済み集合と異なるコマンドのみを列挙。実行時のゲートは `EditorCommand#supports`。

| コマンド | 列挙 | バージョン | 性質 | 公式導入バージョン |
|---|---|---|---|---|
| `ADJUST_BOUNDING_BOX_ANGLE` | EditorCommand | 5.2.03–5.3.04 | 実境界 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `DELETE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `ENABLE_PLAYBACK_CACHE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `EXPAND_WARPDEFORMER` | EditorCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `HIDE_OR_RESTORE_PALETTE` | EditorCommand | 5.0.03–5.3.04 | 実境界 | [5.0.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) |
| `MESH_EDIT_RESET` | EditorCommand | 5.3.02–5.3.04 | 実境界 | [5.3.02](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `MOVE_END` | EditorCommand | — | 実境界 | — |
| `MOVE_END_WORKSPACE` | EditorCommand | — | 実境界 | — |
| `OPEN_FAQ_PAGE` | EditorCommand | 5.0.00–5.2.03 | 実境界 | — |
| `PARAM_CONTROLLER_CONVERT_TO_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `PARAM_CONTROLLER_LINE_PARAM_CTRL_REVERSE_DIRECTION` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `PARAM_CONTROLLER_PREVIEW_BAKE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `PARAM_CONTROLLER_SPLIT_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `PART_PALETTE_CMD_CHANGE_VISIBLE_GUIDE_IMAGE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SELECT_CHILD_ELEMENTS_OF_DEFORMER` | EditorCommand | 5.2.03–5.3.04 | 実境界 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SELECT_OBJECT_BY_CURRENT_KEY_VALUE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SETTING_GUIDES_MODELING_ADD_CENTER` | EditorCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SHOW_ARTMESH_POINT` | EditorCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `SHOW_DEFAULT_ZOOM` | EditorCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SHOW_FULL_SCENE` | EditorCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SHOW_HOME_DIALOG` | EditorCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `SHOW_PARAM_CONTROLLER_TARGET_TRACKING` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SHOW_PARAM_CTRL_MARKER` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SHOW_POPUP_HOVERING_OBJECT` | EditorCommand | 5.2.03–5.3.04 | 実境界 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SWITCH_HIGHTLIGHT_CONFLICT_POLYGON` | EditorCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SWITCH_LOOP_EDITING_SUPPORT` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `SYNC_PARAMETERS_ACROSS_TABS` | EditorCommand | 5.0.05–5.3.04 | 実境界 | [5.0.05](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) |
| `UPDATE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `AUTO_CREATE_DEFORMERS` | EditorParameterizedCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `CHANGE_ALL_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 実境界 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `CHANGE_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 実境界 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `CREATE_YURE` | EditorParameterizedCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `DISPLAY_MULTI_PASTE_SHAPE_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `EXTERNAL_APP_SETTING` | EditorParameterizedCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `FADE_SETTING` | EditorParameterizedCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `OPEN_PARAMETER_GROUP_SETTING` | EditorParameterizedCommand | 5.1.02–5.3.04 | 実境界 | [5.1.02](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `PARAM_CTRL_CALC_ORDER_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 実境界 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) |
| `REFLECT_FADE` | EditorParameterizedCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.3.00–5.3.04 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `SHOW_ROTATE3D_SETTING_DIALOG` | EditorParameterizedCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |
| `SHOW_SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.0.00–5.2.03 | 実境界 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) |
| `IMPORT_SCENE_FROM_ANIMATION` | EditorFileCommand | 5.0.03–5.3.04 | 実境界 | [5.0.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) |
| `EXPORT_AS_TEMPLATE` | EditorFileCommand | 5.1.00–5.3.04 | 実境界 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) |

## 確認済みバージョン境界

Cubism Editor 自体に機能が存在しない確認済みホスト境界。公式更新履歴およびレビュー済みバイトコードで検証済み。

| サーフェス / 識別子 | 非対応バージョン | 公式導入バージョン | 理由 |
|---|---|---|---|
| `dev.turboism.sdk.cubism.model.AlphaComposition` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Alpha composition (blend modes) was introduced in Cubism 5.3. |
| `dev.turboism.sdk.cubism.model.Part#maskIds` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Part mask-id access requires the 5.3 inspector schema. |
| `dev.turboism.sdk.cubism.model.Part#alphaComposition` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Alpha composition exists only on Cubism 5.3. |
| `dev.turboism.sdk.cubism.model.Part#setAlphaComposition` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Alpha composition exists only on Cubism 5.3. |
| `dev.turboism.sdk.cubism.model.Part#setOpacity` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Part opacity writes through the inspector are 5.3-only. |
| `dev.turboism.sdk.cubism.core.CoreCapabilities#parameterRepeat` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Core parameterRepeat support is 5.3-only. |
| `cubism.editor-model.part-inspector.write` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | The extended part-inspector write schema (mask/alpha fields) exists only on 5.3. |
| `command:ADJUST_BOUNDING_BOX_ANGLE` | 5.0.00–5.2.02 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.03. |
| `command:SELECT_CHILD_ELEMENTS_OF_DEFORMER` | 5.0.00–5.2.02 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.03. |
| `command:SHOW_POPUP_HOVERING_OBJECT` | 5.0.00–5.2.02 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.03. |
| `command:CHANGE_ALL_MESH_DRAW_ORDER` | 5.0.00–5.2.02 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.03. |
| `command:CHANGE_MESH_DRAW_ORDER` | 5.0.00–5.2.02 | [5.2.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.03. |
| `command:EXPAND_WARPDEFORMER` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:SETTING_GUIDES_MODELING_ADD_CENTER` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:SHOW_DEFAULT_ZOOM` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:SHOW_FULL_SCENE` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:SWITCH_HIGHTLIGHT_CONFLICT_POLYGON` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:FADE_SETTING` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only (renamed from the 5.2 label-copy command). |
| `command:REFLECT_FADE` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command is 5.3-only. |
| `command:SHOW_LABEL_COPY_DIALOG` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | 5.3 rename of the 5.2 SHOW_SHOW_LABEL_COPY_DIALOG command. |
| `command:MESH_EDIT_RESET` | 5.0.00–5.3.01 | [5.3.02](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Command added in Cubism 5.3.02. |
| `command:OPEN_FAQ_PAGE` | 5.3.00–5.3.04 | — | Command was dropped in Cubism 5.3 (present through 5.2.03). |
| `command:MOVE_END` | 5.0.00–5.3.04 | — | Dead constant: the id exists in no reviewed jar. |
| `command:MOVE_END_WORKSPACE` | 5.0.00–5.3.04 | — | Dead constant: the id exists in no reviewed jar. |
| `cubism.editor-model.auto-yure.read` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Auto sway (Yure) was introduced in Cubism 5.1.00. |
| `cubism.editor-model.part-opacity.write` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Part opacity writes through the inspector were introduced in Cubism 5.3.00. |
| `cubism.integration.external-app-settings.edit-toggle` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | External application integration was introduced in Cubism 5.1.00. |
| `cubism.integration.edit-api-bridge.dispatch` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | External application integration WebSocket dispatch was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.model.AutoYure` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Auto sway (Yure) was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.model.AutoYureBinding` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Auto sway (Yure) was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.model.AutoYureConfig` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Auto sway (Yure) was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.model.YureDeformConfig` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Auto sway (Yure) was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.CubismFacade#edit` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | External application edit API was introduced in Cubism 5.1.00. |
| `dev.turboism.sdk.cubism.model.Drawable#setAlphaComposition` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Alpha composition (blend modes) was introduced in Cubism 5.3.00. |
| `dev.turboism.sdk.cubism.model.Part#setMaskIds` | 5.0.00–5.2.03 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | Part clipping masks (offscreen drawing) were introduced in Cubism 5.3.00. |
| `command:HIDE_OR_RESTORE_PALETTE` | 5.0.00–5.0.02 | [5.0.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) | Command added in Cubism 5.0.03 (replacing 5.0.00–5.0.02 CMD_HIDE_PALETTE). |
| `command:IMPORT_SCENE_FROM_ANIMATION` | 5.0.00–5.0.02 | [5.0.03](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) | Command added in Cubism 5.0.03. |
| `command:SYNC_PARAMETERS_ACROSS_TABS` | 5.0.00–5.0.04 | [5.0.05](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates5/) | Command added in Cubism 5.0.05 (replacing 5.0.00–5.0.04 CMD_CHANGE_SYNC_PARAMETER_TO_SAME_MODEL). |
| `command:AUTO_CREATE_DEFORMERS` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (automatic deformer generation). |
| `command:CREATE_YURE` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (Auto Sway / Yure generation). |
| `command:EXPORT_AS_TEMPLATE` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (modeling template enhancements). |
| `command:EXTERNAL_APP_SETTING` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (external application integration). |
| `command:SHOW_ARTMESH_POINT` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (show/hide mesh vertices). |
| `command:SHOW_HOME_DIALOG` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (Home dialog). |
| `command:SHOW_ROTATE3D_SETTING_DIALOG` | 5.0.00–5.0.06 | [5.1.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.00 (3D rotation expression). |
| `command:OPEN_PARAMETER_GROUP_SETTING` | 5.0.00–5.1.01 | [5.1.02](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/) | Command added in Cubism 5.1.02. |
| `command:DELETE_DEFORMER_ORIGINAL_SHAPE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (restore original shape support for deformers). |
| `command:DISPLAY_MULTI_PASTE_SHAPE_DIALOG` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (special paste shape dialog). |
| `command:ENABLE_PLAYBACK_CACHE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00. |
| `command:PARAM_CONTROLLER_CONVERT_TO_LINE_PARAM_CTRL` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PARAM_CONTROLLER_LINE_PARAM_CTRL_REVERSE_DIRECTION` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PARAM_CONTROLLER_PREVIEW_BAKE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PARAM_CONTROLLER_SPLIT_LINE_PARAM_CTRL` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PARAM_CTRL_CALC_ORDER_SETTING_DIALOG` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:PART_PALETTE_CMD_CHANGE_VISIBLE_GUIDE_IMAGE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00. |
| `command:SELECT_OBJECT_BY_CURRENT_KEY_VALUE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (select objects associated with parameter keys). |
| `command:SHOW_PARAM_CONTROLLER_TARGET_TRACKING` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:SHOW_PARAM_CTRL_MARKER` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (parameter controllers in animation). |
| `command:SWITCH_LOOP_EDITING_SUPPORT` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (loop editing support). |
| `command:UPDATE_DEFORMER_ORIGINAL_SHAPE` | 5.0.00–5.1.03 | [5.2.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-2/) | Command added in Cubism 5.2.00 (restore original shape support for deformers). |
| `command:SHOW_SHOW_LABEL_COPY_DIALOG` | 5.3.00–5.3.04 | [5.3.00](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/) | 5.0–5.2 command id renamed to SHOW_LABEL_COPY_DIALOG in Cubism 5.3.00. |

## ランタイム許可ピン

検証済みケイパビリティを特定バージョンに絞り込むセレクタコントラクトとクラスピンテーブル（機能は存在するが適合が未完了、または他バージョンでは利用不可）。

| ソース | シンボル | バージョン |
|---|---|---|
| class-pins/`float-array-parse.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`image-archive-reuse.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`incremental-update.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`matrix-scratch.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`mesh-mirror.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`mesh-triangulation-hash.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`model-update-skip.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`startup-suppression.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`texture-upload-preparation.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`uniform-location-lifecycle.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`warp-alt-mirror.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`warp-position-projection.json` | `ClassPinTable` | 5.2.00–5.3.04 |

## バージョン単調性の原則

同一マイナーライン内では新しいパッチは古いパッチの上位集合です。`5.x.y` で許可されるケイパビリティとコマンドは `5.x.y'`（`y' > y`）でも許可されます。マイナーラインを跨ぐ差異（例: `5.2.03` と `5.3.00`）は回帰ではなく実際のホスト境界です。

## 実行時のケイパビリティ照会

プラグインはバージョン文字列で分岐せず、ケイパビリティを照会してください。`CubismFacade` の各アクセサは `isAvailable()` を持つセンチネルを返し、`EditorCommand#supports(String)` / `supportedVersions()` がコマンドごとの集合を公開し、注釈付きサーフェスはホストが許可しない場合 `CubismEditorApiUnavailableException` でフェイルクローズします。

## 実機検証タスク

`validation/cubism-version-matrix/campaign-jobs.json` のタスク×バージョン別最新 FINAL 結果をケイパビリティ領域別に集約。✓ 合格 · ◇ 確認済み境界（CAPABILITY_BOUNDARY/ADMISSION_STATIC）· ? 未確認（インフラ再試行・ドリフト・不適・不明・未分類）。

| タスク | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `about-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `acp` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `animation` |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ✓ |
| `artmesh-mirror-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `atlas-image-observe` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `atlas-image-shadow` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `atlas-polygon` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `autobackup` |  |  |  |  |  |  |  | ◇ | ◇ | ? | ◇ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `boundingbox-warp-mirror` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `boundingbox-warp-mirror-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `clipmask` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `core` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `cursor-coalesce` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `edit-protocol` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ? | ✓ |
| `edit-session` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `editor-ui` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `external-psd-edit-pipeline` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `external-psd-fixture-preparation` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `glcanvas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `guard-5302` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `incremental-update` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `integration` | ◇ |  | ? |  |  |  | ◇ | ◇ |  |  | ? | ✓ | ✓ | ? | ? | ✓ | ? | ? | ? | ✓ |
| `media-perf` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `mesh-edit` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `model-import-3d` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `model-import-3d-n2` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `model-import-3d-user-entry` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `model-update-skip` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `modeling-tool` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `native-resource` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `official-core` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `official-viewer` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `parameter` | ? | ? | ◇ | ◇ | ◇ | ◇ | ◇ | ? | ◇ | ◇ | ◇ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `performance` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | ✓ | ✓ | ✓ |  | ◇ | ◇ | ✓ |  | ◇ | ✓ | ✓ | ✓ |
| `performance-settings` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `plugin-management-direct-jar-restart` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-management-direct-jar-stage` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? |  |  |
| `project-workspace` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? |  |  |
| `protected-export` |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |  | ✓ | ✓ |  | ? | ? | ✓ |
| `psd` |  |  |  |  |  |  |  |  |  |  |  | ◇ |  |  | ✓ | ◇ | ✓ | ✓ | ✓ | ✓ |
| `review056` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `selection-brush-coordinate-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-round-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-edit` | ✓ | ? | ? | ? | ? | ? | ✓ |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `semantic-history-native-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |
| `semantic-history-primary` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `separate-save-path` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ? | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `session-standard` | ? |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  | ◇ |
| `settings-edge-index` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `settings-page` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `startup` | ✓ | ? | ? | ✓ | ? | ? | ✓ | ✓ | ? | ? | ✓ | ✓ | ? | ? | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `texture` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `texture-atlas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ? |  |
| `texture-relations-smoke` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5203-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |  |  |  |
| `tlprod-5203-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `tlprod-5302-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5302-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5303-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `tlprod-5303-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `turbo-mesh` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `update-check` | ✓ | ✓ | ? | ? | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `vertex-upload-skip` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `warp-deformer` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `warp-deformer-alt-symmetry` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-glue` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-point-link` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `workspace` | ◇ | ◇ | ◇ | ◇ | ◇ | ? | ◇ | ✓ | ? | ? | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ✓ |
