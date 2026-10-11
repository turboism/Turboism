# Cubism Editor 호환성 — capability 매트릭스

> `scripts/generate_compatibility_matrix.py`로 생성됨. 버전 커버리지 변경 후 다시 생성하세요(`--check`는 `devCheck`에서 강제됨).

검토 데이터에서 생성한, 검토된 Editor 아티팩트별로 허용되는 capability/SDK 표면 뷰.

## capability 그룹 × 검토 버전

범례: ✓ 그룹 내 모든 capability 허용 · ◐ 부분 허용 · ✗ 해당 버전에서 불허. 허용 = 정적 검증 레코드가 그 버전에서 capability를 인정하고, 이를 축소하는 선택자 계약/클래스 핀도 그 버전을 인정함.

| capability 그룹 | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 애니메이션과 타임라인 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 아트메시 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 자동 흔들림 읽기 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 자동 백업 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 클리핑 마스크 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Cubism Core | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 디포머 편집 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 편집 레벨 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 편집 세션 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 에디터 UI 표면 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 지오메트리 읽기 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 편집 기록 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 통합 브리지 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 메시 도구 세션 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 메시 도구 모음 기여 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 기타 모델 표면 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 모델 인스턴스 읽기 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 모델링 사용자 도구 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 오브젝트 계층 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 파라미터와 바인딩 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 파라미터 키 편집 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 파라미터 구조 편집 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 파트와 인스펙터 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 파트/오브젝트 편집 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 성능 통계 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 물리 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 플러그인 UI 표면 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 프로젝트 읽기 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 보호된 내보내기 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PSD 원본 이미지 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 선택 편집 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 시작 억제 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 텍스처와 원본 이미지 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 텍스처 아틀라스 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 실행 취소 복귀 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 워프 디포머 미러 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 워크스페이스 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

- `cubism.editor-model.animation-eval`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.animation-timeline.write`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.auto-yure.read`: 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.autobackup.backup` (자동 백업 생성): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.autobackup.settings`: 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.editor-ui.embedded-panel` (임베디드 패널 도킹): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.editor-ui.main-toolbar` (메인 도구 모음 통합): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.editor-ui.top-menu` (상단 메뉴 통합): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.geometry.read` (메시 지오메트리 읽기): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-history.semantic-read` (기록의 의미적 디코딩): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.integration.edit-api-bridge.dispatch`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.integration.external-app-settings.edit-toggle` (외부 앱 편집 토글): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.mesh-tool.session` (메시 편집 도구 세션): 검증된 레코드 없음 5.0.00–5.1.03
- `ui.mesh-toolbar.contribute` (메시 도구 모음 기여): 검증된 레코드 없음 5.0.00–5.0.06
- `ui.mesh-toolbar.contribute` (메시 도구 모음 기여): 검증됐으나 계약/핀으로 축소(적응 대기) 5.1.00–5.1.03
- `cubism.editor-model.default-keyform-lock.write`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.native-control-appearance.write`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.write` (에디터 모델 쓰기): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.model-instance.read` (활성 모델 인스턴스 읽기): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.modeling-tool.selection-brush` (모델링 선택 브러시): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.object-hierarchy.apply-deformer-to-children`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.parameter-combined.write`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.parameter-definition.write`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.parameter-structure.write` (파라미터 구조 쓰기): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.add-parameter`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.add-parameter-group`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter-group`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.edit-parameter`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.edit-parameter-group`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.move-parameter`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.move-parameter-group`: 검증된 레코드 없음 5.0.00–5.1.01
- `cubism.editor-model.part-inspector.write`: 검증된 레코드 없음 5.0.00–5.2.03
- `cubism.editor-model.part-opacity.write`: 검증된 레코드 없음 5.0.00–5.2.03
- `cubism.protected-export.orchestration` (보호된 내보내기 오케스트레이션): 검증된 레코드 없음 5.0.00–5.0.06
- `cubism.editor-model.psd.raw-image.export`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.editor-model.psd.raw-image.replace` (PSD 원본 이미지 교체): 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.texture-atlas.auto-layout-hook`: 검증된 레코드 없음 5.0.00–5.1.03
- `cubism.texture-atlas.native-layout-invocation`: 검증된 레코드 없음 5.0.00–5.1.03

## 버전 제약이 있는 SDK 표면

`@CubismEditor`가 검토 집합의 진부분 집합으로 해석되는 표면만 나열합니다. 나열되지 않은 SDK API는 모든 검토 버전에서 사용 가능합니다(capability 허용에는 따름).

| API | 종류 | 버전 |
|---|---|---|
| `dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#edit()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorSession()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorUi()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasPolygonLayouts()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#artifacts()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupAfterSave()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupNow()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#registerSyncTarget()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#settings()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#statuses()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#updateSettings()` | method | 5.1.00–5.3.04 |
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
| `dev.turboism.sdk.cubism.model.AlphaComposition` | type | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Deformers#applyToChildren()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Drawable#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#exportRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#replaceRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#alphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#maskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setMaskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setOpacity()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi` | type | 5.2.00–5.3.04 |

