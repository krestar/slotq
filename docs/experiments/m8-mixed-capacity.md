# M8 Product·AI 혼합 부하 진단 (#181)

## 상태와 범위

이 문서는 [M8 공통 계약](../architecture/m8-evaluation-contract.md)의 capacity 담당 자료다.
실제 지원 용량의 최종 qualification은 **INCOMPLETE**다. 아래 진단은 단일 JVM,
MySQL 8.4, DB_DIRECT, loopback HTTPS, 합성 Actor·문서·통제된 adapter에 한정한다.
실제 모델의 품질·처리량, 공개 로그인, HA/SLA, 다중 인스턴스 및 재시작 복구를 주장하지 않는다.

`m8Capacity`는 기존 Product HTTPS/Runtime fixture, M5의 두 scoped DB_DIRECT worker 구성,
`waitlistBaseline`/`m5TransportComparison`이 사용하는 `WaitlistRecoveryDatabase`를 재사용한다.
`concurrencyBaseline`의 public HOLD·capacity/Allocation 검증 방식도 유지한다.
기존 baseline이나 M5 closed-loop 수치를 새로운 open-loop 증거로 합산하지 않는다.
Product/Auth/잠금/격리/멱등성/이벤트/MCP admission/Runtime 정책과 기본 설정은 변경하지 않는다.

## 실행과 원자료

Java 25, 로컬 Docker가 필요하다. 아래 명령은 `backend/`에서 실행한다.
실행별 폴더는 재사용하지 않는다. MySQL은 매번 새 Testcontainer이며 개발 bootstrap은 끈다.
모델 credential이나 외부 모델 호출은 필요 없다.

```powershell
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=4 -PcapacitySize=16 -PcapacityOutput=build/m8-capacity/pilot-v4-p4-s16
.\gradlew.bat m8Capacity -PcapacityStage=verification -PcapacityPool=4 -PcapacitySize=16 '-PcapacityCriteria=../docs/experiments/m8-capacity-criteria-v1.json' -PcapacityOutput=build/m8-capacity/verification-v1-r1
.\gradlew.bat m8CapacityRecalculate -PcapacityInput=build/m8-capacity/verification-v1-r1 -PcapacityDigest=df59011ca8bee7da118d117f0f57748066fad1749ef3cc2056b8777819c141eb
```

실제 사전 실험의 나머지 두 명령은 다음과 같다. 본 검증은 위 명령의 output을
`verification-v1-r1/r2/r3`으로 나눠 순차 실행했다.

```powershell
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=3 -PcapacitySize=16 -PcapacityOutput=build/m8-capacity/pilot-v4-p3-s16 --console=plain
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=4 -PcapacitySize=64 -PcapacityOutput=build/m8-capacity/pilot-v4-p4-s64 --console=plain
```

재계산은 다음 여섯 run 각각에서 수행했다. `$capacityDigest`는 해당 실행이 기록한
`raw.sha256` 값이며 아래 결과 표의 digest와도 일치한다.

```powershell
$capacityRuns = @('pilot-v4-p3-s16', 'pilot-v4-p4-s16', 'pilot-v4-p4-s64', 'verification-v1-r1', 'verification-v1-r2', 'verification-v1-r3')
foreach ($capacityRun in $capacityRuns) {
    $capacityDigest = (Get-Content "build/m8-capacity/$capacityRun/raw.sha256").Trim()
    .\gradlew.bat m8CapacityRecalculate "-PcapacityInput=build/m8-capacity/$capacityRun" "-PcapacityDigest=$capacityDigest" --console=plain
}
```

`manifest.json`은 `kind=EXECUTION/measured=true`인 이번 실행의 명세이며 #178의 TEMPLATE과 다르다.
source revision/dirty와 소스·resource·build 파일의 SHA-256, 실제 JVM/DB/image/호스트,
pool·timeout·worker·scheduler, 데이터 규모·seed를 기록한다. Product가 만드는 UUID와
스케줄링 순서는 seed로 고정된다고 주장하지 않는다. idempotency key와 credential 원문은
저장하지 않는다. DB snapshot과 key digest는 합성 fixture의 독립 관측이다.

