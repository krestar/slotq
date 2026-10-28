# M8 DB_DIRECT 장애·재시작 검증

Issue [#182](https://github.com/krestar/slotq/issues/182)의 재현 코드와 절차는
[Runtime 복구 runbook](../runbooks/runtime-recovery.md)을 따른다. Product·Auth·MCP·Agent·전달 transaction,
idempotency·lease/fence·retry·DEAD 구현과 DB schema는 변경하지 않았다. 새 제어는 test classpath에만 있다.

## 기준과 자료 식별

기반 `main`은 PR #194가 병합된 `5532766215b72368dcb7fd87335e1cdeac873728`이다.
2026-10-27 KST의 dirty 작업 트리에서 실제 실행했으며 이를 최종 commit에서 새로 실행한 결과로 소급하지 않는다.
각 manifest의 source/resource/build digest와 최종 파일 대조로 검증한 코드 내용을 연결한다.
JDK `25.0.4.1+1-LTS`, Gradle `9.7.1`, MySQL 8.4 Docker, loopback HTTPS, 단일 호스트와 합성 Actor를 사용한다.
실제 외부 모델 호출은 0회다.

실험 전에 `build/issue182/plan-v1.json`을 동결했다. SHA-256은
`b1bf85f5c50caa399666525ca20dedaa5d7c66357eb5817ce8a47251af3e0f6d`다.
탐지 45초·새 JVM 준비 60초·DB 수렴 90초·quiet 자원 정상화 15초와 정합성 위반 0건을 동결했다.
시간의 시작·종료 정의와 필수 시나리오는 runbook과 plan에 있다. 실패 뒤 기준을 완화하지 않았다.

#181의 로컬 최종 handoff 및 두 FROZEN profile과 본 검증 원자료 6개의 digest를 확인했다.
pool 4의 size 16/rate 20과 size 64/rate 5, seed 18101을 재사용한다.
초기 Slot/예약/문서는 각각 32/16/16과 128/64/64이며 probe 추가분은 최종 snapshot에 별도로 남는다.
이는 #181의 과거 용량 PASS와 연결한 실험 입력이다. 그 PASS를 이번 장애 복구의 실행 증거로 쓰지 않는다.

## 실제 실행과 회귀

| 검증 경로 | 이번 fresh 실행 | 근거와 제한 |
| --- | --- | --- |
| Event process matrix | 9/9 PASS | materialization·claim·effect/DONE commit·stale owner·crash exhaustion·실제 DB pause·batch 초과 전달. Synthetic consumer protocol 회귀 |
| Booking/Waitlist process matrix | 5/5 PASS | 실제 handler·production scheduler·동일 DB 재시작·MySQL pause·FIFO/Offer/capacity/receipt/DONE |
| 지원점 A 통합 장애·개인 복구 | PASS | 실제 Product Auth JDBC 장애, 새 Product/MCP 처리, HTTPS 개인 operator·동일 operation·감사·경보 발생/해제·독립 최종 DB |
| 지원점 B 통합 장애·개인 복구 | PASS | A와 같은 최종 소스·pool에서 초기 128 Slot/64 예약/64 문서·5회/초 검증 |
| 공유 JVM 종료·예산·현재 권한·cancellation/late outcome | PASS | 기존 AgentLifetimeExperiments와 새 권한 철회 회귀; 실제 child 종료와 별도 DB 조회 |
| 전체 Backend `test` | PASS | 27분 57초, 121 suites / 1328건 중 1316 통과·기존 opt-in 12 skip·실패/오류 0 |
| 전체 Backend `clean build` | PASS | 28분 14초, 121 suites / 1328건 중 1316 통과·기존 opt-in 12 skip·실패/오류 0 |

Waitlist의 recovery launch부터 독립 DB 종료 관측까지는 case 순서대로
11,032 / 11,028 / 10,717 / 16,884 / 17,149ms였다. Startup과 test clock 이동·여러 batch를 포함하는 상한이며
개별 command commit latency나 처리량이 아니다. Batch 2보다 큰 HOLD·Offer·Entry·delivery backlog가
실제 두 scheduler로 수렴했으며 기존 claim은 재초기화하지 않았다.

지원점 A의 DB 경보 탐지는 34.335초, unpause부터 새 Product/MCP·DB 수렴 관측은 5.261초,
operator 복구 시작부터 감사·DB·경보 해제 확인은 2.954초다. API 200은 recovery admission이며
성공 판정은 그 뒤의 독립 DB receipt/DONE·FIFO·active Offer·capacity 및 감사 대조에 근거한다.
호출자 timeout/응답 유실은 UNKNOWN을 유지하고 실패한 mutation/key를 자동 재전송하지 않는다.
지원점 B는 같은 정의로 각각 34.590 / 6.168 / 1.907초이며 사전 상한을 충족했다.

최종 targeted 회귀는 8 suites, 164건 중 162건 통과·실패/오류 0·기존 실제 제공자 opt-in 2건 skip이다.
`M8RecoveryPlanTests` 4건과 기존 수용량 판정 회귀, Runtime·운영자·전달 경계·architecture 검사를 포함한다.
원자료 digest·현재 source·필수 시나리오·경보 전이·시간·DB oracle·자원·fresh Agent JUnit의 로컬 대조
당시 44개 항목은 모두 PASS였다. 다만 이 판정기는 Event/Waitlist 실행·소스 식별과 60초 준비 기준을 검사하지 않았다.
이 두 항목까지 완료했다는 근거로 당시 PASS를 사용하지 않는다. 필수 자료를 생략한 별도 음성 검사는 INCOMPLETE로 종료했다.
전체 `test`와 `clean build`도 각각 같은 121 suites/1328건을 실행했다. 필수 장애·재시작 회귀의 skip은 없으며
12건은 기존 opt-in이다. 당시 `clean build`의 fresh JUnit과 source로 44개 항목을 다시 대조한 결과도 보존한다.
배포 JAR에 복구 하네스·process probe가 포함되지 않음도 확인했다. GitHub CI는 조회하지 않았다.

## PR #195 리뷰 보정

두 Finding을 독립 검증한 결과 모두 타당했다. 기존 판정기는 Event/Waitlist의 case 결과와 수렴만 읽었고,
실행 ID·revision·시각·소스·원본 hash를 대조하지 않았다. 과거 시각을 담은 PASS report fixture도 기존 case 판정에서는
PASS였다. 또한 FROZEN `restartReadyMillis=60000`을 읽는 코드가 없었다. 기존 Awaitility timeout이나
90초 수렴 검사를 60초 준비 기준의 검증으로 취급하지 않는다.

추가로 나머지 유효한 새 실행 자료에 stale metadata를 넣은 report 복사본을 연결한 음성 fixture를 만들었다.
`a979319`의 원래 판정기는 전체 44/44 PASS, 보정된 판정기는 FAIL이었다. 이 자료는 명시적으로
`ADVERSARIAL_FIXTURE_NOT_EXECUTION`으로 표시했으며 실제 실행 결과에 합산하지 않는다.
별도 fixture에서 준비 시간만 61.001초로 두고 수렴을 90초 이내로 유지한 경우도 원래 판정기는 PASS였다.
보정 후에는 해당 준비 항목만 FAIL로 검출했다. 준비 관측 누락과 서로 다른 JVM clock 사용도 회귀에서 차단한다.

소스 변경 전에 누락을 검출하는 판정기 회귀를 추가해 실패를 확인했다. 보정 후 결정적 회귀 22건은
stale·다른 source/run·원본 교체·index 누락/교체, 60초 경계값·초과·missing readiness·다른 JVM clock을 검사한다.
85초 수렴과 10초 준비를 함께 둔 회귀도 준비 시간과 수렴 시간을 구분한다. 이 fixture 검사는 실제 장애 실험을 대체하지 않는다.

새 Event/Waitlist report는 전체 Backend source/resource/build digest와 run ID를 실행 중 기록한다.
선택한 report와 Agent checkpoint의 hash·run/revision/시각을 별도 index digest로 고정하고, source 내용,
FROZEN 이후 시각과 JUnit 실행 구간을 대조한다. 이 index는 실행의 독립 서명이 아니며 원본 실행 기록의 보존을 전제로 한다.
누락은 INCOMPLETE, 고정한 원본과의 불일치·실제 기준 초과는 FAIL이다.

준비 시간은 같은 부모 JVM에서 launch 직전부터 기존 ready 경계 관측까지 별도 기록한다.
Event의 context/worker, Waitlist의 production activation/scheduler, Agent의 새 Runtime/이전 Run 거부가 경계다.
child JVM 간 `nanoTime` 차감이나 전체 수렴 시간을 준비 시간으로 바꾸는 방식은 사용하지 않는다.

이번 재검증 자료는 ignored `build/issue182-review/`에 별도로 보존한다. 원본 `build/issue182/`의 report,
plan·checkpoint·실패 기록·44개 판정 결과는 수정하지 않았다. 원본에 없는 source나 준비 시간을 소급해 채우지 않는다.
동결 plan/digest와 지원 부하·설정·정합성 기준은 그대로 유지했다. 새 실행은 `a979319` 기반 dirty 작업 트리의
source digest로 식별하며, 이후 commit에서 실행한 것으로 소급하지 않는다.

2026-10-28 KST의 새 실행 결과는 다음과 같다.

| 경로 | 새 실행 결과 | 별도 준비·수렴 관측 |
| --- | --- | --- |
| Event process matrix | 9/9 PASS, 6분 36초 | child 준비 29건 10.360~15.710초, 각 60초 이내 |
| Waitlist process matrix | 5/5 PASS, 2분 37초 | 준비 10.758~11.003초; 수렴 상한 10.910~14.711초 |
| 통합 지원점 A/B | PASS, 각각 3분 32초/3분 42초 | DB 경보 탐지 34.112/34.940초; DB 복구 후 수렴 6.214/3.584초; operator 수렴 2.987/1.891초 |
| 관련 targeted / 판정기 회귀 | 7/7, 22/22 PASS | 실제 child/MySQL targeted와 결정적 CLI 판정기 회귀를 구분 |
| 전체 Backend `test` | PASS, 28분 10초 | 121 suites, 1328건 중 1316 통과·기존 opt-in 12 skip·실패/오류 0 |
| 전체 Backend `clean build` | PASS, 28분 34초 | 같은 121 suites/1328건, 1316 통과·기존 opt-in 12 skip·실패/오류 0 |
| `clean build`의 공유 JVM 재기동 | 5/5 PASS | 부모 monotonic 준비 12.027~14.240초, 각 60초 이내 |
| 최종 evidence 대조 | 79/79 PASS | 새 JUnit·재기동 5건·report 원본 hash/source/run/시각과 준비·수렴 기준 연결 |

최종 `evidence/process-index.json` digest는
`661ab41ca72de66d99efb03c35b91080448b4fdeb57247d584a29b797d7a42f3`이다.
`fresh-review.json`은 `kind=EVIDENCE_REVIEW`와 원래 `executionIdentity`, 별도 `reviewedAt`,
대조기 SHA-256을 기록한다. 대조 시각을 새 장애 실험으로 해석하지 않는다.
전체 `test`의 fresh 자료로도 79개 항목을 대조해 PASS를 확인했으며, 마지막에는 별도 보존한 `clean build` 자료로 확인했다.
최종 메타데이터 회귀 22건도 통과했다. Index가 없는 회귀에서 identity까지 요구했던 테스트 단언의 오류는
원본 실패 결과를 보존하고 수정했다. 누락된 identity를 생성하거나 실제 판정을 PASS로 바꾸지는 않았다.
배포 JAR에 test harness/probe가 없고 기존 JAR과 SHA-256이 동일함도 확인했다.

통합 실험의 첫 출력 경로 지정은 기존 `backend/build/` guard에 걸려 장애 주입 전에 실패했다.
실패 로그를 보존하고 허용된 새 경로에서 별도로 실행했다. 이 시작 실패는 실제 DB outage 근거에 포함하지 않는다.

최종 Backend 명령은 `backend/`에서 다음 순서로 실행했다. 중간 Backend source 변경이나 별도 병렬 Gradle 실행은 없었다.

```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat clean build --console=plain
```

준비 중 발견한 오래된 migration 설정·synthetic worker scope, 관측 계정 권한과 운영 경로 활성화,
timeout 관측 및 read readiness 제어 문제는 test-only 하네스에서 수정했다. 이전 실패·중단 원자료를
새 PASS로 바꾸지 않았다. 병렬 Gradle 컴파일이 진행 중 child의 test class 로딩을 방해한 실행도 보존하고
최종 컴파일·실험·테스트는 순차 실행한다. M5 전체 Kafka 비교·운영 drill은 새로 반복한 것으로 기록하지 않는다.
그 runbook과 개인 HTTPS 복구·관측 방식을 DB_DIRECT fixture에서 재사용했다.

## 보존과 인계

초기 실행의 원자료·manifest·기준·환경·주입 지점·checkpoint·SQL failure class·독립 DB snapshot·시간·JUnit·전체 로그는
Git에서 제외된 `build/issue182/`에 보존한다. 초기 대조 입력은 `build/issue182/evidence/`이고
준비 중 실패와 이전 결과는 `diagnostics/` 및 실행별 로그에 남긴다. `backend/build/`의 필요한 자료를
`clean` 전에 루트 ignored 경로로 복사한다. 로컬 workspace ACL로 접근을 제한하고 최소 90일 보존하며
독립 리뷰에는 이 위치와 digest를 별도로 제공한다. Credential·private key·전체 원자료는 commit하지 않는다.
`final-recovery-review.json`은 원자료 SHA-256, 실행 source 대조, 대조기 자체 SHA-256과 fresh JUnit을 연결한다.
`full-test-execution.json`·`clean-build-execution.json`, 각 JUnit과 실제 프로세스 5건의 index를 별도로 보존해
과거 checkpoint와 이번 실행을 구분한다. 원본 manifest의 revision/dirty와 시간·수치는 변경하지 않았다.

리뷰 보정의 최종 입력은 `build/issue182-review/evidence/`, 결과는 `fresh-review.json`이다.
두 전체 Backend 실행의 로그·JUnit·Agent checkpoint, 최초 실패·회귀, 음성 fixture와 source/index identity도
`build/issue182-review/`의 실행별 경로에 보존한다. 초기 실행과 같은 접근·보존 규칙을 적용한다.

#183은 같은 지원점과 준비/DB 수렴 시간 정의·독립 DB oracle을 복원 후 서비스 재개 검증에 사용할 수 있다.
백업·복원·binary rollback과 복원 뒤 보안 변경 재검증은 이번에 실행하지 않았다.
#185는 최종 source/config/evidence identity와 실제 실행·결정적 회귀·기존 결과·실패/미완료를 구분해 인계받는다.
이번 결과는 HA·다중 인스턴스·SLA·다른 부하/데이터 규모·실제 모델 또는 최종 출시 GO를 보장하지 않는다.
