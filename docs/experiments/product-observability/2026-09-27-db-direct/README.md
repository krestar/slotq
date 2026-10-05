# Product 관측 DB direct 실행 증거

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

2026-09-27 실행 **PASS**. Synthetic fixture로 실제 Product/DB와 observability stack을 실행했다.
당시 관찰은 현재 revision의 새 실행이 아니다. 후속 query/queue-loss 최소 재검증은
[별도 blocker 기록](../2026-09-27-blocker-regression/README.md)을 따른다.

최종 Backend 검증은 commit `c3fc40c`에서 `./gradlew.bat clean build`로 성공했다.
XML/HTML report 기준 60 suites, 576 tests 중 573 passed, 0 failures/errors, 3 skipped다.
skip은 기존 `SLOTQ_CAPACITY_LOCK_EVIDENCE=true` opt-in 진단 테스트이며 일반 correctness 테스트는 통과했다.
앞선 전체 실행에서 발견한 V14 기대값 두 곳은 V15로 수정하고 관련 12개 테스트를 통과시켰다.
당시 결과는 아래 표에 요약한다. Frontend 변경은 없었다.

| 검증 | 실제 결과와 근거 |
| --- | --- |
| 외부 telemetry 접근 | 익명 Prometheus/Grafana/OTLP 모두 401, 각 machine/operator credential으로 정상 접근 |
| 실제 lock wait | Product cancel이 append fence에서 대기, `SlotqDatabaseLockWait` firing 후 잠금 해제·요청 200·알림 해제 |
| business DEAD | real release target을 handler 없는 worker로 처리하여 DEAD, `SlotqDeadDelivery` firing |
| backlog | target age 60초 + `for: 30s`를 실제 기다린 뒤 `SlotqBacklog` firing |
| 복구 | 기존 trusted replay 1회 및 worker drain, backlog/DEAD 각각 Offer 1·PROMOTED receipt·DONE, 두 알림 해제 |
| 저장된 trace | 실제 HTTP request와 append가 같은 trace, effect는 original link를 가진 별도 trace |
| dashboard | 실제 provisioned Grafana의 인증된 datasource proxy로 34개 panel query 실행·성공 |
| Kafka missing | 모든 예약 Kafka panel 결과가 빈 series. 정상 0으로 보충하지 않음 |
| collector outage | 실제 OTLP ingress container pause, export 5 spans 실패 중 새 Product cancel·Offer 1·receipt·DONE commit |

정상 effect는 직접 seed하지 않았다. HTTP HOLD→등록→취소, production handler, receipt와 worker를 사용했다.
DEAD fault는 handler registry가 없는 worker를 사용하는 test-only 주입이며 원 event·target identity를 유지했다.
고정 business clock은 재현 fixture를 위한 것이고 DB lease/target age 및 Prometheus alert 시간은 실제 clock이다.
설정된 알림 시간을 축소하거나 `created_at`을 과거 값으로 수정하지 않았다.

Grafana evidence는 실제 dashboard provisioning와 panel query의 실행 증거다. 브라우저 픽셀/레이아웃 검사나 production SLO 검증은 아니다.
표본은 bounded advisory inventory이며 partial snapshot, truncation, missing 의미는
[metric 계약](../../../architecture/product-observability-metrics.md)을 따른다. 실제 Kafka emission은 #107/#108 소유다.

## 재현

Java 25와 Docker가 준비된 `backend/`에서 새로운 output directory를 사용한다.

```powershell
.\gradlew.bat observabilityEvidence '-Poutput=build/reports/experiments/product-observability/<new-run>'
```

실행마다 credential을 memory에서 생성하고 disposable containers를 종료한다. 원자료를 덮어쓰지 않는다.
설치/수동 실행: [observability stack](../../../../infra/observability/README.md).
