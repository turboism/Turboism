[EN / English](README.md) · [ZH / 简体中文](README_zh.md) · [JP / 日本語](README_ja.md) · [KR / 한국어](README_ko.md)

# Turboism

## 저작권 및 고지

Copyright © 2026 Turboism Contributors. Turboism은 [MIT License](LICENSE)에 따라 제공되는 오픈 소스 소프트웨어입니다.

Turboism은 **독립적인 서드파티 프로젝트**이며, Live2D Inc.와 제휴 관계가 없고 해당 회사의 보증이나 후원을 받지 않습니다. Live2D, Cubism 및 관련 명칭과 상표에 대한 권리는 Live2D Inc. 또는 각 권리자에게 있습니다. Turboism은 Cubism Editor를 배포하거나 해당 라이선스를 제공·대체·우회하지 않습니다. 적법한 라이선스를 취득한 Cubism Editor를 별도로 설치해야 합니다.

설치 전에 [최종 사용자 실행 고지 및 면책 조항](EULA.md)을 읽어 주세요. 이 고지는 MIT License가 부여한 권리를 제한하지 않으며, 중국어 간체 정식 문서가 기준입니다. 소프트웨어는 **있는 그대로** 제공됩니다. 프로젝트 내용을 변경하는 플러그인이나 자동화 기능을 사용하기 전에 독립적인 백업을 보관하세요.

## 프로젝트 소개

Turboism은 **Live2D Cubism Editor**를 위한 Windows 중심의 런타임 확장 도구이자 플러그인 프레임워크입니다. Java Agent와 공개 SDK를 통해 파라미터·메시 도구, PSD 보조 기능, 팔레트 확장, 로컬 자동화 등 모델링 작업을 지원합니다. 사용할 수 있는 기능은 설치된 플러그인, 권한 및 Editor의 정확한 버전에 따라 달라집니다.

## 지원하는 Cubism Editor 버전

| Cubism Editor | 지원 상태 |
| --- | --- |
| **5.2.03** | 해당 버전 전용 어댑터 제공 |
| **5.3.02** | 해당 버전 전용 어댑터 제공 |
| **5.3.03** | 해당 버전 전용 어댑터 제공 |

지원하는 Cubism 호스트 플랫폼은 **Windows x64**입니다. 목록에 없는 Editor 버전의 호환성은 보장하지 않습니다. 필요한 어댑터나 기능이 없으면 안전을 위해 실행을 거부합니다. 지원 버전이라고 해서 모든 플러그인 기능을 사용할 수 있는 것은 아닙니다.

Java 설치 프로그램은 macOS와 Linux에서도 파일을 설치할 수 있습니다. 다만 macOS 패키징은 미리 보기 단계이며 Cubism 호스트 지원은 검증되지 않았습니다. Linux는 설치 프로그램과 파일 배포 동작만을 대상으로 하며 Cubism 호스트로는 지원하지 않습니다.

## 설치

