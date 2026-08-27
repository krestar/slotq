# Contributing to SlotQ

SlotQ는 `Issue → branch → Pull Request → CI → squash merge` 흐름으로 작업합니다.

`main`은 직접 작업하는 브랜치가 아니라 항상 통합 가능한 상태를 유지하는 브랜치입니다.

## 작업 흐름

1. Issue에서 목적, 범위, Acceptance Criteria를 정의합니다.
2. Issue 성격에 맞는 작업 브랜치를 만듭니다.
3. 한 가지 목적에 집중한 작은 단위의 commit으로 작업합니다.
4. 로컬 검증을 수행합니다.
5. PR을 만들고 관련 Issue를 `Closes #번호`로 연결합니다.
6. CI와 review conversation을 확인합니다.
7. squash merge합니다.
8. 작업 브랜치는 merge 후 삭제합니다.

## Branch Naming

```text
feat/reservation-hold
fix/waitlist-duplicate-offer
refactor/reservation-state-machine
test/postgres-concurrency
chore/github-settings
```

권장 prefix:

- `feat`
- `fix`
- `refactor`
- `test`
- `docs`
- `chore`
- `build`
- `ci`
- `perf`

Issue 번호나 사용자명을 branch 이름에 강제하지 않습니다.

## Commit / PR Title

Commit과 PR 제목은 Conventional Commit 형식을 사용합니다.

```text
type(scope): 한글 제목
```

예시:

```text
feat(reservation): 예약 HOLD 상태 전이 추가
fix(waitlist): 중복 승급 제안을 방지
refactor(domain): 예약 상태 변경 책임 분리
test(persistence): 동시 예약 정합성 테스트 추가
chore(ci): GitHub Actions 설정 정리
docs(readme): 로컬 실행 방법 보완
```

대표 type:

`feat` · `fix` · `refactor` · `test` · `docs` · `chore` · `build` · `ci` · `perf`

scope는 변경 책임이 드러나는 실제 영역을 사용합니다. 제목 설명은 자연스러운 한국어로 작성합니다.

## PR 원칙

- PR 하나는 하나의 명확한 목적을 가집니다.
- 독립적인 기능 여러 개를 하나의 PR에 섞지 않습니다.
- 변경 이유, 검증 결과, 보안·데이터 영향을 PR template에 기록합니다.
- unresolved review conversation이 있다면 해결한 뒤 merge합니다.
- 일반 PR은 squash merge합니다.

## 로컬 검증

Backend 개발에는 JDK 21이 필요합니다. 별도의 Gradle 설치 없이 `backend/`의 Gradle Wrapper를 사용합니다.

Windows PowerShell:

```powershell
Set-Location backend
.\gradlew.bat test
.\gradlew.bat clean build
java -jar build\libs\slotq-0.0.1-SNAPSHOT.jar
```

macOS/Linux:

```bash
cd backend
./gradlew test
./gradlew clean build
java -jar build/libs/slotq-0.0.1-SNAPSHOT.jar
```

Backend 기준선은 Java 21 LTS, Spring Boot 4.1.1 GA, Gradle Wrapper 9.7.1입니다. Spring Boot 4.1.1은 Java 21과 Gradle 9.x를 공식 지원하며, Wrapper로 로컬과 CI의 Gradle 버전 및 실행 진입점을 통일합니다.

## Engineering Principles

- Product Backend를 먼저 완성하고 AI Platform을 그 위에 확장합니다.
- 동시성, 데이터 정합성, 상태 전이, 멱등성, 실패 복구를 핵심 설계 대상으로 다룹니다.
- 기술은 필요성이 있는 문제를 해결하기 위해 도입합니다.
- 중요한 선택과 trade-off는 ADR 또는 실험 기록으로 남깁니다.
- Secret, API Key, 토큰, 비밀번호, 개인정보를 commit·Issue·PR·로그에 남기지 않습니다.

## Dependency Update

Dependabot PR은 자동 merge하지 않습니다.

- patch/minor: CI와 변경 영향을 확인한 뒤 merge할 수 있습니다.
- major 또는 breaking 가능성이 있는 변경: 별도로 검토합니다.
- framework, build tool, lint/build ecosystem의 major upgrade는 CI 통과만으로 merge하지 않습니다.
