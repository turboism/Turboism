---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.deformer-batch-rename
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, rename, batch-edit
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 디포머 이름 일괄 변경

## 기능 개요

자손 오브젝트 이름과 연결된 파라미터를 바탕으로 현재 모델의 워프 및 회전 디포머에 대한 이름 제안을 생성합니다. 미리 보기에서 변경할 항목을 확인하고 선택한 뒤 일괄 적용할 수 있습니다.

## 요구 사항 및 호환성

Turboism 0.67.0 이상 버전과 열려 있는 Cubism 모델이 필요합니다. 아래 생성된 호환성 정보는 SDK에서 도출된 것이며 실제 호스트 검증을 의미하지 않습니다.

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

`./gradlew :plugins:deformer-batch-rename:jar` 명령으로 빌드한 뒤, Turboism 플러그인 관리에서 JAR을 설치하고 활성화합니다.

## 사용 방법

1. Cubism 모델을 열고 **Turboism → 플러그인 → 디포머 이름 일괄 변경**을 선택합니다.
2. 네이티브 워프/회전 아이콘, 기존 이름, 변경할 이름, 적용 체크박스의 4개 열을 확인합니다.
3. 이름을 변경할 행을 선택합니다. 네 번째 열 머리글을 클릭하면 전체 선택 또는 해제할 수 있으며, 이름이 변경되지 않는 행은 선택할 수 없습니다.
4. **확인**을 클릭하면 일괄 적용됩니다. **취소**, 창 닫기 또는 Escape 키를 누르면 변경 사항이 적용되지 않습니다.
5. Cubism의 실행 취소(Undo)를 사용하면 전체 일괄 변경을 한 번에 되돌릴 수 있습니다.

변경할 이름은 규칙에 따라 자동으로 생성되며 이 대화상자에서 직접 편집할 수 없습니다.

말단 자손 이름의 최장 공통 접두사를 사용하며, 공통 접두사가 없으면 가장 짧은 이름을 사용합니다(길이가 같으면 모델 순서 기준). 비어 있는 자식 디포머는 자신의 이름으로 부모 추론에 참여하며, 자식이 없는 디포머는 기존 이름을 유지합니다. 디포머 자체에 연결된 파라미터의 표시 이름(없으면 ID)을 뒤에 추가하며, 같은 그룹의 X/Y/Z 접미사는 연결 순서대로 병합됩니다.

### 이름 지정 예시

| 말단 자손 이름 | 연결된 파라미터 | 변경할 이름 |
| --- | --- | --- |
| `머리카락1`, `머리카락1 그림자` | 없음 | `머리카락1` |
| `머리카락1`, `머리카락1 그림자` | `머리X`, `머리Y` | `머리카락1 머리XY` |
| `왼눈`, `오른눈 그림자` | 없음 | `왼눈` |

표시 이름만 변경됩니다. 오브젝트 ID, 계층 구조 및 파라미터 바인딩은 변경되지 않으며, 중복된 이름은 자동으로 해결하지 않습니다.

## 기능

- `cubism.model.read`
- `cubism.model.write`
- `ui.dialog.contribute`
- `ui.menu.contribute`
- `ui.status.notify`

## 권한

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.action.register`
- `turboism.ui.dialog.contribute`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`

## 개인정보 및 데이터

모든 처리는 로컬에서 수행되며 네트워크 연결, 파일 내보내기 또는 설정 저장을 수행하지 않습니다.

## 상태 및 제한 사항

릴리스 후보 버전입니다. Cubism 5.3.03에서 미리 보기 및 두 가지 네이티브 디포머 아이콘 유형이 실기 확인되었습니다(네이티브 아이콘 12행, 텍스트 폴백 없음). 지원되는 모든 버전에서의 전체 이름 변경 및 실행 취소 검증은 아직 진행 중입니다. 표시 이름 중복이 허용되며 ID는 변경되지 않습니다. 일괄 변경은 단일 네이티브 실행 취소 트랜잭션으로 처리됩니다.

## 문제 해결

먼저 모델을 여세요. 미리 보기가 열려 있는 동안 모델을 전환하거나 계층 구조, 이름 또는 파라미터 바인딩을 변경한 경우 취소하고 다시 여세요. 오래된 미리 보기는 확인 시 거부됩니다. 트랜잭션 실패 시 플러그인 로그와 보고된 트랜잭션 결과를 확인하세요.

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

[문제 보고](https://github.com/turboism/Turboism/issues). 리포지토리 라이선스를 따릅니다.
