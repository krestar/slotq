# Pessimistic concurrency run environment

- 상태: Measured
- runId: 4c571add-7867-4326-8cd5-60a899fda418
- 실행 시각: 2026-09-08T04:47:03.274759500Z
- 원자료: [report.json](raw/report.json)

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
