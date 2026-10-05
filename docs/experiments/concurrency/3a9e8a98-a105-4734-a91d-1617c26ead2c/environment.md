# Optimistic concurrency run environment

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

- 상태: Measured
- runId: 3a9e8a98-a105-4734-a91d-1617c26ead2c
- 실행 시각: 2026-09-08T04:46:04.563467600Z

| 구분 | 값 |
| --- | --- |
| Source | e9faf83e091ab63d8f833bedd990355288054662, branch fix/m2-closure-audit, dirty=false |
| Host OS | Windows 11 10.0, amd64 |
| CPU | AMD64 Family 25 Model 68 Stepping 1, AuthenticAMD, 16 available processors |
| Memory | 16,329,510,912 bytes |
| Storage | NVMe SSD, WD PC SN810 SDCPNRY-512G-1006 |
| Runtime | Java 25.0.4.1, Spring Boot 4.1.1, Gradle 9.7.1 |
| JVM options | 실행 property와 locale/encoding 전체 목록을 raw report에 보존 |
| Database | mysql:8.4, MySQL 8.4.11, REPEATABLE-READ, Flyway schema 8 |
| Connection pool | HikariCP, maximumPoolSize 10, connectionTimeout 30000ms |
| Tooling | ConcurrencyBaselineRunner slotq-concurrency-baseline/v3 |
| Container limit | Docker Desktop 기본값, 개별 container CPU·memory override 없음 |
| Network | local loopback과 Docker bridge, traffic shaping 없음 |

애플리케이션은 Windows host에서 실행하고 MySQL만 Testcontainers의 일회용 Docker
container에서 실행했다. credential과 원문 인증 token은 기록하지 않았다.
