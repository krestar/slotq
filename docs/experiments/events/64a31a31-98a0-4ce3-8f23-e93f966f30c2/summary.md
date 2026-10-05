# Event delivery process recovery summary

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

상태: **Measured**

## 결론

WP3 reliability gate는 **PASS**다. 9개 process fault/recovery case가 모두 production
`materialize()`, `claim()`, `process()`, `runCycle()` 및 internal replay 경로를 변경하지 않고
통과했다. 판정은 child 출력이 아니라 fault 직후 DB가 조회 가능한 최초 시점과 recovery 후
MySQL snapshot을 기준으로 했다.

effect와 receipt, delivery `DONE`은 하나의 transaction에서 전체 commit 또는 전체 rollback만
관측됐다. 별도 ACK transaction은 만들지 않았다. stale owner는 정의된 두 JVM 순서에서 새 owner의
effect/DONE 뒤 어떤 durable 상태도 바꾸지 못했다. 세 claim crash는 `CRASH_EXHAUSTED`로 끝났고,
trusted replay는 lifetime attempt와 identity, append-only audit을 유지한 새 cycle에서 수렴했다.

MySQL pause 전에 준비된 child는 production `runCycle()`의 DB access failure를 실제로 관측했고
memory-only 상태를 남기지 않았다. 같은 container를 unpause한 뒤 기존 durable claim에서 회복했다.
batch 3보다 큰 8개 backlog도 process restart 뒤 전부 `DONE`으로
drain됐으며 effect/receipt는 logical target마다 하나였다.

## 적용 범위와 한계

이 결과는 ADR-0007과 `docs/architecture/event-delivery.md`의 M3-WP3 process recovery gate에
한정된다. scheduler activation, 실제 Booking/Waitlist producer/consumer, 외부 provider,
Kafka/Redis, 범용 Inbox 및 M3 전체 종료 판정은 포함하지 않는다.

## 재현

Backend에서 Java 25와 Docker를 사용한다.

```powershell
.\gradlew.bat eventProcessRecovery -PrunId=64a31a31-98a0-4ce3-8f23-e93f966f30c2 "-Poutput=build/reports/experiments/events/64a31a31-98a0-4ce3-8f23-e93f966f30c2"
```

새 실행의 report/snapshot은 지정한 `build/` 경로에 생성한다. 실제 판정은 runner의 durable DB oracle를 따른다.
