---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-project-board
version: 0.68.2
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, viewport, navigation
turboismApi: "[0.70.1,)"
requiresCubism: true
interface: swing
---

# 최근 프로젝트 보드

> **Turboism 공식 플러그인** · **상태: 미리보기**

열려 있는 프로젝트가 없을 때 빈 Cubism 모델링 뷰포트에 최근 프로젝트 썸네일을 표시합니다. 카드를 두 번 클릭하여 프로젝트를 엽니다.

| 세부 정보 | 값 |
|---|---|
| 버전 | `0.68.2` |
| 플러그인 ID | `dev.turboism.plugin.recent-project-board` |
| 카테고리 | `workflow` |
| 태그 | recent-files, preview, viewport, navigation |
| Cubism 필요 여부 | 예 |
| 인터페이스 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- 문서가 열려 있지 않을 때 기본 OpenGL 뷰포트에 최대 8개의 최근 프로젝트를 표시합니다.
- 헤더의 Lucide 아이콘 버튼을 통해 6열 그리드, 3열 그리드, 1행 목록을 전환하고 설정을 저장합니다.
- 레이아웃 전환 버튼 왼쪽에 아이콘 전용 생성 버튼 두 개를 배치합니다. `plus`는 새 모델 프로젝트, `clapperboard`는 새 애니메이션 프로젝트용입니다. 둘 다 호스트의 네이티브 '새로 만들기' 메뉴 명령을 호출하므로 네이티브 대화상자가 열립니다. 호스트가 해당 명령을 제공하지 않으면 그 버튼은 표시되지 않습니다.
- 공식 `recent-preview` 플러그인의 공개 `thumbnail.captured` 이벤트를 통해 썸네일을 가져옵니다.
- 각 카드 오른쪽 위의 휴지통 버튼은 제공자를 통해 해당 프로젝트의 캐시된 썸네일을 삭제합니다. 카드는 '미리보기 없음' 상태로 돌아가고 호버 팝업도 오래된 이미지를 표시하지 않습니다.
- 문서를 열면 보드가 자동으로 숨겨지고, 모든 문서가 닫히면 다시 표시됩니다.
- 카드를 두 번 클릭하여 프로젝트를 엽니다.
- 최근 파일이 없으면 중앙에 "새 프로젝트 생성" 및 "새 애니메이션 프로젝트 생성" 링크를 표시합니다.

## 요구 사항 및 호환성

- **SDK API 범위:** `[0.70.1,)`.
- **Cubism:** Cubism 필요 (5.0.00–5.3.04).
- **인터페이스 모드:** `swing`。
- **플러그인 종속성:** `dev.turboism.plugin.recent-preview` (필수).

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05, 정확한 버전 21개).
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05.
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

이 공식 플러그인은 Turboism에 기본 포함되어 있으며 **플러그인 마켓**을 통해서도 이용할 수 있습니다. **플러그인 관리**에서 활성화, 비활성화 또는 제거할 수 있습니다.

## 라이선스

프로젝트 라이선스. 헤더의 생성 버튼은 Lucide v0.468.0의 `plus`와 `clapperboard`, 레이아웃 버튼은 `layout-grid`, 카드 삭제 버튼은 `trash-2`를 사용합니다(ISC 라이선스).
