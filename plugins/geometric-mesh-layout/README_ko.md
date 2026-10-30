---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.geometric-mesh-layout
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# 기하학 메시 배치

## 기능 개요

메시 편집 툴바에 기하학 메시 배치 도구를 추가합니다. 활성화 후 캔버스에 중심 앵커를 설정하고 대화상자에서 원형 또는 정다각형, 반지름, 변 수를 설정하여 점과 선을 생성합니다.

## 요구 사항 및 호환성

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

Turboism 공식 배포 패키지에 포함되어 있으며 플러그인 관리에서 활성화하거나 비활성화할 수 있습니다.

## 사용 방법

1. 아트메시의 수동 메시 편집 모드로 들어갑니다.
2. 메시 편집 툴바에서 **기하학 메시 배치** 버튼을 클릭합니다.
3. 캔버스를 클릭하거나 드래그하여 중심 앵커를 설정합니다.
4. 대화상자에서 **모양 프리셋**(원주, 삼각형, 사각형, 육각형, 팔각형), **반지름**, **변 수** 및 **중심점 생성**, **주변 점에서 중심까지 연결선 생성**, **동심원 생성**(주수 슬라이더)을 설정하고 **확인**을 클릭합니다.

## 기능

| Capability | Effect |
|---|---|
| `cubism.mesh.custom-tools` | 네이티브 메시 편집 툴바에 사용자 정의 도구를 등록합니다. |

## 권한

| Permission | Scope | Effect |
|---|---|---|
| `turboism.ui.toolbar.mesh.contribute` | `application` | 메시 툴바에 버튼을 추가합니다. |
| `turboism.cubism.model.read` | `application` | 정점 좌표와 카메라 투영을 읽습니다. |
| `turboism.cubism.model.write` | `application` | 정점과 엣지를 생성합니다. |

## 개인정보 및 데이터

네트워크 연결이나 원격 측정이 없으며 생성된 점과 선은 네이티브 실행 취소/다시 실행에 포함됩니다.

## 상태 및 제한 사항

메시 편집 모드 내에서만 작동합니다.

## 문제 해결

도구 버튼이 보이지 않으면 단일 아트메시로 메시 편집 모드에 들어갔는지 확인하십시오.

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

[Issues](https://github.com/turboism/Turboism/issues) · [프로젝트 라이선스](../../LICENSE)
