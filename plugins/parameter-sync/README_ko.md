---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-sync
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, sync, link, workflow
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 매개변수 동기화 플러그인

> **Turboism 공식 플러그인** · **상태: 프리뷰**

통합 단일 창에서 정규화된 백분율 매핑을 통해 체크된 매개변수 간의 실시간 Cubism 매개변수 값 동기화를 제공합니다.

| 세부 정보 | 값 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.parameter-sync` |
| 카테고리 | `modeling` |
| 태그 | parameter, sync, link, workflow |
| Cubism 필요 여부 | 예 |
| 인터페이스 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- 슬라이더 드래그, 텍스트 입력 또는 외부 제어 시 체크된 모든 매개변수에 정규화 백분율 진행도를 즉시 실시간 동기화합니다.
- 단일 통합 창 디자인: 상단 전체 스위치 및 활성화 수량 통계, 중간 테이블에서 매개변수별 독립 체크박스 제공.
- 0 지연 이벤트 기반 아키텍처 및 프로그래밍 방식 쓰기 억제 메커니즘을 적용하여 진동 피드백 루프와 버벅임을 제거합니다.
- 네이티브 모델 GUID를 키로 사용하여 세션 간에 지속적으로 설정을 저장합니다.

## 요구 사항 및 호환성

- **SDK API 범위:** `[0.67.0,1.0.0)`.
- **Cubism:** Cubism이 필요합니다. Turboism은 현재 검토된 에디터 아티팩트 `5.0.00`부터 `5.3.04`까지 허용하며, 선언된 서비스 및 기능을 사용할 수 있을 때 호스트 기능을 노출합니다.
- **인터페이스 모드:** `swing`.
- **플러그인 종속성:** 선언되지 않음.

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

이 공식 플러그인은 Turboism에 번들로 제공되며 **Plugin Market**을 통해서도 이용할 수 있습니다. **플러그인 관리**에서 설치된 플러그인을 활성화, 비활성화 또는 제거할 수 있습니다.

## 사용 방법

1. 매개변수 팔레트에서 임의의 매개변수를 마우스 우클릭합니다.
2. **매개변수 동기화...**를 선택하여 통합 구성 창을 엽니다.
3. 함께 연결할 매개변수를 체크하고 **저장 후 닫기**를 클릭합니다.
4. 팔레트나 캔버스에서 체크된 매개변수를 드래그하면 다른 모든 체크된 매개변수가 실시간으로 동기화됩니다.

## 기능

| 선언된 기능 | 사용자 효과 |
|---|---|
| `cubism.parameter.read` | 활성 모델에서 매개변수 값, 범위 및 이름을 읽습니다. |
| `cubism.parameter.write` | 대상 매개변수에 동기화된 값을 씁니다. |
| `cubism.model.observe` | 모델 열기/닫기 라이프사이클 이벤트를 관찰하여 동기화 규칙을 복원합니다. |
| `event.subscribe` | 실시간 매개변수 값 변경 이벤트를 구독합니다. |
| `ui.context-menu.contribute` | 매개변수 팔레트 컨텍스트 메뉴에 매개변수 동기화 액션을 추가합니다. |
| `ui.status.notify` | 동기화 상태 및 토글 알림을 표시합니다. |
| `file.read` | 플러그인 저장소에서 저장된 매개변수 동기화 규칙을 읽습니다. |
| `file.write` | 플러그인 저장소에 매개변수 동기화 규칙을 유지합니다. |

## 권한

| 권한 | 범위 | 요청 이유 |
|---|---|---|
| `turboism.action.register` | `application` | 매개변수 동기화 관리자 및 토글 액션을 등록합니다. |
| `turboism.ui.context-menu.contribute` | `application` | 매개변수 팔레트 컨텍스트 메뉴에 매개변수 동기화 액션을 추가합니다. |
| `turboism.cubism.model.read` | `application` | 동기화를 위해 매개변수 값, 범위, 이름 및 활성 모델 GUID를 읽습니다. |
| `turboism.cubism.model.write` | `application` | 대상 매개변수에 동기화된 값을 씁니다. |
| `turboism.cubism.model.observe` | `application` | 매개변수 값 변경 및 모델 라이프사이클 이벤트를 관찰합니다. |
| `turboism.event.subscribe` | `application` | 매개변수 값 변경 이벤트를 구독합니다. |
| `turboism.file.read` | `application` | 플러그인 저장소에서 저장된 매개변수 동기화 규칙을 읽습니다. |
| `turboism.file.write` | `application` | 플러그인 저장소에 매개변수 동기화 규칙을 저장합니다. |
| `turboism.ui.status.notify` | `application` | 동기화 규칙 변경, 루프 경고 및 토글 상태를 알립니다. |

## 개인정보 및 데이터

### 네트워크

네트워크 연결을 수행하지 않습니다.

### 로컬 데이터

모델 GUID를 키로 하여 샌드박스 플러그인 저장소에 매개변수 동기화 구성을 유지합니다. 모델 GUID 및 매개변수 ID 문자열만 포함합니다.

### 원격 측정

이 플러그인은 원격 측정을 전송하지 않습니다.

플러그인 라이프사이클 및 오류 기록은 플러그인 ID가 첨부되어 Turboism의 세션 로그 및 Cubism의 호스트 로그에 나타날 수 있습니다.

## 상태 및 제한 사항

- **상태:** 프리뷰.
- 정규화 백분율 매핑을 사용하여 활성 모델에서 체크된 매개변수를 동기화합니다.
- 활성 모델 문서가 필요합니다.
- 2개 미만의 매개변수가 체크된 경우 동기화가 비활성화됩니다.

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| 매개변수가 동기화되지 않음 | 매개변수 동기화가 활성화되어 있고 최소 2개 이상의 매개변수가 체크되어 있는지 확인하십시오. |
| 컨텍스트 메뉴 항목이 누락됨 | 에디터에 활성 모델이 로드되어 있는지 확인하십시오. |
| 동기화 일시 중지됨 | 컨텍스트 메뉴에서 매개변수 동기화 전환을 사용하거나 대화상자의 전체 스위치를 확인하십시오. |

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

- **프로젝트 웹사이트:** [https://turboism.dev](https://turboism.dev)
- **게시자:** Turboism Contributors
- **라이선스:** 프로젝트 라이선스
- **플러그인 ID:** `dev.turboism.plugin.parameter-sync`
