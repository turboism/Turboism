[English](../en/README.md) / [简体中文](../zh/README.md) / [日本語](../ja/README.md) / [한국어](../ko/README.md)

# Turboism

## 면책 조항

Turboism은 독립적인 서드파티 프로젝트이며 Live2D Inc.와 제휴·보증·후원 관계가 없는 비공식 프로젝트입니다(Live2D, Cubism 및 관련 상표는 Live2D Inc. 또는 각 권리자에게 있습니다).

Turboism은 Cubism Editor를 배포하지 않으며, 라이선스 검증 우회, 제한 기능 해제 등 보유한 Cubism 라이선스 범위를 넘는 권한 획득이나 사용을 돕지 않습니다. 정식 라이선스를 보유한 Cubism Editor를 별도로 설치해야 합니다.

본 소프트웨어는 [AGPL-3.0-only](../../LICENSE)에 따라 **있는 그대로** 제공됩니다. 설치 전에 [EULA](EULA.md)를 확인하고 프로젝트 파일을 별도로 백업해 주세요.

## 소개

Turboism은 **Live2D Cubism Editor**를 위한 Windows 중심의 런타임 확장 도구이자 플러그인 프레임워크입니다. Java Agent와 공개 SDK를 기반으로 파라미터·메시 도구, PSD 유틸리티, 팔레트 확장, 로컬 자동화 등 모델링 작업 흐름을 확장합니다(사용 가능한 기능은 설치된 플러그인과 Editor 버전에 따라 달라집니다).

## 현재 기능

### 프레임워크

- **성능 및 JVM 튜닝**: 선택적 렌더링·모델 업데이트·클리핑 마스크·텍스처 업로드·메시 분할 최적화를 제공하며, Cubism JVM 프로파일(ZGC / GraalVM 등) 전환과 시작 대화상자 억제를 지원합니다.
- **플러그인 플랫폼 및 마켓**: 내장 플러그인 관리와 Ed25519 서명 기반 플러그인 마켓(설치·업데이트·활성화 전환), 권한 및 Cubism 버전별 허용 검사, 프레임워크 업데이트 확인을 제공합니다.
- **호스트 셸 및 개발 기반**: 통합 Turboism 메뉴, 툴바 홈 버튼, 공용 설정·단축키 UI, 저장 경로 기록, 진단 로그, 공개 Java SDK(`dev.turboism:sdk`), 별도 프로세스 GraalJS 스크립트 호스트를 포함합니다.

### 퍼스트파티 플러그인

표준 릴리스 패키지에는 35개의 퍼스트파티 플러그인이 기본 포함됩니다(`parameter-lock`은 플러그인 마켓에서 제공). 플러그인별 Editor 버전 지원은 [플러그인 호환성 매트릭스](../../compatibility/cubism/plugins_ko.md)를 참고하세요:

- **모델링 · 메시 · 디포머**: `turbo-mesh`(곡률 기반 사용자 지정 정점 배치 및 삼각 분할), `geometric-mesh-layout`(원형/다각형 메시 생성), `selection-brush`, `artmesh-mirror`, `mesh-edit-mirror-axis-enhance`, `boundingbox-warp-mirror`, `warp-deformer-alt-symmetry`, `deformer-batch-rename`.
- **파라미터 · 물리 · 애니메이션**: `parameter`(CSV 가져오기/내보내기, 바인딩 반전·이관), `parameter-batch-transfer`, `parameter-sync`, `parameter-lock` *(마켓)*, `physics-editor`(물리 목록 전체 선택 및 상태 유지), `frame-animation-import`(스프라이트 시트·연속 이미지 가져오기).
- **PSD · 3D · 텍스처 아틀라스**: `external-psd-edit`(외부 앱에서 원본 PSD 실시간 편집), `psd-clip-mask-import`, `model-import-3d`(GLB/glTF/OBJ 가져오기), `atlas-maxrects-bssf`, `atlas-dalsoo-polygon`, `atlas-bitmask-nesting`, `atlas-folder-layout`, `texture-atlas-stats`.
- **작업 공간 · UI · 검사**: `ui-theme`, `command-palette`(`Ctrl+K`), `cubism-tab-filter`, `palette-label-style`, `scene-palette-enhancer`, `recent-project-board`, `recent-preview`, `history-panel`(시맨틱 실행 취소 타임라인), `clipmask-viewer`, `perf-stats`, `project-work-time`.
- **백업 · 내보내기 · AI / 자동화**: `webdav-backup`, `protected-export`(Cubism 5.1+ 평탄화·난독화 내보내기), `mcp`(로컬 MCP 서버), `acp`(ACP v1 에이전트 대화 창).

## 플랫폼 지원