`raw.json`에는 예정 요청별 결과·시각, 250ms 목표 주기의 실제 sample 시각, Auth 호출,
DB oracle, 조회 비용을 보존한다. `raw.sha256`과 `summary.json`은 원자료와 연결된다.
재계산은 caller가 제공한 원자료 digest와 저장된 summary의 구조적 동등성을 검사한다.
별도 JVM에서 provider/Docker 없이 실행할 수 있다. JSON object key 순서는 판정 기준이 아니다.
원자료·전체 로그·host.json·DB snapshot은 모두 ignored `backend/build/m8-capacity/`에 둔다.
현재 보존은 이 로컬 작업 공간에 한정되며 `clean` 전에 ignored 사본을 보관해야 한다.
GitHub에는 이 요약·하네스·사전 기준만 남긴다. raw 접근은 로컬 파일 권한을 따른다.
로컬 보존 기간은 90일이며 삭제·후속 Issue 인계는 수동 관리한다.

## 부하와 측정 정의

| 구간 | 예정 유입 | 시간 | 역할 |
| --- | ---: | ---: | --- |
| warmup | 10/s | 3s | 예열, 본 구간 분모에서 분리 |
| distributed | 20/s | pilot 8s / 본 검증 30s | 여러 Slot의 HOLD와 동일 키 재조회 |
| hot-slot | 80/s | 8s | 한 Slot, 동일·다른 키의 경쟁 |
| burst | 320/s | 3s | 짧은 과부하, 발생기 drop도 기록 |
| recovery | 5/s | 10s | 새 요청과 자원 정상화 관측 |

예정 요청의 40%는 좁은 PRODUCT credential로 직접 HOLD, 20%는 Availability,
20%는 HTTP MCP Management 목록, 10%는 Runtime Management+Knowledge+합성 adapter,
10%는 HTTP MCP 이벤트 관측 조회다. 직접 HOLD는 정상 생성과 명시적인 같은 키 replay를
포함하므로 HTTP 성공 횟수는 새 예약 생성 수가 아니다. 새 생성 수는 독립 DB에서 별도로 읽는다.
Slot capacity와 Allocation units는 각각 1, Resource 좌석 수는 4, 요청 partySize는 2다.
Runtime은 하나이며 provider worker 4, 저장 Run 상한 4096이다. adapter는 호출 순서에 따라
50/250ms를 기다리고 매 7번째 호출을 실패시킨다. 실제 통과한 provider 호출 수를 raw에 남긴다.

Waitlist는 같은 Venue의 별도 14시 Slot을 사용한다. Product HOLD는 12시 Slot을 사용해
사전 준비 중 Waitlist가 모든 HOLD target을 차지하는 fixture 간섭을 피한다. 고객은 합성
별도 principal이다. 분산 구간에는 확정 예약을 한 번씩 취소해 실제 이벤트·receipt·Offer를
만들고, production maintenance와 waitlist/observation 두 scoped worker가 동시에 동작한다.
취소 실패도 원자료에 남기며 자동 재시도하지 않는다.

고정 예정시각을 기준으로 64개 발생기 thread의 `SynchronousQueue`에 각각 제출한다.
발생기가 차면 `GENERATOR_REJECTED`를 분모에 남긴다. 서비스 응답을 기다려 다음 유입을
만드는 closed-loop가 아니다. 그러나 발생기와 서비스가 같은 JVM/호스트를 공유하므로
발생기 자체 한계가 있다. 특히 drop이 발생한 높은 유입률은 서비스 포화 용량을 입증하지 않는다.
credential 준비는 각 구간 전에 완료하며 그 소요시간도 명세에 남긴다. 분산 구간의 취소 작업이
발생기 제출을 늦추는 영향은 dispatch lag에 포함된다. 요청이 밀리면 뒤늦게 제출하며 예정시각은 옮기지 않는다.