## 일부 버전에서만 사용 가능한 Editor 명령

허용 집합이 전체 검토 집합과 다른 명령만 나열합니다. 런타임 게이트는 `EditorCommand#supports`입니다.

| 명령 | 열거형 | 버전 | 성격 |
|---|---|---|---|
| `ADJUST_BOUNDING_BOX_ANGLE` | EditorCommand | 5.2.03–5.3.04 | 실제 경계 |
| `DELETE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `ENABLE_PLAYBACK_CACHE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `EXPAND_WARPDEFORMER` | EditorCommand | 5.3.00–5.3.04 | 실제 경계 |
| `HIDE_OR_RESTORE_PALETTE` | EditorCommand | 5.0.03–5.3.04 | 미확인 |
| `MESH_EDIT_RESET` | EditorCommand | 5.3.02–5.3.04 | 실제 경계 |
| `MOVE_END` | EditorCommand | — | 실제 경계 |
| `MOVE_END_WORKSPACE` | EditorCommand | — | 실제 경계 |
| `OPEN_FAQ_PAGE` | EditorCommand | 5.0.00–5.2.03 | 실제 경계 |
| `PARAM_CONTROLLER_CONVERT_TO_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `PARAM_CONTROLLER_LINE_PARAM_CTRL_REVERSE_DIRECTION` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `PARAM_CONTROLLER_PREVIEW_BAKE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `PARAM_CONTROLLER_SPLIT_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `PART_PALETTE_CMD_CHANGE_VISIBLE_GUIDE_IMAGE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `SELECT_CHILD_ELEMENTS_OF_DEFORMER` | EditorCommand | 5.2.03–5.3.04 | 실제 경계 |
| `SELECT_OBJECT_BY_CURRENT_KEY_VALUE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `SETTING_GUIDES_MODELING_ADD_CENTER` | EditorCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SHOW_ARTMESH_POINT` | EditorCommand | 5.1.00–5.3.04 | 미확인 |
| `SHOW_DEFAULT_ZOOM` | EditorCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SHOW_FULL_SCENE` | EditorCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SHOW_HOME_DIALOG` | EditorCommand | 5.1.00–5.3.04 | 미확인 |
| `SHOW_PARAM_CONTROLLER_TARGET_TRACKING` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `SHOW_PARAM_CTRL_MARKER` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `SHOW_POPUP_HOVERING_OBJECT` | EditorCommand | 5.2.03–5.3.04 | 실제 경계 |
| `SWITCH_HIGHTLIGHT_CONFLICT_POLYGON` | EditorCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SWITCH_LOOP_EDITING_SUPPORT` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `SYNC_PARAMETERS_ACROSS_TABS` | EditorCommand | 5.0.05–5.3.04 | 미확인 |
| `UPDATE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 미확인 |
| `AUTO_CREATE_DEFORMERS` | EditorParameterizedCommand | 5.1.00–5.3.04 | 미확인 |
| `CHANGE_ALL_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 실제 경계 |
| `CHANGE_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 실제 경계 |
| `CREATE_YURE` | EditorParameterizedCommand | 5.1.00–5.3.04 | 미확인 |
| `DISPLAY_MULTI_PASTE_SHAPE_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 미확인 |
| `EXTERNAL_APP_SETTING` | EditorParameterizedCommand | 5.1.00–5.3.04 | 미확인 |
| `FADE_SETTING` | EditorParameterizedCommand | 5.3.00–5.3.04 | 실제 경계 |
| `OPEN_PARAMETER_GROUP_SETTING` | EditorParameterizedCommand | 5.1.02–5.3.04 | 미확인 |
| `PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 미확인 |
| `PARAM_CTRL_CALC_ORDER_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 미확인 |
| `REFLECT_FADE` | EditorParameterizedCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.3.00–5.3.04 | 실제 경계 |
| `SHOW_ROTATE3D_SETTING_DIALOG` | EditorParameterizedCommand | 5.1.00–5.3.04 | 미확인 |
| `SHOW_SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.0.00–5.2.03 | 미확인 |
| `IMPORT_SCENE_FROM_ANIMATION` | EditorFileCommand | 5.0.03–5.3.04 | 미확인 |
| `EXPORT_AS_TEMPLATE` | EditorFileCommand | 5.1.00–5.3.04 | 미확인 |