[최신 GitHub Release](https://github.com/turboism/Turboism/releases/latest)에서 패키지와 해당 `.sha256` 파일을 다운로드하세요. 아래 설치 방식 중 **하나만** 선택하면 됩니다. 설치하거나 업데이트하기 전에 Cubism을 종료하고 프로젝트를 백업하세요.

모든 Turboism 설치 프로그램은 **영어, 중국어 간체, 일본어, 한국어**를 지원합니다. 언어 선택은 설치 프로그램 자체의 인터페이스만 바꾸며, 실행 시 Turboism의 언어는 계속 `config.json`의 `locale`로 결정됩니다.

현재 설치 프로그램에는 코드 서명이 없습니다. 실행하기 전에 다운로드한 파일의 SHA-256을 함께 제공되는 체크섬과 비교하세요. 다음은 PowerShell 예시입니다. `<version>`을 다운로드한 버전으로 바꿔 주세요.

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### ZIP — Windows 수동 설정

1. 기본 제공 플러그인이 필요하면 `turboism-<version>-full.zip`을, 플러그인 JAR 없이 런타임만 필요하면 `turboism-<version>-lite.zip`을 선택하세요.
2. **압축 파일 전체**를 별도의 Turboism 폴더에 풀어 주세요. Cubism 설치 폴더에는 풀지 마세요.
3. 압축을 푼 폴더에서 설정 도구를 실행하세요.

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```

   실행 정책 옵션은 해당 PowerShell 프로세스에만 적용됩니다. 사용할 Cubism 설치 경로와 플러그인을 선택한 뒤 저장하세요.
4. 생성된 Turboism 바로 가기 또는 해당 폴더의 `launch-cubism-turboism.bat`으로 Cubism을 실행하세요.

### JAR — Java 설치 프로그램

**Java 17 이상**을 설치한 뒤 실행하세요.

```bash
java -jar "TurboismInstaller-<version>.jar"
```

라이선스와 고지를 확인하고 설치 경로 및 패키지 옵션을 선택해 설치를 완료하세요. 마법사를 시작하면 4개 언어의 언어 선택 대화상자가 먼저 표시되며, `-language eng|chn|jpn|kor`를 전달하면 이 대화상자를 건너뜁니다. Windows에서는 필요에 따라 설치 폴더의 `configure_turboism.ps1`으로 Cubism을 설정한 뒤 Turboism을 통해 실행하세요. macOS/Linux에서 파일을 설치할 수 있다는 것이 해당 플랫폼의 Cubism 호스트 호환성을 의미하지는 않습니다.

### EXE — Windows 권장 방식

`TurboismInstaller-<version>.exe`를 실행하고 설치 마법사에서 설치 경로, 플러그인 및 실행 옵션을 선택하세요. 환영 페이지 전에 언어 선택(영어, 중국어 간체, 일본어, 한국어)이 먼저 표시되며, 설치 프로그램에만 적용됩니다. 설치 후에는 생성된 Turboism 바로 가기를 사용하세요.

Cubism 공식 실행 BAT와의 통합은 **선택 사항**이며 명시적으로 선택해야 합니다. 해시 검증을 적용한 백업을 사용하지만, 나중에 파일을 직접 수정하면 자동 복원이 불가능할 수 있습니다. 이러한 설치 프로그램 관리 백업과 별도로 프로젝트 백업을 보관하세요.

모든 릴리스 패키지에는 관리형 fx 런타임 파일이나 개발 전용 Turboism with fx 플러그인이 포함되지 않습니다.

## 개발

**Git과 JDK 17**이 필요합니다. 저장소에 포함된 Gradle Wrapper를 사용하므로 Gradle을 별도로 설치할 필요가 없습니다.

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 사용하세요. 변경 사항은 별도의 기능 브랜치나 worktree에서 작업하세요.

플러그인을 개발하려면 [데모 플러그인](plugins/demo/README.md), 해당 [빌드 설정](plugins/demo/build.gradle.kts) 및 [플러그인 설명 파일](plugins/demo/src/main/resources/META-INF/turboism/plugin.json)을 참고하세요. 플러그인은 `compileOnly` 범위로 `:sdk`에 의존해야 하며, 런타임 내부 구현이나 `com.live2d.*` 클래스에 직접 의존해서는 안 됩니다.

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

데모는 개발 전용이며 릴리스 패키지에 포함되지 않습니다. 변경 사항을 제출하기 전에 영향받는 부분의 테스트와 `./gradlew devCheck`를 실행하세요. API, 수명 주기, 트랜잭션 및 검증 규칙은 [아키텍처 문서](ARCHITECTURE.md)를 참고하세요.

## 문서

- [사용자 및 개발자 문서](https://docs.turboism.dev)
- [아키텍처](ARCHITECTURE.md)와 [로드맵](ROADMAP.md)
- [SDK API 계약 및 호환성](sdk/api-contracts/), [SDK v7 마이그레이션 안내](sdk/api-contracts/sdk-api-v7-review.md)
- [데모 플러그인](plugins/demo/README.md)
- [Java 설치 프로그램 상세 안내](packaging/java-installer/README-java-installer.md)
- [릴리스 절차](RELEASING.md)와 [변경 기록](CHANGELOG.md)