`SUCCESS / BUSINESS_CONFLICT / REJECTED / UNAVAILABLE / UNKNOWN / DENIED /
GENERATOR_REJECTED`를 각각 센다. 500·연결/응답 실패를 rollback이나 업무 거부로 취급하지 않는다.
빈/불완전 MCP envelope는 UNKNOWN이다. `responded`는 응답 관측이며 내부 admission 성공이 아니다.
HTTP/MCP/Runtime의 내부 수용·전송 여부 전체를 입증하는 지표는 아직 없다.

지연은 예정 도착→종료, 제출 지연, 실행 시작→종료를 구분하며 nearest-rank p50/p95/p99/max를
계산한다. 분모는 구간의 예정 요청 전체다. cohort의 성공/예정 시간과 실제 측정 window 안에
완료한 성공/초를 별도로 기록한다. 늦게 끝난 응답으로 window 처리량을 부풀리지 않는다.
`generatorDispatched`는 발생기 job을 실행한 횟수다. 실제 HTTP 전송이나 서버 admission의
증거로 바꾸지 않는다. 과부하 구간의 전체 지연에는 빠르게 drop된 요청도 포함되므로
outcome별 원자료와 service latency를 함께 확인한다.
구간 사이 drain·다음 credential 준비 시간이 있어 구간 전체가 연속 정상 유입이라는 주장은 하지 않는다.
회복은 burst 예정 종료부터 active/pending/MCP/Runtime/in-flight/backlog/unmaterialized가
모두 0인 연속 3 sample의 마지막 시각까지의 상한 관측이다. 측정되지 않으면 UNAVAILABLE이다.
이 값은 MCP permit 전체가 복구됐다는 증거를 대체하지 않는다.

## 자원·SQL·정합성 관측

- Hikari active/idle/pending과 Micrometer acquisition/usage timer의 누적 count/total/max,
  실제 HTTP JMX thread 수, MCP/Runtime worker, heap/가상 메모리/호스트 여유 RAM,
  process/system CPU, MySQL lock wait·thread·bytes·InnoDB read/write 누적값을 기록한다.
  sampler는 독립 root JDBC 연결을 사용하며 Product pool을 소비하지 않는다. MySQL/Docker
  관측·샘플링 자체의 비용이 포함되며 250ms 이하의 peak를 모두 관측했다고 주장하지 않는다.
- Auth proxy의 테스트 전용 계측은 `authenticateProduct/authenticateMcp/requireHoldAdmission`의
  벽시계 지연, 결과와 호출 전 외부 transaction 존재를 기록한다. Auth 지연에는 SQL과
  transaction 시작/연결 획득이 함께 들어간다. 이것만으로 모든 UNAVAILABLE을 pool starvation으로 분류하지 않는다.
- Availability/Management의 servlet thread Hibernate statement 수는 test-only inspector로 센다.
  JDBC Auth·다른 thread·background SQL은 제외되므로 전체 SQL 수로 표시하지 않는다.
  별도 20회 조회에서 response bytes와 tail latency를 측정한다. Knowledge는 다른 검색이
  없는 구간에 MySQL digest counter의 `SELECT ... knowledge_*` 차이를 기록한다. 이는
  검색의 corpus SQL 수이며 전체 인증 SQL 수가 아니다. 검색 응답 크기와 종단 지연도 별도 기록한다.
- 독립 MySQL 조회는 occupancy, idempotency 중복 참조, null receipt, DONE/receipt,
  cycle retry 상한과 실제 table count를 검사한다. 기존 Waitlist snapshot의 FIFO/Offer/receipt
  관련 행도 보존한다. aggregate 0만으로 시도별 현재 권한, 모든 이벤트 원자성,
  응답 유실 뒤 exact GET reconciliation을 검증했다고 주장하지 않는다.

