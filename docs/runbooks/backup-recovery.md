# 일관된 백업과 격리 복원

Issue [#183](https://github.com/krestar/slotq/issues/183)의 합성 복구 검증 절차다.
단일 Product/MCP/Router/Runtime, MySQL 8.4, DB_DIRECT와 process-local Agent 계약을 유지한다.
이 도구는 외부 DB 주소를 받지 않으며 disposable MySQL만 생성한다. 운영 DB에 그대로 적용하는 배포 도구가 아니다.

## 복구 경계

1. 원본 HTTP writer, Runtime, scheduler와 worker를 중단하고 연결 종료를 확인한다.
2. 미완료 transaction의 연결을 끊고 독립 연결에서 parent·dependent row가 함께 rollback됐는지 확인한다.
3. MySQL `super_read_only`를 켜고 실제 DML·DDL 거부와 열린 transaction 부재를 확인한다.
   이 경계를 복구 시점으로 기록한다. InnoDB 전체 schema를 `mysqldump --single-transaction`으로 저장한다.
   `--no-tablespaces --set-gtid-purged=OFF --hex-blob --routines --events --triggers --skip-add-drop-table --skip-add-locks`를 사용한다.
   데이터베이스의 user/password와 외부 TLS private key는 dump에 포함하지 않고 별도로 제공한다.
4. dump의 SHA-256, 원본 server UUID, 복구 시점, 모든 테이블의 identity·원본 값·Flyway 이력을 함께 고정한다.
   실행 중 쓰기나 DDL을 허용하는 온라인 백업은 이 절차의 지원 범위가 아니다.
5. 원본과 다른 server UUID·container·저장 볼륨을 가진 빈 MySQL에만 복원한다.
   테이블/이력을 삭제해 비우는 방식은 사용하지 않는다. 원본 writer와 복원 writer를 동시에 실행하지 않는다.

## 현재 보안 상태

과거 snapshot은 철회된 credential, 해제된 Venue 권한, 철회된 publication을 다시 포함할 수 있다.
복원 성공만으로 서버를 시작하지 않는다. 복구 시점 이후의 **현재** 보안 상태를 별도 근거에서 확인해야 한다.

이 합성 실험의 신뢰 근거는 아직 보존된 원본 DB에서 명시적인 변경을 마친 뒤 writer를 종료하고
읽기 전용으로 고정한 보안 inventory다. 출처·원본 server·대상 scope·cutoff·run ID·digest를 별도 기록한다.
같은 호스트의 파일과 hash는 독립 서명이나 재해 시 생존을 보장하지 않는다.
원본도 유실되고 별도 검증 가능한 최신 보안 변경 근거도 없다면 **서비스 재개는 INCOMPLETE로 차단**한다.
백업에 있던 권한을 현재 권한으로 추정하지 않는다.

합성 fixture의 offline reconciliation은 실제 원본에서 확인한 exact credential·delegation, 현재 역할과
Venue grant, 문서 metadata/version/publication만 하나의 transaction으로 대조·반영한다.
원본에서 없어진 grant를 복원본에 남기지 않으며, 변경 이력이나 현재 상태가 맞지 않으면 중단한다.
이는 범용 보안 병합 알고리즘이 아니다. 운영 복구에서 원본을 확인할 수 없는 임의의 row를 생성하거나
권한을 확대하는 근거로 사용하지 않는다. 비밀값은 별도 메모리 공급하며 파일·요약에 쓰지 않는다.

새 credential과 새 MCP session으로 조회한다. 기존 intent·approval의 현재 권한과 실제 만료를 확인하고
자동 mutation이 없음을 DB에서 확인한다. Run·quota·session은 복원하지 않는다.
좁혀진 Product credential의 실행 lease도 process-local이다. 별도 관리 프로세스에서 발급한 Product 자격을
재기동한 서버에 주입하지 않는다. 새 MCP session의 호출이 같은 서버 안에서 Product 자격과 lease를 발급한다.
완료 멱등키 재조회는 결과가 이미 확인된 독립 MCP 요청을 원래 유효한 delegation에서 명시적으로 재승인한다.
원래 key·target·승인 연결과 현재 권한을 유지하며, 종료된 Agent Run이나 `outcome_unknown`의 자동 재시도와 구별한다.
파생 검색 자료는 복원한 원문·현재 publication을 기준으로 새 프로세스에서 만들며 철회한 문서의 공개를 허용하지 않는다.

## 사전 실험과 본 검증

JDK 25, Docker, PowerShell 7이 필요하다. 기존 #181 두 FROZEN 파일과 각 3회 원자료 digest,
#182 FROZEN plan과 실제 인계 자료를 먼저 확인한다. 지원점은 pool 4에서 초기 규모 16/rate 20 또는
규모 64/rate 5, seed 18101이다. 초기 Slot/예약/문서는 각각 32/16/16과 128/64/64이며
권한·정합성·복원 probe의 추가분은 별도로 기록한다. 다른 규모나 요청 구성을 보간하지 않는다.

이전 릴리스와 후보를 각각 보존한 source revision에서 `bootJar`로 빌드하고 실제 JAR digest를 고정한다.
복원한 DB에서 후보를 먼저 기동하고 수렴을 확인한 뒤 종료한다. 이후 import나 migration 없이 같은 DB에서
이전 JAR를 기동해 binary 단독 rollback을 확인한다. 이전 JAR가 마지막 exact GET에 응답한 때까지 RTO에 포함한다.
`m8Capacity`의 기존 부하·운영 복구 fixture에 다음 opt-in 인자를 추가한다.
다음 변수는 확인한 절대 경로와 digest이며 credential 값이 아니다.

```powershell
Set-Location backend
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=4 -PcapacitySize=16 -PcapacityRate=20 -PcapacitySeed=18101 -PcapacityRecovery=true "-PcapacityRecoveryPlan=$recoveryPlan" "-PcapacityRecoveryDigest=$recoveryDigest" -PcapacityBackup=true -PcapacityBackupPlan=pilot "-PcapacityPreviousBinary=$previousJar" "-PcapacityCandidateBinary=$candidateJar" '-PcapacityOutput=build/m8-capacity/backup-pilot-new' --console=plain
```

실패한 사전 실험을 덮어쓰지 않는다. 최종 pilot 원자료를 `clean` 영향이 없는 보호된 루트로 복사하고 digest를 확인한다.
완료된 pilot의 실제 RPO/RTO와 여유 근거로 기준을 선택한 뒤
저장소 루트에서 다음 명령으로 동결한다. 소스가 pilot 이후 달라졌으면 새 pilot이 필요하다.

```powershell
./tools/freeze-backup-recovery.ps1 -PilotResult $pilotResult -Capacity16 $capacity16 -Capacity64 $capacity64 -RecoveryPlan $recoveryPlan -PreviousBinary $previousJar -CandidateBinary $candidateJar -RpoMillis $rpoLimit -RtoMillis $rtoLimit -Reason $measuredReason -Output $newPlan
```

본 검증은 `capacityBackupPlan`에 동결 파일, `capacityBackupDigest`에 출력된 digest를 넣어 두 지원점을
각각 새로운 경로에서 실행한다. `capacityStage=pilot`은 기존 부하 발생기의 실행 옵션이며,
복원 검증의 PILOT/EXECUTION 구분은 backup plan으로 결정한다. 새 수용량 qualification으로 집계하지 않는다.

RPO는 백업 복구 시점부터 원본 writer cutoff까지의 유실 가능 구간이다. 그 사이 실제 commit한
예약이 복원본에 없음을 관측한다. RTO는 빈 대상 MySQL 생성 직전부터 보안 대조·binary 준비·DB 최종 검증까지다.
준비 60초, DB 수렴 90초, quiet 자원 15초는 [#182 runbook](runtime-recovery.md)의 별도 기준이다.
결과를 본 뒤 한도를 늘려 같은 실행을 통과시키지 않는다.

동결한 배포 설정은 매 실행에 새로 생성하는 loopback 포트(두 HTTPS origin 포함), 대상 MySQL URL,
합성 TLS key store 경로만 치환해 대조한다. 두 origin은 실제 포트와 먼저 일치해야 한다. 원래 실행별 설정의
hash도 별도로 보존한다. secret은 digest 비교용 공개 설정에서 제외하고 실제 인증·TLS·DB 접근으로 확인한다.

실제 배포 JAR의 Product 역할은 `waitlist.promotion`을 처리한다. #181 테스트 구성에 함께 등록한
`operations.event-observation`의 미처리 작업은 Product 종료 후 같은 JAR의 기존 `consumer` 역할로
순차 처리한다. 각각 pool 4이며 두 writer를 동시에 실행하지 않는다. 전체 수렴 90초는 Product 기동부터
이 순차 처리 완료까지 포함한다. 이는 복구 절차의 검증이며 동시 운영 처리량의 추가 지원점이 아니다.

## 재개와 중단

dump·필수 자료·복구 시점·schema·binary/config digest·실제 DB credential·현재 보안 상태·독립 DB oracle을
모두 확인한 뒤 복원 writer를 시작한다. 이전 binary/현재 schema의 검증한 조합만 사용한다.
확인하지 않은 조합을 통과시키거나 Flyway undo, table/history 삭제로 맞추지 않는다.
schema 호환성을 입증하지 못하면 새 빈 환경으로의 검증한 백업 복원 또는 별도 검증한 forward-fix가 필요하다.

정확한 Reservation GET, 복원된 완료 멱등키의 동일 identity 조회, 명시적인 새 HOLD,
MCP 새 session과 Knowledge 현재 publication, 미처리 작업·receipt/DONE·FK·attempt/fence·감사 이력을 대조한다.
API 성공만으로 수렴을 판단하지 않는다. 운영 DEAD는 그대로 보존하고 기존 개인 운영자 복구 경계를 유지한다.
호출자가 `outcome_unknown`이면 유실/rollback으로 단정하거나 새 키로 재시도하지 않는다.

파일 누락/손상, 권한 부족, 잘못된 복구 시점/schema, 보안 근거 누락/반영 실패,
정합성 위반, 시간 초과 또는 필수 관측 누락은 재개를 차단한다.
명시적 위반은 FAIL, 관측 부재는 INCOMPLETE다. 결정적 판정기 회귀와 실제 MySQL 거부 관측을 구별한다.

완료된 원자료는 실행 당시 전달받은 result·plan SHA-256을 입력해 다시 검토한다. 자료에서 새로 계산한 hash만
신뢰 근거로 삼지 않는다. 이 도구의 PASS는 보존된 실행 증거의 재검토이며 새 복원 실행을 뜻하지 않는다.

```powershell
./tools/verify-backup-recovery.ps1 -Result $result -ResultDigest $resultDigest -Plan $plan -PlanDigest $planDigest -Output $newReview
```

## 기존 내부 replay 이력 확인

사람 운영자의 복구 이력과 별도로, 과거 `TRUSTED_INTERNAL` replay 행까지 채운 보강 실험은 다음과 같이
`backend/`에서 실행한다. `$result`와 `$resultDigest`는 위 본 검증의 원본 result와 전달받은 SHA-256이다.
`$replayOutput`에는 저장소 루트 `build/` 아래의 새 절대 경로를 사용한다. 하네스는 원본 dump의 복사본에서
기존 내부 API로 한 번 replay한 뒤 쓰기를 막고, 다른 빈 MySQL에 다시 복원한다. 두 DB 모두 서비스를 시작하지 않는다.

```powershell
.\gradlew.bat -I ../tools/backup-replay.init.gradle verifyBackupReplayHistory "-PbackupResult=$result" "-PbackupResultDigest=$resultDigest" "-PbackupReplayOutput=$replayOutput"
```

## 자료 보존

원자료는 새 ignored `backend/build/m8-capacity/<run>/backup/`에 owner-only Windows ACL로 보관한다.
container의 client credential file과 dump는 mode 0600이다. 최소 90일 보존하며 자동 삭제를 등록하지 않는다.
`clean` 전에 필요한 원자료와 전체 실행 로그를 보호된 루트 `build/issue183/`에 복사하고 hash를 대조한다.
원본 dump·credential·private key·전체 로그·실행별 JSON과 hash 목록은 Git에 추가하지 않는다.
독립 리뷰에는 경로와 digest를 별도로 전달한다. 실행 summary와 한계는
[실험 기록](../experiments/m8-backup-recovery.md)에서 관리한다.
