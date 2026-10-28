# DB_DIRECT JVM·MySQL 장애 복구

Issue [#182](https://github.com/krestar/slotq/issues/182)의 지원 구성은 단일 Product/MCP/Agent JVM,
MySQL 8.4와 DB_DIRECT다. MySQL의 업무 상태·immutable event·전달 target·receipt가 복구 기준이다.
Kafka는 [ADR-0008](../adr/0008-m5-event-transport-and-runtime-status.md)의 experimental topology를 유지한다.
Run·누적 예산·취소·late result는 [ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)에 따라
프로세스 안에만 존재한다. 재시작 시 이전 intent·승인으로 mutation을 자동 실행하지 않는다.

## 실행 전 고정할 조건

- JDK 25, Docker의 disposable MySQL, 격리된 합성 Tenant, 현재 source revision/dirty·source digest,
  스키마·이미지·DB 격리 수준과 실제 설정을 기록한다. 실제 credential은 명령 인자·Git·결과 JSON에 넣지 않는다.
- #181의 로컬 handoff와 FROZEN 기준을 직접 확인한다. pool 4의 지원점은 size 16/rate 20과
  size 64/rate 5이며 seed는 18101이다. 각각 초기 Slot/예약/문서는 32/16/16과 128/64/64다.
  probe가 추가하는 데이터와 최종 규모는 manifest/oracle에서 별도로 확인한다. 다른 규모나 유입률을 보간하지 않는다.
- 실험 전 `slotq-m8-recovery-plan/v1`의 FROZEN JSON과 SHA-256을 ignored 경로에 보관한다.
  `frozenAt`, `sourceRevision`, `supportInputs`의 pool/size/rate/seed/#181 criteriaDigest,
  원인별 `requiredScenarios`, 시간 기준과 정합성 기준, 근거와 미지원 범위를 포함한다.
- 이 프로토콜의 상한은 탐지 45초, 새 JVM 준비 60초, 독립 DB 수렴 90초, 복구 후 quiet 자원 정상화 15초다.
  탐지는 기존 Prometheus `for:30s`와 scrape 여유를 포함한다. 재기동은 Spring startup을 포함하며,
  수렴은 lease·재시도·여러 scheduler batch를 포함한다. partial/duplicate effect, capacity/FIFO 위반,
  감사 누락과 재시작 후 자동 mutation은 모두 0건이어야 한다. SLA나 #185 출시 GO 기준은 아니다.
- 실패한 실행 뒤 기준을 완화해 같은 자료를 PASS로 바꾸지 않는다. missing/stale/truncated 관측은
  INCOMPLETE, 실제 불변식 위반·시간 초과는 FAIL이다. 과거 PASS·결정적 회귀·이번 실제 실행을 합산하지 않는다.

`gates`의 키는 `detectionMillis=45000`, `restartReadyMillis=60000`,
`durableConvergenceMillis=90000`, `postRecoveryQuietMillis=15000`, `partialEffects=0`,
`duplicateEffects=0`, `capacityViolations=0`, `fifoViolations=0`, `missingAudit=0`,
`automaticMutationAfterRestart=0`이다. 기준을 바꾸려면 새 프로토콜·새 계획과 새로운 실행이 필요하다.

`restartReadyMillis`는 부모 프로세스의 같은 `System.nanoTime` clock으로 launch 직전부터
ready checkpoint를 관측할 때까지 측정한다. Event는 Spring context와 scoped worker 구성,
Waitlist는 production activation과 recovery scheduler 시작, Agent는 새 Runtime 구성과 이전 Run 거부가
각 ready 경계다. Agent의 새 exact GET이나 effect/receipt/DONE의 최종 수렴은 그 뒤에 별도로 확인한다.
서로 다른 JVM의 `nanoTime`을 빼거나 `recoveryLaunchToObservationMillis`를 준비 시간으로 읽지 않는다.
후자는 startup·lease·scheduler batch·독립 DB 최종 관측을 포함하는 수렴 상한이다.
준비가 60초 이하여도 수렴이 90초를 넘으면 FAIL이며, 준비 관측이 없으면 수렴 성공만으로 PASS가 되지 않는다.

## 장애 발생 시

1. incident ID와 장애 전 마지막 정상 scrape·DB snapshot, 대상 Tenant/consumer/event/registration,
   request ID, 알려진 Reservation identity와 장애 시각을 보존한다. 실패한 writer를 자동 재전송하지 않는다.
2. 프로세스 종료는 전송 전, Product commit 전, commit 후 응답 유실을 구별한다. 호출자가 결과를 받지 못했다면
   `outcome_unknown`을 유지한다. 사후 DB 부재나 GET 404만으로 최초 요청의 rollback을 단정하지 않는다.
3. MySQL 장애라면 연결 복구 시각과 실제 Product/Auth JDBC 실패를 확인한다. outage 중 DB inventory는
   조회 불가로 표시한다. metrics/observer 연결은 Product pool과 분리하며 필요한 SELECT·PROCESS 권한을 확인한다.
4. 같은 배포 파일·설정으로 JVM을 명시적으로 재기동한다. 기존 event/claim/lease/fence/attempt/receipt를
   초기화하거나 delivery row를 직접 수정하지 않는다. 이전 Run을 복구하거나 provider fallback·mutation retry를 만들지 않는다.
5. DB가 돌아온 뒤 현재 권한으로 새 read를 수행한다. 대상 identity가 알려진 경우에만 exact Reservation GET을
   실행한다. 대상이 불명확하면 unknown을 유지하며 목록 조회로 임의의 대상을 골라 성공 처리하지 않는다.
6. production scheduler가 batch보다 큰 HOLD/Offer/Entry·이벤트 backlog를 처리하는지 독립 DB 연결로 확인한다.
   original identity와 registration, attempt/lifetime/fence 단조성, receipt/DONE 원자성, FIFO·active Offer·capacity를 대조한다.
7. DEAD는 원인을 먼저 수정한 뒤 [개인 운영자 복구](operator-recovery.md)의 HTTPS 경로로 처리한다.
   개인 credential의 현재 유효성과 exact grant를 확인하고 Tenant·consumer·state·fence·transport·epoch를 고정한다.
   하나의 operation ID를 보관해 admission·재조회·동일 operation의 명시적 재호출·감사 결과를 연결한다.
   response loss에 새 operation ID를 만들지 않는다. SQL UPDATE나 SystemPrincipal은 사람의 복구를 대체하지 않는다.
8. 경보 해제, 실제 새로운 Product 요청·MCP 조회, DB/worker/permit 정상화와 최종 DB snapshot을 함께 확인한다.
   HTTP 200이나 metric 감소만으로 성공을 선언하지 않는다. 경보 부재는 정상 scrape와 fresh DB sample이 있어야 해제로 인정한다.

현재 권한을 확인할 수 없거나 DB가 미복구인 경우, 재기동 반복·DEAD 재발, stale fence/epoch,
receipt/DONE·감사 불일치, 정합성 위반, 관측 누락·잘림, 사전 시간 초과가 있으면 서비스 재개를 중단하고
incident 책임자에게 exact inventory와 근거를 전달한다. 데이터 유실·백업 복원·이전 binary는 #183의 절차로 인계한다.

## 합성 환경에서 재현

저장소 루트에서 JDK를 지정하고 `backend/`로 이동한다. `$plan`은 실제 동결한 로컬 파일의 절대 경로,
`$digest`는 실행 전에 별도로 기록한 SHA-256이다. 출력 경로는 매번 새로 사용한다.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
Set-Location backend
.\gradlew.bat test --tests '*RepresentativeFlowIntegrationTests.lifetime*' --tests '*RepresentativeFlowIntegrationTests.*Cancellation*' --tests '*M8RecoveryPlanTests' --tests '*WaitlistProcessRecoveryEvidenceTests' --tests '*HumanRecoveryIntegrationTests' --console=plain
.\gradlew.bat eventProcessRecovery '-Poutput=build/reports/recovery/events-new' --console=plain
.\gradlew.bat waitlistProcessRecovery '-Poutput=build/reports/recovery/waitlist-new' --console=plain
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=4 -PcapacitySize=16 -PcapacityRate=20 -PcapacitySeed=18101 -PcapacityRecovery=true "-PcapacityRecoveryPlan=$plan" "-PcapacityRecoveryDigest=$digest" '-PcapacityOutput=build/m8-capacity/recovery-s16-new' --console=plain
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=4 -PcapacitySize=64 -PcapacityRate=5 -PcapacitySeed=18101 -PcapacityRecovery=true "-PcapacityRecoveryPlan=$plan" "-PcapacityRecoveryDigest=$digest" '-PcapacityOutput=build/m8-capacity/recovery-s64-new' --console=plain
```

recovery opt-in은 #181의 fixture·발생기·계측과 30초 분산 구간을 재사용한다. 이후 같은 JVM·DB에서
실제 MySQL pause, Product Auth의 실제 SQL 실패, 인증서 검증을 하는 HTTPS operator, production Waitlist handler와
Prometheus의 기존 경보 규칙을 실행한다. 개인 credential provisioning과 특정 handler의 고정 실패는 test-only다.
실제 provider 호출은 없다. `recovery.json`은 capacity summary와 별도이며 `complete=false` 또는 파일 부재는 PASS가 아니다.
`stage=pilot`이라는 실행 옵션을 #181의 새 수용량 qualification으로 해석하지 않는다.

프로세스 종료는 기존 co-located Agent 하네스, batch 초과 유지보수·FIFO는 기존
[Waitlist process recovery](../architecture/waitlist-process-recovery.md)를 별도로 재실행한다.
M3 synthetic protocol 실험의 internal replay는 bounded retry/fencing 회귀이며 HTTPS 개인 운영자 복구 근거가 아니다.
이벤트 하네스는 시나리오마다 새 schema를 사용하고 같은 시나리오의 child JVM들은 같은 durable DB를 사용한다.
V20 trigger용 `--log-bin-trust-function-creators=1`은 disposable Testcontainers에만 적용한다.

Backend 수정 뒤에는 사용자 요청에 따라 전체 `test`, `clean build`를 순서대로 실행한다.
`clean` 전에 원자료·manifest·기준·digest·JUnit·실패 로그와 child checkpoint를 루트 ignored `build/`로 보존한다.
동결 plan과 실행 소스 digest를 확인해 실패·중단 실행을 덮어쓰지 않는다. 민감정보나 원자료를 commit하지 않는다.
GitHub CI는 사용자가 확인하며 Codex는 추적하지 않는다.

보존 디렉터리를 `capacity/s16/`, `capacity/s64/`, `events/raw/report.json`, `waitlist/report.json`,
`junit/`, `agent/<실행 디렉터리>/` 구조로 구성한다. Agent는 선택한 JUnit 실행 구간 안의 실제 재기동
5건만 복사하며 `summary.json`, `original/prepared.json`, `restart/restarted.json`,
`restart/readiness.json`을 보존한다. 과거 checkpoint를 새 JUnit에 연결하지 않는다.
Event/Waitlist report에는 실행 중 기록한 run ID·revision·recordedAt와 전체 Backend source/resource/build digest가 있어야 한다.
이 필드가 없는 과거 report에 현재 digest나 재기동 시간을 덧붙이지 않는다. 해당 판정은 INCOMPLETE다.

판정·index·회귀 명령은 PowerShell 7(`pwsh`)에서 실행한다.
새 evidence 디렉터리에서 index를 한 번 만들고 출력된 digest를 원자료와 별도로 보관한 뒤 다음과 같이 대조한다.
Index는 선택한 report/run/시각과 checkpoint 원본 hash를 고정한다. 원자료 교체 후 index를 재생성해 같은 실행으로
취급하지 않는다. Index는 독립적인 실행 증명 서명이 아니며, 실제 실행 로그와 원본 보존의 신뢰를 전제로 한다.
실제 실행을 생략하는 명령이 아니다.
source/resource/build digest, 필수 시나리오, Product SQL 실패, 경보 전이, 수렴 시간과 fresh JUnit을 검사하며
누락은 INCOMPLETE로 남긴다. 원본 report/summary를 덮어쓰지 않는다.
출력은 `kind=EVIDENCE_REVIEW`이며 `executionIdentity`의 원래 run/revision/시각과 `reviewedAt`을 구분한다.
보존 자료를 다시 대조한 시각을 새로운 장애 실험의 실행 시각으로 사용하지 않는다.

```powershell
$indexDigest = ./tools/new-runtime-recovery-index.ps1 -EvidenceRoot $evidence -PlanDigest $digest
./tools/verify-runtime-recovery.ps1 -EvidenceRoot $evidence -Plan $plan -PlanDigest $digest -ProcessIndexDigest $indexDigest -Output $newReviewFile
pwsh -NoProfile -File ./tools/tests/verify-runtime-recovery.tests.ps1
```

독립 DB 확인에는 승인된 SELECT 연결에서 아래 조회를 사용한다. `:tenant`, `:event`, `:registration`,
`:slot`, `:operation`은 기록한 exact UUID를 parameter로 bind한다. outage 동안 실행됐다고 주장하지 않는다.

```sql
SELECT HEX(e.event_id),e.event_type,e.schema_version,e.boundary_sequence,
       HEX(d.registration_id),r.consumer_id,a.transport,a.authority_epoch,
       d.state,d.cycle_attempts,d.lifetime_attempts,d.fencing_token,d.lease_until,d.failure_code
FROM event_records e
LEFT JOIN event_deliveries d ON d.tenant_id=e.tenant_id AND d.event_id=e.event_id
LEFT JOIN event_registrations r ON r.registration_id=d.registration_id
LEFT JOIN event_transport_assignments a ON a.registration_id=r.registration_id
WHERE e.tenant_id=UUID_TO_BIN(:tenant) AND e.event_id=UUID_TO_BIN(:event);

SELECT p.outcome,HEX(p.offer_id),HEX(p.entry_id),HEX(p.reservation_id),d.state,d.fencing_token
FROM waitlist_promotion_receipts p
JOIN event_deliveries d ON d.tenant_id=p.tenant_id AND d.event_id=p.event_id
WHERE p.tenant_id=UUID_TO_BIN(:tenant) AND p.event_id=UUID_TO_BIN(:event)
  AND d.registration_id=UUID_TO_BIN(:registration);

SELECT HEX(r.id),r.state,r.expires_at,a.active,a.units
FROM reservations r LEFT JOIN capacity_allocations a ON a.reservation_id=r.id AND a.tenant_id=r.tenant_id
WHERE r.tenant_id=UUID_TO_BIN(:tenant) AND r.slot_inventory_id=UUID_TO_BIN(:slot);

SELECT HEX(o.operation_id),HEX(o.operator_id),o.consumer_id,o.result_json,a.recorded_at,a.result_json
FROM operations_recovery_operations o JOIN operations_recovery_audit a ON a.operation_id=o.operation_id
WHERE o.tenant_id=UUID_TO_BIN(:tenant) AND o.operation_id=UUID_TO_BIN(:operation);
```

## #183·#185 인계

#183은 검증한 source/config/profile과 서비스 준비·수렴·정합성 oracle을 복원 후 재개 검증에 재사용한다.
복원 뒤 credential·권한·문서 공개 상태의 과거 snapshot 위험과 binary/schema 호환성은 #183에서 별도로 검증한다.
#185는 원자료 위치·접근 권한·보존 기간, FROZEN plan, 실행/재계산 코드 identity, fresh 결과와
실패·미완료·미지원 범위를 함께 인계받는다. #182 결과로 실제 모델 평가나 최종 GO를 대신하지 않는다.
