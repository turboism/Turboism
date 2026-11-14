---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.turbo-mesh
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.70.0,)"
requiresCubism: true
interface: embedded
---

# Turbo Mesh

> **Turboism 공식 플러그인** · **상태: Preview**

아트메시 맞춤 점 배치: 곡률에 따른 경계 재샘플링, 자율 영역 분할, 제약 들로네 삼각분할을 수행하고 한 번의 실행 취소 단위로 확정합니다.

| 항목 | 값 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.turbo-mesh` |
| 카테고리 | `modeling` |
| 태그 | mesh, artmesh, editing |
| Turboism API | `[0.70.0,)` |
| Cubism 필요 | 예 |
| 인터페이스 | `embedded` |
| 라이선스 | Project License |

## 기능 개요

- 아트메시 컨텍스트 메뉴에 **Turbo Mesh: 점 재배치** 항목을 추가합니다.
- 메시의 경계 링을 추출하여 곡률 적응형 간격으로 재샘플링합니다. 뾰족한 끝에는 앵커 점과 촘촘한 간격을, 완만한 곡선에는 현 오차가 제한된 샘플을 배치합니다.
- 형상을 자율 영역(4-연결 성분, 좁은 목 부분에서 재귀 분할)으로 나누고 영역 면적에 따라 내부 점 예산을 배분합니다.
- 영역 경계와 보호 여백을 유지하는 시드 고정 지터 격자에 내부 샘플을 배치합니다.
- 제약 들로네로 전체를 삼각분할하여 영역 구분선을 고정 엣지로 만들고, 에디터의 실행 취소 단위로 결과를 확정합니다. 실행 취소/다시 실행이 정상적으로 동작합니다.

## 요구 사항 및 호환성

- **Turboism API:** `[0.70.0,)`.
- **Cubism:** Cubism이 필요합니다. 현재 Turboism은 검토된 정확한 Editor 아티팩트 `5.2.03`, `5.3.02`, `5.3.03`을 허용하며, 이 플러그인은 선언된 서비스와 기능이 모두 사용 가능할 때만 각 호스트 연동 기능을 노출합니다.
- **인터페이스 모드:** `embedded`.
- **플러그인 의존성:** 선언된 항목이 없습니다.

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 5.2.00–5.2.03, 5.3.00–5.3.04（이전 Cubism 버전에는 기능 자체가 없음）.
전체 기능(검토된 버전): 5.2.00–5.2.03, 5.3.00–5.3.04.
- 메시 지오메트리 읽기 에서만 5.2.00–5.3.04（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

이 공식 플러그인은 **스토어 후보**이며 아직 스토어에 게시되지 않았습니다. 마켓플레이스 게시 전에는 Turboism 공식 릴리스 패키지로 설치한 뒤 **플러그인 관리**에서 활성화하세요. 필요하지 않을 때는 같은 창에서 비활성화하거나 제거할 수 있습니다.

## 사용 방법

1. 디포머, 파트 또는 작업 영역 오브젝트 패널에서 아트메시를 선택합니다.
2. 마우스 오른쪽 버튼을 클릭하고 **Turbo Mesh: 재배치**를 선택합니다.
3. 모드 대화상자에서 배치 모드(자동 혼합 / 부드러운 띠 / 부드러운 면 / 강체 지오메트리)를 선택합니다.
4. 확인 대화상자에서 원본 메시와 새 메시의 통계를 비교합니다. 확인하면 반영되고 취소하면 아무것도 바뀌지 않습니다.
5. 메시는 한 번의 실행 취소 단위로 재배치되며 실행 취소/다시 실행이 평소처럼 동작합니다.

파라미터 키폼에 연결된 아트메시는 확인 대화상자에 경고가 표시됩니다. 호스트가 대화상자를 표시할 수 없으면 키폼이 있는 아트메시는 재배치가 완전히 차단됩니다.

현재 조정은 JVM 시스템 속성으로 수행합니다(설정 대화상자는 계획 중입니다).

| 속성 | 기본값 | 의미 |
|---|---|---|
| `turboMesh.targetPoints` | 320 | 전체 점 수의 목표값(소프트) |
| `turboMesh.features` | `false` | 0.2.0 특징 인식 배치 경로 사용 |
| `turboMesh.features.preset` | `AUTO_BLEND` | 프리셋: `AUTO_BLEND` / `SOFT_STRIP` / `SOFT_SHEET` / `RIGID_GEOMETRY` |
| `turboMesh.features.weightTexture` | 프리셋별 | 텍스처 엣지 밀도 가중치(소프트 신호) |
| `turboMesh.features.directionInfluence` | 프리셋별 | 샘플링에 대한 방향장 영향(0 = 끔) |
| `turboMesh.confirm` | `dialog` | `never`는 확인 대화상자를 건너뜀(자동화/검증용) |
| `turboMesh.alphaThreshold` | `8` | 텍스처 알파 영역 임계값 |

### 자동 메시 생성 알고리즘

이 플러그인은 에디터의 자동 메시 생성 진입점에 두 가지 알고리즘을 등록합니다. 둘 다 에디터 기본 생성기와 나란히 표시되며, 기본 항목을 선택하면 호스트 생성기는 전혀 변경되지 않습니다.

| 알고리즘 | 동작 | 프리셋 |
|---|---|---|
| `turbo-mesh.region` | 텍스처를 영역으로 분할하고 모든 영역 구분선을 고정 엣지로 유지하며 영역별로 삼각분할합니다. | 성김, 상세, 단단한 표면 |
| `turbo-mesh.adaptive` | 곡률에 따라 경계를 재샘플링하고 영역을 인식하는 점으로 내부를 채웁니다. | 균형, 부드러운 띠, 부드러운 면, 강체 지오메트리 |

영역 알고리즘은 아트메시의 텍스처 픽셀을 읽습니다. 호스트가 픽셀을 제공하지 않는 메시는 추측한 메시를 만들지 않고 `no-texture` 사유로 거부됩니다. 자동 값을 가진 모든 컨트롤에는 보조 스위치가 있으며, 스위치가 켜져 있으면 해당 값은 실루엣 크기에서 계산되고 옆의 숫자는 무시됩니다.

런타임에 자동 메시 레지스트리가 없으면 플러그인은 한 줄을 기록하고 아트메시 컨텍스트 메뉴 동작을 기존과 똑같이 유지합니다.

## 기능

| Capability | Effect |
|---|---|
| `cubism.mesh.read` | 배치 계획을 위해 선택한 아트메시의 지오메트리를 읽습니다. |
| `cubism.mesh.write` | 생성한 메시 지오메트리를 아트메시에 기록합니다. |
| `cubism.mesh.automesh-algorithms` | 에디터의 자동 메시 생성 진입점에 영역 알고리즘과 적응형 알고리즘을 제공합니다. |
| `ui.context-menu.contribute` | 아트메시 컨텍스트 메뉴에 배치 항목을 추가합니다. |
| `ui.dialog.contribute` | 배치 모드 선택기와 변경 전후 확인 대화상자를 표시합니다. |
| `ui.status.notify` | 호스트 상태 알림으로 배치 결과와 실패를 알립니다. |

## 권한

| Permission | Scope | Effect |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 배치 계획을 위해 선택한 아트메시의 지오메트리를 읽습니다. |
| `turboism.cubism.model.write` | `application` | 생성한 메시 지오메트리를 아트메시에 기록합니다. |
| `turboism.action.register` | `application` | 사용자 정의 배치 동작을 등록합니다. |
| `turboism.ui.context-menu.contribute` | `application` | 아트메시 컨텍스트 메뉴에 배치 항목을 추가합니다. |
| `turboism.ui.dialog.contribute` | `application` | 배치 모드 선택기와 변경 전후 확인 대화상자를 표시합니다. |
| `turboism.ui.status.notify` | `application` | 배치 결과와 실패를 알립니다. |
| `turboism.cubism.mesh.automesh.contribute` | `application` | 에디터의 자동 메시 생성 진입점에 영역 알고리즘과 적응형 배치 알고리즘을 제공합니다. |

## 개인정보 및 데이터

### 네트워크

네트워크 연결을 하지 않습니다.

### 로컬 데이터

모델 데이터나 플러그인 설정을 저장하지 않습니다. 배치 결과는 열려 있는 모델 문서에 일반 편집으로만 기록됩니다.

### 원격 측정

이 플러그인은 원격 측정 데이터를 전송하지 않습니다.

플러그인 수명 주기와 실패 기록은 플러그인 ID와 함께 Turboism 세션 로그와 Cubism 호스트 로그에 나타날 수 있습니다.

## 상태 및 제한 사항

- **상태:** Preview.
- 지오메트리 기록은 **현재 키폼**에만 적용됩니다. 이미 다른 키폼에서 변형된 메시를 재배치하면 정점 수가 바뀌며 해당 키폼은 자동으로 다시 매핑되지 않습니다. 변형하기 전에 점을 배치하거나 복사본에서 먼저 확인하세요.
- 배치 영역은 메시의 기존 UV 점유 영역(구멍 인식, 토폴로지 유지)에서 도출됩니다. 텍스처 알파 인식 배치는 검토된 픽셀 읽기 표면이 마련되면 확장될 예정입니다.

## 문제 해결

| 증상 | 확인할 사항 |
|---|---|
| 메뉴 항목이 없음 | 플러그인이 활성화되어 있고 선택에 아트메시가 포함되어 있는지 확인하세요. |
| 배치가 실패로 보고됨 | 상태 알림과 세션 로그에서 플래너 진단을 확인하세요. |
| 메시에 변화가 없음 | 메시의 삼각형 토폴로지가 유효하고 잠겨 있지 않은지 확인하세요. |

## 변경 이력

### 0.1.0

- 최초 릴리스: 컨텍스트 메뉴 재배치, 영역 및 적응형 자동 메시 알고리즘, 6개 로케일 카탈로그.

## 지원 및 라이선스

- **프로젝트 웹사이트:** [https://turboism.dev](https://turboism.dev)
- **게시자:** Turboism Contributors
- **라이선스:** Project License
- **플러그인 ID:** `dev.turboism.plugin.turbo-mesh`
