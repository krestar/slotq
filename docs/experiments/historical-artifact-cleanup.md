# Historical 실행 산출물 정리

PR [#139](https://github.com/krestar/slotq/pull/139)의 정책을 Issue
[#141](https://github.com/krestar/slotq/issues/141)에 따라 현재 tree에 적용했다.
과거 commit/history와 production 동작은 변경하지 않는다.

## 분류와 보존

| 분류 | 처리와 근거 |
| --- | --- |
| Run별 JSON/CSV/gzip, provider/container 출력, lock dump, hash/manifest와 중간 실패 output | 제거. 실행 시 `build/`에 생성하며 재현 가능한 runner/oracle는 유지 |
| MySQL gap/next-key/INSERT_INTENTION, registration/append fence 관찰 | 원자료 삭제 전에 [gap finding](booking-capacity-gap-lock-finding.md)과 Waitlist architecture에 관찰·종료 oracle·한계가 남아 있음을 대조. 실제 MySQL 진단/회귀 유지 |
| Kafka fault/comparison/drill | [M5 summary](m5-transport/README.md), [closure](m5-transport/closure.md), ADR-0008에 당시 결과·환경·채택 제약 유지. 새 실행의 raw 재계산/codec/integrity capability 유지 |
| Waitlist process recovery | [다섯 case의 실제 경계와 관찰](../architecture/waitlist-process-recovery.md), child-JVM/DB outage runner와 durable oracle 유지 |
| Promtool synthetic series와 expected result | Raw 실행 결과가 아닌 재현 입력. `infra/observability/kafka-query-contract.test.json`으로 이동해 유지 |
| 환경/workload/fault matrix/결론 Markdown | 재현 조건과 판단·limitation을 표현하므로 유지. Raw inventory와 삭제된 링크 정리 |

Historical timing/UUID/row bytes를 새 실행에서 동일하게 만들 수 있다는 뜻은 아니다.
과거 bytes가 필요하면 Git history를 사용한다. 현재 source를 재실행할 때는 새 provenance와
fresh gitignored output을 사용하고 historical PASS를 현재 revision의 새 PASS로 표기하지 않는다.
기존 summary에서 확인하지 못한 관찰을 추가하지 않았다.

## Artifact 의존 테스트

고정 historical 파일만 읽던 `ConcurrencyBaselineRawEvidenceTests`, `EventRawEvidenceTests`,
`EventProcessRecoveryEvidenceTests`와 Waitlist recovery의 historical report/hash 검사 한 개를
제거했다. 이 검사는 실제 Product/fault를 실행하지 않았다.

- `ConcurrencyBaselineSmokeTests`, `OptimisticConcurrencyExperimentTests`와 support test 유지.
- `EventBoundaryTests`, `EventExperimentRunner` / `EventEvidence`, `EventProcessRecoveryRunner`의 actual transaction/fault/DB 판정 유지.
- `WaitlistProcessRecoveryEvidenceTests`의 두 Spring scheduler overload pause 검증 그대로 유지.
- `M5OperationsDrillEvidenceTests`의 SQL failure 누락, rollback 중 attempt 변경, authority/audit 오류 거부와 integrity bytes 보존은 synthetic input과 TempDir로 계속 검증. Physical drill은 `M5OperationsDrillRunner`가 담당.
- `M5CanonicalEvidenceTests`, `M5TransportEvidenceTests`, `WaitlistBaselineEvidenceTests`는 synthetic codec/oracle 회귀이므로 유지.
- `Kafka*EvidenceIntegrityTests`는 새 fault run의 durable snapshot을 재계산하는 opt-in oracle. Historical 파일의 보존 여부만 검사하는 테스트와 구분해 유지.

`backend/build.gradle`의 functional task/property와 fault/response-loss/locking harness는 유지한다.
`verify-fault.ps1` 기본 출력과 process runner의 재현 command는 gitignored `backend/build/`를 사용한다.
전체 `docs/experiments/`를 가리는 ignore rule은 추가하지 않는다.
