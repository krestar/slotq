# M8 백업·복원 및 binary rollback 검증

Issue [#183](https://github.com/krestar/slotq/issues/183)의 합성 MySQL 8.4 실행 기록이다.
재현 절차와 차단 조건은 [백업 복구 runbook](../runbooks/backup-recovery.md)에 있다.
실제 고객 자료, 외부 모델 호출, 운영 배포는 포함하지 않는다.

## 기준과 적용 범위

- 시작 `main`: `5d0f47e82665249b712f065550de5496f53122f3`.
- 이전 binary source: `5532766215b72368dcb7fd87335e1cdeac873728`.
- MySQL 8.4.11 (`REPEATABLE-READ`, 128 MiB buffer pool), JDK 25.0.4, 단일 로컬 Docker 호스트, DB_DIRECT, process-local Agent.
- #181의 pool 4, seed 18101, 초기 규모 16/rate 20 및 64/rate 5만 사용한다.
  초기 Slot/Reservation/Knowledge는 32/16/16 및 128/64/64다. 혼합 부하와 #182 복구 fixture 이후
  백업용 완료 예약·멱등키, FIFO 대기 3건, DEAD, 철회/만료/권한 축소/publication probe를 추가한다.
- #181 FROZEN 두 파일과 원자료 6개의 실제 digest를 확인했다. #182 plan의 준비 60초,
  수렴 90초, quiet 15초를 그대로 사용한다. 과거 PASS를 이번 복원 실행으로 집계하지 않는다.
- 선택 방식은 writer·scheduler·worker 종료와 MySQL `super_read_only` 이후의 전체 logical dump다.
  실제 DML/DDL 거부, 미완료 transaction 연결 종료 후 전체 rollback, 별도 빈 MySQL의 전체 값 대조를 수행한다.

## 배포 파일과 schema

| 배포 파일 | SHA-256 | schema 및 판정 범위 |
| --- | --- | --- |
| 이전 `previous.jar` | `c8f4d401f0fadf5488fc023f8ae7be3241a5d963a9afc25af528940ad72c6ea5` | V1–V23 전체 migration이 있는 이전 릴리스 |
| 후보 `candidate.jar` | `1641bf431730e75ab8641c35a76353872b5f7632cc667537cf6dd5df9a4c9039` | 시작 main의 V1–V23 후보 |

두 revision의 production source는 동일하다. JAR 원본 digest는 각각 보존한다.
최종 작업 트리의 Backend 소스·설정 554개가 FROZEN plan의 개별 SHA-256과 일치했고,
`clean` 이후 다시 만든 후보 bootJar의 SHA-256도 본 검증에서 기동한 후보와 일치했다.
archive와 Windows checkout의 V5/V6/V10/V11/V20 SQL 줄바꿈 차이는 원본 hash와 LF 대조 hash를 함께 남기며,
이 대조가 실제 Flyway 검증과 JAR 기동을 대신하지 않는다. 다른 binary/schema 조합은 승인하지 않는다.
V22 DB는 복원 재개 gate에서 거부하고, 별도 실제 MySQL 테스트에서 기존 V23 migration을 forward 적용한다.
이는 V22 binary의 V23 호환성을 입증하는 실험이 아니다. 검증한 복구 경로는 빈 DB로의 백업 복원이다.
이전 binary가 다른 schema와 호환되지 않는 배포는 해당 조합을 차단하고 별도 복원/forward-fix 검증이 필요하다.

## 보안과 독립 oracle

원본 DB에서 백업 이후 credential 철회·재발급, 자연 만료, Manager 역할 축소와 Venue grant 해제,
문서 철회 및 공개 범위/version 변경을 수행한다. 원본 writer를 다시 종료하고 읽기 전용으로 고정한
현재 security inventory를 run ID·복구 시점·cutoff·digest와 묶는다. 복원된 과거 snapshot의 활성 자격과
publication 위험을 서비스 정지 상태에서 확인한 다음, 현재 inventory를 한 transaction으로 반영한다.
이 신뢰 근거가 없거나 대조에 실패하면 서비스 재개는 차단한다. 원본도 소실된 재해에서 최신 권한을
재구성할 수 있다는 주장은 하지 않는다.

독립 JDBC는 모든 base table의 원래 identity와 cell을 비교하고 scoped FK, event/receipt/DONE,
Offer·notification 효과, 멱등성 binding, 유한 attempt, fence, DB_DIRECT epoch, append-only 감사,
문서 publication/content를 확인한다. 기존 terminal delivery·receipt·replay·운영 감사·intent·approval은
재개 후에도 보존해야 한다. 새 credential로 정확한 GET, 기존 키의 동일 Reservation 재조회,
명시적인 새 HOLD와 3개 FIFO Offer를 검증한다. 과거 승인은 새 delegation에서 mutation을 허용하지 않는다.

실제 배포 JAR은 Product 역할을 종료한 뒤 같은 JAR의 기존 DB_DIRECT consumer 역할로
operations observer를 순차 수렴시킨다. 두 환경의 writer는 동시 실행하지 않으며 두 과정 모두 pool 4다.
전체 수렴 시간은 Product 기동부터 observer 완료까지다. 이 실행을 동시 처리량 측정으로 일반화하지 않는다.

## 실행 결과

최종 pilot 14(규모 64/rate 5)의 RPO는 5.769초, RTO는 78.015초였다. 후보 JAR의 수렴 후 같은 V23 DB에서
이전 JAR를 실행했고, 마지막 서비스 조회와 최종 DB 대조를 완료했다. 2026-11-01 20:15 KST에
RPO **15초**, RTO **180초**를 동결했다. 로컬 스케줄링과 cold JVM·MySQL 변동 여유로 최종 pilot의 두 배를
각각 5초·30초 단위로 올림했다. `frozen-v1.json`의 SHA-256은
`3b06e6dbff22816dc80f77b8f30c1f84f44bed0eeafa5445e2ece47d6d40c387`이고,
배포 설정 계약의 SHA-256은 `7e0a94ea304c630b333a1241d2da3b2660af0b314675cdf001d2438b1947ab16`이다.

2026-11-01 본 검증에서 두 지원점 모두 동결 기준을 충족했다. 아래 시각은 KST이며 초 미만은 밀리초까지 표시했다.

| 지원점 (규모/rate) | 쓰기 중단 후 복구 시점 | 빈 DB import 완료 | 이전 JAR의 최종 조회 | RPO / RTO | 복원 테이블 / 행 |
| --- | --- | --- | --- | --- | --- |
| 16/20 | 20:19:46.795 | 20:20:05.543 | 20:20:48.173 | 5.335초 / 54.686초 | 46 / 1,980 |
| 64/5 | 20:24:46.292 | 20:25:06.012 | 20:26:07.769 | 6.128초 / 74.015초 | 46 / 2,595 |

각 원본에서 백업 이후 commit한 예약 한 건이 복원본에서 유실됐다. 현재 보안 inventory와 대조한 뒤 철회·만료된
자격 및 축소된 Venue 권한을 거부했고, 고객 검색은 철회·교체된 문서의 ID와 내용을 반환하지 않았다. 유효한
문서의 정확한 version과 본문은 조회됐다. 옛 승인 두 건은 새 delegation에서 dispatch되지 않았고 해당 Slot의
예약 수가 유지됐다. 각 지원점에서 원래 멱등키의 동일 예약과 새로 승인한 HOLD 한 건을 독립 조회로 확인했다.

| 지원점 | 후보 준비 / 전체 수렴 | 이전 JAR 준비 / 전체 수렴 | 후보 / 이전 Hikari quiet |
| --- | --- | --- | --- |
| 16/20 | 10.598 / 21.054초 | 9.158 / 12.002초 | 1.776 / 1.621초 |
| 64/5 | 16.721 / 32.535초 | 15.930 / 19.879초 | 2.572 / 2.315초 |

두 복원본 모두 원래 cell 전체가 일치했고, 보안 대조 전과 서비스 재개 후 독립 DB oracle 65개가 모두 0이었다.
원본 DEAD 한 건과 기존 terminal delivery·receipt·감사·승인 이력이 보존됐고, 세 대기열은 정확한 Offer로 수렴했다.
두 원자료에 대한 사후 점검은 각각 726개를 통과했다. 별도 복사본을 사용한 판정기 회귀 10건은 정상 복사본 1건
PASS, 명시적 위반 6건 FAIL, 필수 관측 누락 3건 INCOMPLETE로 모두 기대 판정과 일치했다.

| 보존 디렉터리 (`build/issue183/evidence/` 아래) | dump SHA-256 | result SHA-256 |
| --- | --- | --- |
| `backup-verify-s16/backup/` | `c341f425c3b27a245cd65e4312583a54f7d106c1d05f46944150f011e65803b7` | `e7f07be8d01d3578655e2bae3ffbbdcea06a75c2cc282a5b65c956c6411a945f` |
| `backup-verify-s64/backup/` | `d8984b031671a76c6be7f1051bb8147ce1823676c22d26698d377b01ed47519e` | `0c3bda36c2b88bcad9290ad2798a2f79596ab332d8fc6e8d1a1038468517628a` |

두 본 검증의 사람 운영자 replay 작업과 append-only 감사는 각각 1건이었다. 별도 V9 `event_replay_audit`는
이 시점에 비어 있었다. 2026-11-02 두 dump의 격리 복사본에서 `EventReplayService`가 원래 DEAD를 대상으로
`TRUSTED_INTERNAL` 행을 각각 1건 생성하도록 했다. 이를 읽기 전용으로 고정해 다시 dump하고 각각 다른 빈
MySQL에 복원했다. tenant/event/registration, 직전 DEAD 상태·fence·lifetime attempts가 일치했으며 46개
테이블 전체 값 대조 및 독립 DB oracle을 통과했다. 이 보강 실험은 서버를 기동하지 않아 RPO/RTO 분모에서 제외한다.
보호된 `replay-s16/result.json`과 `replay-s64/result.json`은 위 본 검증 result digest를 참조한다. 각 보강 result의
SHA-256은 `c838e52bb486a5e1a7e955daf52474ea6119c39c6950fe61c59b8c1ac8293aa8`,
`d0ac629f0c8f4163ae661b4371e5e97c2e6082926c39fff587197825fc8a6222`다.

### 사전 실험에서 발견한 문제

규모 16/rate 20의 pilot 10에서 복구 시점 이후 유실 가능 구간은 5.692초였고, 빈 DB 준비부터
후보 JAR의 최종 exact GET까지 75.764초가 걸렸다. 별도 MySQL 복원, 보안 대조, 이전 JAR과 후보 JAR의
기동 및 최종 DB oracle까지 완료했다. 이 값은 본 검증 기준을 정하는 사전 측정이다.

실패한 pilot 01–06은 보호된 원자료에 그대로 보존했다. 순서대로 완료 예약 fixture 부족,
offline JDBC numeric binding(`2`가 binary literal `50`으로 해석), SQL 줄바꿈 대조,
offline Auth JDBC UTC 설정, child JVM의 HTTP retry 금지 설정 누락을 발견했다.
pilot 07은 별도 프로세스가 발급한 좁은 Product 자격을 재시작 프로세스가 401로 거부했다.
DB에 저장된 자격과 발급 프로세스의 lease가 함께 필요하므로 실제 JAR의 새 MCP session을 통해 서버 내부에서 발급하도록 했다.
pilot 08은 권한 부족 fixture가 대상 SQL 실행 전에 DB 선택부터 거부돼 중단했다. 제한 계정에 무관한 한 테이블의
SELECT만 부여한 후 각 대상 SQL의 MySQL 오류 코드를 구별했다. pilot 09는 동일한 시간·인원의 대기를 서로 다른
대기열로 가정한 oracle에서 실패했다. Demand별로 시간대를 분리하고 백업 시점에 세 Entry가 모두 WAITING임을 확인한다.
01–09의 실패 결과는 그대로 보존한다.

규모 64/rate 5 pilot 11은 후보 JAR의 Hikari quiet 관측이 15초를 넘어 실패했다. 당시 실패 구간의 개별 표본은
누락됐다. 이후 같은 시간·연속 3표본 기준을 유지하고 실패 시에도 표본을 남긴다. pilot 12는 후보 → 이전 JAR
교체를 포함해 RPO 6.231초, RTO 80.102초였지만, 동결 전 observer effect와 누락된 원 event에 대한 DB 대조를
보강해 소스 digest가 달라졌다. 동결 도구가 이 pilot의 사용을 거부했다.
pilot 13은 두 옛 승인의 MCP 감사가 모두 `NOT_DISPATCHED`였지만 전체 예약 수 부동 검사가 실패했다.
이 자료에는 해당 구간의 정확한 Slot별 최종 건수가 없다. 이후 FIFO worker가 동시에 변경할 수 있는 전체 건수 대신,
옛 승인의 Tenant·Venue·Slot에서 생긴 예약 수와 intent의 최초 dispatch 수를 함께 검증한다.

## 자료와 #185 인계

- 최종 targeted `M8Backup*Tests`, `M8RecoveryPlanTests`, `M8Capacity*Tests` 66건이 모두 통과했다.
- 2026-11-02 `backend/`에서 `./gradlew.bat clean test build --console=plain`을 실행해 전체 테스트와 빌드를
  한 번에 검증했다. 28분 33초, 123 suites / 1,335건 중 1,323 통과, 기존 opt-in 12 skip, 실패·오류 0건이다.
  중단됐던 이전 전체 빌드 로그는 보존했다. Frontend 변경은 없으며 Frontend 검증은 실행하지 않았다.

보호된 ignored 루트는 `C:\dev\slotq\build\issue183\`다. 실행별 원본 dump·전체 로그·DB snapshot·hash 목록은
Git에 넣지 않는다. `clean build` 전에 실행 자료를 이 루트에 복사하고 byte digest를 대조한다.
최소 90일 보존하며 자동 삭제를 등록하지 않는다. 로컬 ACL은 저장 접근 통제이며 독립 호스트의 재해 내구성 보장이 아니다.

#185는 최종 후보의 source/config/binary/schema와 이 실행의 digest를 대조하고, 변경 영향이 있으면 필요한
복원 실험을 다시 수행해야 한다. 현재 보안 근거 부재, 누락된 필수 자료, RPO/RTO 초과, 미검증 조합은
NO-GO 근거다. 이 Issue의 bounded 복원 결과는 M8 전체 GO나 #193 실제 모델 평가를 대신하지 않는다.
