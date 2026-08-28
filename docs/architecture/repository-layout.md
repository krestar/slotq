# Repository Layout

SlotQ는 Product Backend, thin SPA와 로컬 Infrastructure를 하나의 저장소에서 관리하는
Product monorepo다. Root는 특정 runtime의 build project가 아니라 저장소 전체의 문서,
정책과 공통 개발 진입점을 소유한다.

## Top-level boundaries

| 경계 | 책임 | 소유하지 않는 항목 |
| --- | --- | --- |
| `backend/` | Spring Boot source, Gradle Wrapper, test, build와 Flyway migration | Frontend dependency, container runtime data |
| `frontend/` | React·TypeScript·Vite source, npm manifest, test와 static asset build | Product business rule, server secret |
| `infra/` | 로컬·운영 container와 service configuration | 애플리케이션 schema migration, generated database data, credential |
| `docs/` | Architecture, ADR, Roadmap와 experiment record | 실행 가능한 application source |
| `.github/` | Issue·PR template, repository policy와 CI workflow | 애플리케이션별 build implementation |

아직 구현하지 않은 애플리케이션이나 service를 표현하기 위한 빈 module은 만들지 않는다.
새로운 독립 실행 단위가 실제로 필요해질 때 현재의 평평한 최상위 경계와 `apps/` 같은
추가 grouping 중 어느 쪽이 더 단순한지 다시 검토한다.

## Build and execution

Backend는 `backend/`를 Gradle project root로 사용한다.

```bash
cd backend
./gradlew test
./gradlew clean build
```

Frontend가 추가되면 `frontend/`를 독립 npm package root로 사용한다. Backend와 Frontend의
검증 명령은 독립적으로 유지하고, 실제 Product flow가 생긴 뒤 API contract 수준의 통합
검증을 추가한다.

저장소 전체를 하나의 build tool로 감싸기 위한 Nx, Turborepo 또는 root Gradle aggregation은
현재 도입하지 않는다. 반복되는 공통 실행 문제가 실제로 생겼을 때 최소한의 orchestration
진입점을 검토한다.

## Infrastructure and schema ownership

MySQL image version, container 설정과 필요한 local configuration은 `infra/`가 소유한다.
Database volume, log와 generated data는 repository에 commit하지 않는다. Secret과 실제
credential도 저장소 밖에서 주입한다.

Flyway migration은 Infrastructure 설정이 아니라 Product Backend의 database contract다.
따라서 migration은 `backend/src/main/resources/db/migration/`에서 Backend source와 함께
versioning하고 test한다. 향후 독립 service가 별도 schema를 소유하게 되면 해당 service가
자신의 migration을 소유한다.

Migration의 호환성 및 rollback 기준은
[Database Migration Compatibility](database-migrations.md)에 기록한다.