pool 3/4의 기존 `ProductDatabaseBoundsIntegrationTests`는 그대로 유지한다. 결정적 gate로
외부 물리 연결 3개를 잡은 뒤 중첩 획득을 시작하는 회귀는 혼합 부하의 발생 빈도·처리량과 별도다.
pool 4가 일반적으로 충분하다는 결론을 내리지 않으며 테스트 통과를 위해 pool을 늘리지 않는다.

## 사전 기준과 결과

최종 사전 실험·본 검증·기준 digest와 실측 요약은 아래에 기록한다. 하네스 보강 도중의
`pilot-p3-s16*`, `pilot-p4-s16*`, 중단된 `pilot-final-*`는 본 검증에 합산하지 않는다.
특히 초기 비동기 MCP 응답 계측 결함이 있던 실행은 성공률/용량 근거에서 제외한다.

본 검증은 FROZEN 기준, 이전에 끝난 pilot raw digest, 동일 pool/규모/소스 digest를 요구한다.
미확정·미래 기준·pilot 변조·지원 설정 불일치·소스 변경은 실행 전에 거부한다.
단일 진단 지표 PASS와 #181 전체 지원 qualification의 INCOMPLETE를 분리한다.
관측된 지표 미달과 DB oracle 위반은 FAIL로 보존한다.

[capacity-diagnostic-v1 기준](m8-capacity-criteria-v1.json)은
2026-10-25 04:34:49 KST에 고정했다. pilot은 `pilot-v4-p4-s16`이며 분산 p95
608.7718ms, dispatch p99 62.4036ms, UNAVAILABLE 2/160, 회복 4963.8811ms였다.
본 검증은 별도 disposable MySQL에서 30초 분산 구간을 포함해 3회 수행한다.
첫 기준의 이전 값은 null이며 변경이 필요하면 새 버전과 새 실행을 사용한다.

| 본 검증 진단 항목 | 사전 상한 | 분모·근거 |
| --- | ---: | --- |
| 분산 scheduled p95 | 2000ms | 예정 600건 전체; 기존 2초 획득/Auth timeout보다 빠른 정상 구간 |
| 분산·회복 dispatch p99 | 100ms | 각 예정 구간 전체; pilot 62.4ms에 대한 발생기 지연 한계 |
| 분산·회복 비업무 실패 | 5% | 예정 전체; UNAVAILABLE/UNKNOWN/DENIED/REJECTED/발생기 drop, 주입 실패도 포함 |
| 정상 요청 회복 scheduled p95 | 1000ms | 회복 예정 50건 전체; pilot 약 551ms |
| burst 이후 자원 회복 관측 상한 | 15000ms | burst 예정 종료부터 연속 3 idle sample; pilot 약 5초, pool 3 pilot 약 11초 |

80/s·320/s의 과부하 처리량은 지원 수용량으로 판정하지 않는다. drop·거부·불명을 보존하고
그 뒤 회복을 확인한다. 이 진단 기준의 FROZEN은 #178 공통 출시 capacity 등록부의 완료를
뜻하지 않는다. 공유 출시 기준은 PENDING이며 전체 지원 qualification은 INCOMPLETE다.
실행 명령 exit 0은 자료 생성 완료이며, 지표 PASS를 뜻하지 않는다.

### 실측 환경과 사전 실험