## 런타임 허용 핀

검증된 capability를 특정 버전으로 좁히는 선택자 계약과 클래스 핀 테이블(기능은 존재하지만 적응이 미완료이거나 다른 버전에서는 사용 불가).

| 소스 | 심볼 | 버전 |
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

## 버전 단조성 원칙

같은 마이너 라인 안에서 새 패치는 이전 패치의 상위 집합입니다. `5.x.y`에서 허용된 capability와 명령은 `5.x.y'`(`y' > y`)에서도 허용됩니다. 마이너 라인을 넘는 차이(예: `5.2.03`과 `5.3.00`)는 회귀가 아닌 실제 호스트 경계입니다.

## 런타임 capability 조회

플러그인은 버전 문자열로 분기하지 말고 capability를 조회하세요. `CubismFacade`의 각 접근자는 `isAvailable()`이 있는 센티널을 반환하고, `EditorCommand#supports(String)` / `supportedVersions()`가 명령별 집합을 노출하며, 어노테이션이 있는 표면은 호스트가 허용하지 않으면 `CubismEditorApiUnavailableException`으로 실패 차단됩니다.

## 실기 검증 태스크

`validation/cubism-version-matrix/campaign-jobs.json`의 태스크×버전별 최신 FINAL 결과를 capability 영역별로 집계. ✓ 통과 · ◇ 확인된 경계(CAPABILITY_BOUNDARY/ADMISSION_STATIC) · ? 미확인(인프라 재시도, 드리프트, 부적합, 불명 또는 미분류).

| 태스크 | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `about-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `acp` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `animation` |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? |
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
| `edit-protocol` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? | ? |
| `edit-session` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `editor-ui` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `external-psd-edit-pipeline` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `external-psd-fixture-preparation` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `glcanvas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `guard-5302` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `incremental-update` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `integration` | ? |  | ? |  |  |  | ◇ | ◇ |  |  | ? | ✓ | ✓ | ? | ? | ✓ | ? | ? | ? | ? |
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
| `performance` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | ✓ | ✓ | ? |  | ◇ | ◇ | ✓ |  | ◇ | ✓ | ✓ | ✓ |
| `performance-settings` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `plugin-management-direct-jar-restart` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-management-direct-jar-stage` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? |  |  |
| `project-workspace` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? |  |  |
| `protected-export` |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |  | ? | ✓ |  | ? | ? | ✓ |
| `psd` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ◇ | ✓ | ✓ | ✓ | ◇ |
| `review056` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `selection-brush-coordinate-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-round-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-edit` | ? | ? | ? | ? | ? | ? | ? |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `semantic-history-native-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |
| `semantic-history-primary` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `separate-save-path` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ? | ✓ | ◇ | ◇ | ✓ | ✓ | ✓ |
| `session-standard` | ? |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  | ◇ |
| `settings-edge-index` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `settings-page` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `startup` | ✓ | ? | ? | ✓ | ? | ? | ✓ | ? | ? | ? | ✓ | ? | ? | ? | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `texture` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `texture-atlas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `texture-relations-smoke` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5203-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |  |  |  |
| `tlprod-5203-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `tlprod-5302-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5302-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5303-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `tlprod-5303-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `turbo-mesh` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `update-check` | ✓ | ✓ | ? | ? | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ? | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `vertex-upload-skip` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `warp-deformer` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `warp-deformer-alt-symmetry` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-glue` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-point-link` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `workspace` | ? | ◇ | ◇ | ◇ | ◇ | ? | ◇ | ✓ | ? | ? | ✓ | ? | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ✓ |
