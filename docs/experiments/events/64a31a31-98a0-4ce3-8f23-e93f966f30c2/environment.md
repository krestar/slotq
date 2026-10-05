# Event delivery process recovery environment

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

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