2026-10-25 KST에 기준 `main=81fe48920818e00a547e720e691ec08019ddd523`의
dirty 작업 트리에서 실행했다. 여섯 실행의 source/resource/build digest로 이번 구현 소스를
연결한다. 실행 당시 revision/dirty를 최종 commit으로 소급 변경하지 않는다.
최종 source/resource/build 파일 541개를 여섯 manifest와 대조해 모두 일치함을 확인했다.
Windows 11, Ryzen 7 6800H(8 core/16 logical), RAM 16,329,510,912 bytes,
NVMe SSD/NTFS와 loopback HTTPS/Docker TCP를 사용했다. Java `25.0.4.1+1-LTS`,
JVM `-Xmx768m`, Docker VM 16 CPU/7,910,813,696 bytes, MySQL `8.4.11`,
buffer pool 134,217,728 bytes, max_connections 151, REPEATABLE-READ다.
컨테이너 memory limit 0은 별도 제한 없음이며 Docker VM 자원 범위 안에서 동작한다.
image ID는 `sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`다.
live HTTP JMX의 maxThreads는 64였다. 기존 MCP connector 설정의 maxConnections 128,
acceptCount 16을 사용하며 기본 production 설정을 변경하지 않았다.

| 최종 pilot | Slot / 최종 Reservation / 문서 | 분산 성공 / 예정 | 분산 scheduled p95 | pool pending 최대 | Auth HOLD admission UNAVAILABLE / 호출 | 회복 상한 관측 |
| --- | --- | --- | ---: | ---: | --- | --- |
| pool 3, size 16 | 32 / 41 / 16 | 50 / 160 | 4810.806ms | 67 | 22 / 168 | 11024.482ms |
| pool 4, size 16 | 32 / 48 / 16 | 158 / 160 | 608.772ms | 68 | 19 / 272 | 4963.881ms |
| pool 4, size 64 | 128 / 144 / 64 | 88 / 160 | 5018.909ms | 68 | 23 / 313 | UNAVAILABLE |

Auth 분모는 전체 workload 중 `requireHoldAdmission` 호출 수이며 HTTP 요청 수가 아니다.
모두 호출 전 외부 transaction이 존재했다. pool pending 및 Auth UNAVAILABLE은 혼합
부하에서 실제 관측됐지만 개별 UNAVAILABLE의 원인을 모두 연결 획득으로 단정하지 않는다.
작은 pool의 결정적 회귀와 이 빈도·처리량을 합산하지 않는다. pool 4도 과부하에서
UNAVAILABLE/UNKNOWN과 높은 pending을 보였으므로 일반적인 충분 조건이 아니다.

### 고정 기준의 별도 본 검증

| 반복 | 분산 성공 / 예정 | 비업무 실패 | 분산 p95 / dispatch p99 | 회복 요청 p95 | burst 이후 자원 회복 | 진단 판정 |
| --- | --- | --- | --- | ---: | --- | --- |
| r1 | 591 / 600 | UNAVAILABLE 9 (1.5%) | 527.651 / 15.432ms | 517.039ms | 3765.295ms | PASS |
| r2 | 591 / 600 | UNAVAILABLE 9 (1.5%) | 525.245 / 15.696ms | 528.624ms | 5575.936ms | PASS |
| r3 | 591 / 600 | UNAVAILABLE 9 (1.5%) | 520.473 / 15.511ms | 524.876ms | UNAVAILABLE | INCOMPLETE |

분산 구간은 매번 600건, 전체 workload는 매번 2280건이다. 반복 평균으로 r3의 누락을
상쇄하지 않는다. r3에서도 새 정상 구간 요청은 성공 49/50, UNAVAILABLE 1이었으나
정한 연속 3 idle sample의 회복 상한은 확보하지 못했다. 정상 요청의 응답 관측과 전체
자원 회복 판정은 다르다. 기준을 바꾸거나 이 실행을 PASS 실행으로 교체하지 않았다.
분산 window 안에서 완료한 성공은 매번 19.633/s였고, 발생기 job의 최대 동시 실행은
각 5/5/6이었다. 과부하에서는 세 실행 모두 발생기 상한 64에 도달했다.

