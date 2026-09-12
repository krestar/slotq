# Event delivery process recovery environment

- Run ID: `64a31a31-98a0-4ce3-8f23-e93f966f30c2`
- Revision: `294a35d510e69d6029a27c10435d28463bc99fba`
- Branch: `fix/event-db-outage-evidence`
- Dirty at run start: `false`
- Java: `25.0.4.1+1-LTS`
- Spring Boot: `4.1.1`
- Gradle: `9.7.1`
- MySQL image / version: `mysql:8.4` / `8.4.11`
- Isolation: `REPEATABLE-READ`
- Docker: `29.7.2`
- Test policy: max attempts 3, lease 4s, effect timeout 2s, lock wait 1s, batch 3, retry delay 0s

테스트 policy는 bounded fault experiment 값이며 production SLO가 아니다. 모든 child JVM은 같은
MySQL container/database를 사용했고 DB credential은 evidence에 기록하지 않았다.