- **Windows(x64)** 가 현재 주로 지원 및 유지보수되는 플랫폼입니다.
- **macOS**: Java의 크로스 플랫폼 특성 덕분에 macOS에서도 동작할 가능성이 있습니다(현재는 미검증 미리 보기용 `turboism-<version>.zip`만 제공). 아직 macOS 환경 테스트와 유지보수를 진행하지 못하고 있으므로, macOS를 보유하고 계시고 테스트나 기여로 도움을 주실 분은 [GitHub Issues](https://github.com/turboism/Turboism/issues)로 연락해 주세요.

## Cubism 지원 현황

| Cubism Editor | 지원 상태 |
| --- | --- |
| **5.0.00 – 5.0.06** | 부분 지원 — 자동 백업, 자동 흔들림 읽기, 지오메트리 읽기, 통합 브리지, 모델링 사용자 도구, 보호된 내보내기, 상단 메뉴 통합, MOC 내보내기 읽기, 파트 인스펙터 쓰기 미지원 / 애니메이션·타임라인 부분 지원 |
| **5.1.00 – 5.1.03** | 부분 지원 — 지오메트리 읽기, 모델링 사용자 도구, MOC 내보내기 읽기, 파트 인스펙터 쓰기 미지원 / 애니메이션·타임라인 부분 지원(5.1.02 미만은 통합 브리지도 부분 지원) |
| **5.2.00 – 5.2.03** | 대부분 지원 — 5.3 전용 기능(알파 컴포지션, 파트 인스펙터 쓰기, MOC 내보내기 읽기, Core parameterRepeat)만 미지원 |
| **5.3.00 – 5.3.04** | 완전 지원 — 검증된 모든 기능 그룹 사용 가능 |

목록에 없는 버전이나 미지원 기능은 자동으로 비활성화되며, API별 상세 내역은 [기능 매트릭스](../../compatibility/cubism/capabilities_ko.md)에서 확인할 수 있습니다.

## 설치

먼저 Cubism Editor를 종료하고 프로젝트를 백업한 뒤, [최신 GitHub Release](https://github.com/turboism/Turboism/releases/latest)에서 패키지를 내려받으세요. 표준 패키지에는 기본 제공 퍼스트파티 플러그인과 오프라인 GraalJS 의존성이 포함되어 있습니다(호환 GraalVM 런타임은 설정 도구에서 별도 설치 가능).

현재 설치 파일에는 코드 서명이 없으므로 실행 전 `.sha256` 파일로 무결성을 확인하는 것을 권장합니다:

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### EXE 설치 프로그램 (Windows 권장)

`TurboismInstaller-<version>.exe`를 실행해 마법사 안내에 따라 설치하세요(설치 화면은 영어, 중국어 간체, 일본어, 한국어 지원 / 실행 시 언어는 `config.json`에서 설정). Cubism 공식 실행 `.bat` 연동은 기본으로 선택되며 제거 시 원본이 자동 복원되고, 업그레이드 시 기존 `disabledPlugins` 설정이 유지됩니다. 설치 후 생성된 Turboism 바로 가기로 실행하세요.

### ZIP (포터블)

1. `turboism-<version>.zip`을 Cubism 설치 폴더가 아닌 별도 폴더에 압축 해제합니다.
2. 설정 도구를 실행해 사용할 Cubism 설치 경로와 플러그인을 선택합니다:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```
3. 생성된 바로 가기 또는 해당 폴더의 `launch-cubism-turboism.bat`으로 Cubism을 실행합니다.

## 개발

### 플러그인 개발

플러그인을 개발할 때는 Turboism 저장소를 클론하거나 빌드할 필요 없이 **JDK 17 이상**만 있으면 됩니다:

1. [templates/plugin-template](../../templates/plugin-template/)을 독립 프로젝트로 복사한 뒤 `./scripts/setup.sh`(Windows는 `.\scripts\setup.ps1`)를 실행해 `turboism-sdk-<version>.jar`와 `turboism-event-processor-<version>.jar`를 내려받아 검증하거나, 직접 `libs/` 폴더에 넣으세요.
2. `./gradlew build`(Windows는 `gradlew.bat build`)로 빌드합니다.

이 저장소 내부에서 테스트할 때는 [데모 플러그인](../../plugins/demo/README.md)([build.gradle.kts](../../plugins/demo/build.gradle.kts), [plugin.json](../../plugins/demo/src/main/resources/META-INF/turboism/plugin.json))을 참고해 다음 명령으로 빌드할 수 있습니다:

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

> 참고: `plugins:demo`는 개발 전용이며 릴리스 패키지에 포함되지 않습니다. 플러그인은 `:sdk`(`dev.turboism:sdk`)만 `compileOnly`로 의존해야 하며 SDK를 JAR에 포함하거나 런타임 내부 구현 및 `com.live2d.*`에 직접 의존해서는 안 됩니다. 수명 주기·권한 규칙은 [플러그인 개발 가이드](../../sdk/plugin-author-guide.md)를 확인하세요.

### 프레임워크 개발

**Git**과 **JDK 17**이 필요합니다(저장소 내 `./gradlew`, Windows는 `gradlew.bat` 사용):

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

- **개발 및 검증 환경**: **Windows x64**에서는 네이티브 Cubism 호스트 검증을 직접 실행할 수 있으며, **Linux**에서는 Xvfb 환경의 헤드리스 Gradle·단위 테스트 및 Proton(Wine) 기반 Cubism 호스트 검증을 지원합니다.
- **로컬 SDK 연동 테스트**: 외부 플러그인 프로젝트에서 로컬 수정 사항을 확인하려면 SDK와 `@SubscribeEvent` 애너테이션 프로세서를 `~/.m2`에 게시할 수 있습니다:
  ```bash
  ./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
  ```
- 모듈 경계, 트랜잭션, 검증 게이트는 [아키텍처 문서](../architecture/ARCHITECTURE.md)를 참고하세요.

## 문서

- [사용자 및 개발자 문서](https://docs.turboism.dev)
- [아키텍처](../architecture/ARCHITECTURE.md) · [로드맵](../ROADMAP.md)
- [SDK API 계약](../../sdk/api-contracts/) · [현재 공개 SDK 표면](../../sdk/api-contracts/current.txt)
- [플러그인 개발 가이드](../../sdk/plugin-author-guide.md) · [플러그인 템플릿](../../templates/plugin-template/) · [데모 플러그인](../../plugins/demo/README.md)
- [릴리스 절차](../RELEASING.md) · [변경 기록](CHANGELOG.md)

## 라이선스

Copyright © 2026 Turboism Contributors. 본 프로젝트는 [AGPL-3.0-only](../../LICENSE)로 배포되며 타사 구성 요소에는 각자의 라이선스가 적용됩니다.