본 검증의 pool pending 최대는 67~68, 관측 process CPU 최대는 전체 가용 CPU 기준
약 27.8~30.7%, heap 최대는 약 204~232MiB, MySQL lock wait sample 최대는 1이었다.
이 수치는 관측 sample의 최대이며 연속 peak 또는 호스트의 전체 CPU/메모리 한계가 아니다.
과부하에서 발생기 drop과 서비스 거부·일시적 사용 불가·결과 불명이 동시에 발생했다.
80/s·320/s의 성공/초를 서비스의 최대 수용량으로 사용하지 않는다.
여섯 실행에서 검사한 aggregate oracle 위반은 0건이었다. 본 검증의 최종 Reservation은
각 48개, 최종 delivery는 각각 DONE 44/34/42개였으며 횟수 차이는 실제 background
스케줄링의 결과다. 전체 시도별 원자성/권한의 PASS를 뜻하지 않는다.
기존 독립 Waitlist snapshot의 occupancy/partial receipt/중복 logical effect/미완료 request link도
세 본 검증 모두 0이었다.

### 데이터 규모별 비용

아래는 최종 pool 4 pilot의 별도 조회 각 20회 결과다. 표의 SQL 범위는 앞의 계측 정의를 따른다.

| 규모 | 조회 | SQL 수 | p95 / p99 | 최대 응답 bytes |
| --- | --- | ---: | --- | ---: |
| Slot 32 / Reservation 48 / 문서 16 | Availability | Hibernate 35 | 54.402 / 55.293ms | 8621 |
| 동일 | Management | Hibernate 100 | 135.366 / 138.422ms | 18145 |
| 동일 | Knowledge | corpus SELECT 6 | 202.093 / 207.065ms | 4615 |
| Slot 128 / Reservation 144 / 문서 64 | Availability | Hibernate 131 | 171.950 / 176.658ms | 34253 |
| 동일 | Management | Hibernate 292 | 323.557 / 324.468ms | 54385 |
| 동일 | Knowledge | corpus SELECT 6 | 205.051 / 208.847ms | 4617 |

Knowledge는 모든 표본에서 `category=evidence`, 결과 5개를 관측했다. Availability의
Slot별 occupancy 조회와 Management의 Reservation별 후속 조회 비용이 규모와 함께
늘어나는 현재 코드 경로도 확인했다. 이 측정만으로 포괄적 persistence 재설계를 시행하지 않는다.
더 큰 Venue/문서 규모와 전체 JDBC 인증을 합친 SQL 상한, 장시간 tail 안정성은 미검증이다.
SQL p95는 `queryCosts`를 operation으로 나눈 20개 `elapsedMs` 정렬의 19번째 값,
p99는 20번째 값으로 재계산할 수 있다.

### 자료 위치와 무결성

`backend/build/m8-capacity/<run>/`의 원자료를 `clean` 전에
`build/issue181-evidence/capacity/<run>/`에 복사했다. 이 두 경로 모두 Git에서 제외된다.
재계산 로그, host 명세, 관련 회귀 및 최종 build 로그도 이 ignored 보존 위치와 연결한다.
본 검증 시작은 각각 manifest에 기록되며 기준 고정(04:34:49 KST) 이후다.
여섯 실행 모두 별도 JVM의 `m8CapacityRecalculate`로 digest와 저장 summary를 검증했다.

| run | raw.json SHA-256 |
| --- | --- |
| pilot-v4-p3-s16 | `2047b7dd2feaf8061e1744c0b7c75180de268d96e00bab4732121583b364c6d3` |
| pilot-v4-p4-s16 | `b0f3d190b45c2421974f835ccbd70c2afc9bb51bdd603d1a803959938afb2a21` |
| pilot-v4-p4-s64 | `0a9cefbd4bb2548504de6cdc9810765d591abda2207213bb06b559b26f1241be` |
| verification-v1-r1 | `df59011ca8bee7da118d117f0f57748066fad1749ef3cc2056b8777819c141eb` |
| verification-v1-r2 | `88cf5248a3e60fce53248872d247b2a6095c57484a371c868c5c157fbce9191d` |
| verification-v1-r3 | `131c8dd882f546af60a71bc5c84bdb41b66ff3a37f43ac744b971c6ab9dd6bdb` |

