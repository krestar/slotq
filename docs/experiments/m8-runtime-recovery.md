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
탐지 45초·새 JVM 준비 60초·DB 수렴 90초·quiet 자원 정상화 15초와 정합성 위반 0건을 적용한다.
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
44개 항목은 모두 PASS다. 필수 자료를 생략한 별도 음성 검사는 INCOMPLETE로 종료했다.
전체 `test`와 `clean build`도 각각 같은 121 suites/1328건을 실행했다. 필수 장애·재시작 회귀의 skip은 없으며
12건은 기존 opt-in이다. `clean build`의 fresh JUnit과 현재 source로 최종 44개 항목을 다시 대조해 PASS를 확인했다.
배포 JAR에 복구 하네스·process probe가 포함되지 않음도 확인했다. GitHub CI는 조회하지 않았다.

최종 Backend 명령은 `backend/`에서 다음 순서로 실행했다. 중간 source 변경이나 별도 병렬 Gradle 실행은 없었다.

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

원자료·manifest·기준·환경·주입 지점·checkpoint·SQL failure class·독립 DB snapshot·시간·JUnit·전체 로그는
Git에서 제외된 `build/issue182/`에 보존한다. 최종 대조 입력은 `build/issue182/evidence/`이고
준비 중 실패와 이전 결과는 `diagnostics/` 및 실행별 로그에 남긴다. `backend/build/`의 필요한 자료를
`clean` 전에 루트 ignored 경로로 복사한다. 로컬 workspace ACL로 접근을 제한하고 최소 90일 보존하며
독립 리뷰에는 이 위치와 digest를 별도로 제공한다. Credential·private key·전체 원자료는 commit하지 않는다.
`final-recovery-review.json`은 원자료 SHA-256, 실행 source 대조, 대조기 자체 SHA-256과 fresh JUnit을 연결한다.
`full-test-execution.json`·`clean-build-execution.json`, 각 JUnit과 실제 프로세스 5건의 index를 별도로 보존해
과거 checkpoint와 이번 실행을 구분한다. 원본 manifest의 revision/dirty와 시간·수치는 변경하지 않았다.

#183은 같은 지원점과 준비/DB 수렴 시간 정의·독립 DB oracle을 복원 후 서비스 재개 검증에 사용할 수 있다.
백업·복원·binary rollback과 복원 뒤 보안 변경 재검증은 이번에 실행하지 않았다.
#185는 최종 source/config/evidence identity와 실제 실행·결정적 회귀·기존 결과·실패/미완료를 구분해 인계받는다.
이번 결과는 HA·다중 인스턴스·SLA·다른 부하/데이터 규모·실제 모델 또는 최종 출시 GO를 보장하지 않는다.