FROZEN 기준 파일의 SHA-256은
`20781a47007d2eb0846c4994a625963f1a38e4ae2f07a8d64a4e9f4f0614b868`이다.
이는 실행 당시 Windows CRLF 바이트이며 `build/issue181-evidence/criteria-executed-v1.json`에
별도 보존했다. 저장소의 UTF-8/LF 표현은
`e9e7d298ae74dc492c7cb975897838d723e30cf7f972dc2878197913d79d9049`이다.
줄바꿈만 정규화한 두 JSON의 전체 값이 동일함을 확인했으며 실행 기준·판정은 변경하지 않았다.

## 후속 전달 및 미충족 사항

#182·#183·#185는 이 자료를 단일 DB_DIRECT 실험의 재현 입력·한계로 재사용할 수 있다.
아직 검증된 운영 지원 유입률이나 pool 최소값은 없다. 높은 부하에서 발생기가 포화되면
독립 발생기로 최종 구성에서 다시 측정해야 한다. 호스트 device IO 지연, MySQL 프로세스별
CPU/메모리, 내부 admission·permit 전체, 시도별 권한·이벤트 원자성·응답 유실 reconciliation,
더 큰 Venue/문서 규모와 장시간 지속 부하는 남은 필수 관측이다. 20회 조회의 p99는 사실상
최댓값이며 통계적 안정성 보장이 아니다. persistence 구조 변경이나 SLA 기준은 제안하지 않는다.

## 자동 검증

Java 25에서 다음 관련 회귀와 fixture 검증을 실행했다.

```powershell
.\gradlew.bat test --tests com.slotq.mcp.M8CapacityEvidenceTests --tests com.slotq.mcp.ProductDatabaseBoundsIntegrationTests --tests com.slotq.mcp.ProductToolsIntegrationTests --tests com.slotq.mcp.McpEngineConcurrencyTests --tests com.slotq.mcp.McpAccountingTests --tests com.slotq.WaitlistPromotionDeliveryIntegrationTests --tests com.slotq.WaitlistMaintenanceIntegrationTests --tests com.slotq.experiments.waitlist.WaitlistBaselineEvidenceTests --tests com.slotq.experiments.waitlist.M5TransportEvidenceTests --tests com.slotq.experiments.concurrency.ConcurrencyBaselineSupportTests validateM8Fixtures --console=plain
```

10 suites, 169 tests, 168 통과, 실패·오류 0, 기존 opt-in gap-lock 진단 1 skip이다.
skip은 `WaitlistPromotionDeliveryIntegrationTests.diagnosticBaselineCapacityGapIsEngineTransient`
(`SLOTQ_CAPACITY_LOCK_EVIDENCE=true` 전용)이며 pool 3/4 회귀의 skip이 아니다.
새 하네스 회귀는 15개 모두 통과했다. fresh pool 3 회귀의 획득 대기는
2007/2007/2009ms, pool 4 회귀는 대기 실패 없이 137ms에 끝났다.
이 결정적 결과를 혼합 부하의 발생 빈도와 구분한다. `validateM8Fixtures`도 통과했다.

전체 Backend 테스트를 포함하는 `.\gradlew.bat clean build --console=plain`은 한 번 실행해
33분 42초에 성공했다. 119 suites, 총 1283 tests 중 1271 통과, 실패·오류 0,
기존 opt-in 12 skip이다. 별도 전체 `test`는 반복하지 않았다.
새 하네스·Auth 계측이 배포 JAR에 포함되지 않음도 확인했다.
원자료 재계산 6회와 관련 회귀 로그/JUnit은 ignored `build/issue181-evidence/`에 보존한다.
