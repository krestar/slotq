# 혼합 부하 검증 하네스

[#181](https://github.com/krestar/slotq/issues/181)의 단일 Product/MCP/Router/Runtime,
MySQL 8.4, DB_DIRECT 검증용 opt-in 실행기다. [M8 공통 계약](../architecture/m8-evaluation-contract.md)을 따른다.
외부 모델 호출 없이 합성 Actor와 통제된 adapter를 사용한다. 모델 품질·제공자 처리량·SLA 검증에는 사용하지 않는다.

## 실행 순서

JDK 25와 Docker를 준비하고 `backend/`에서 실행한다. 각 실행에는 새 ignored `build/` 하위 경로를 사용한다.
아래 변수는 실행자가 정한 로컬 경로와 지원 후보 설정이다.

```powershell
.\gradlew.bat m8Capacity -PcapacityStage=pilot -PcapacityPool=$pool -PcapacitySize=$size -PcapacityRate=$rate -PcapacitySeed=$seed -PcapacityOutput=$pilot
.\gradlew.bat m8CapacityProtocol -PcapacityAction=freeze -PcapacityInput=$localPlan -PcapacityOutput=$frozen
.\gradlew.bat m8Capacity -PcapacityStage=verification -PcapacityPool=$pool -PcapacitySize=$size -PcapacityRate=$rate -PcapacitySeed=$seed -PcapacityCriteria=$frozen -PcapacityOutput=$verification
.\gradlew.bat m8CapacityRecalculate -PcapacityInput=$verification -PcapacityDigest=$expectedRawDigest
.\gradlew.bat m8CapacityProtocol -PcapacityAction=series -PcapacityInput=$localSeriesIndex -PcapacityOutput=$newSeriesReport
```

사전 실험 후 로컬 계획에 `schemaVersion=slotq-m8-capacity-criteria/v2`, `state=PENDING`,
`pilotPath`, `pilotDigest`, `pool`, `size`, `rate`, `seed`, `repeats`, `reason`, `unsupported`, `metrics`를 기록한다.
`metrics`의 필수 키는 `M8CapacityProtocol.validateV2`가 검사한다. 판정 근거, 이전 기준과의 관계,
분모·측정 단위·지원 범위와 미지원 범위도 이 로컬 계획에 기록한다.
freeze는 이전 pilot의 digest·시각·설정·소스를 확인하고 기준 값을 바꾸지 않고 FROZEN 파일을 새로 만든다.
본 검증 전에 파일의 SHA-256과 시각을 별도로 보존한다. 기준 파일 수정이나 실행 결과 확인 후 기준 완화는 허용하지 않는다.
각 반복은 별도 MySQL에서 실행한다. series index에는 `criteriaPath`, `criteriaDigest`,
`runs`의 `path`·`digest`를 기록한다. series는 모든 원자료·저장 요약·동일 기준·소스·독립 실행 식별자를 재검증한다.
판정 항목은 고유 `gate` ID로 대조한다. 표시 순서는 허용하되 모든 값이 같아야 하며 누락·중복·수치나 판정 차이는 거부한다.
원본 요약을 덮어쓰지 않고 재계산 코드 digest를 별도로 기록한다.

## 관측과 판정

- 예정 도착부터 terminal까지의 지연에 제출 지연을 포함한다. 발생기의 미수용도 예정 요청 분모에 포함한다.
  같은 HTTPS client와 발생기를 별도 echo 서버에서 교정한다. 발생기 동시 요청 상한 밖의 서버 용량은 주장하지 않는다.
- Product는 보안 필터 통과 후 handler 진입, MCP는 등록 tool handler 진입을 수용으로 정의한다.
  HTTP 도착·서버 완료·클라이언트 응답·업무 충돌·quota 거부·일시적 사용 불가·결과 불명을 따로 기록한다.
  도착이나 수용 여부를 확인할 수 없는 응답 유실은 미수용으로 추정하지 않는다.
- Hikari·실제 연결 획득·중첩 Auth, HTTP/MCP/Runtime worker·permit,
  JVM·호스트 CPU/메모리, Docker MySQL CPU/메모리/네트워크, MySQL file IO·저장 크기·잠금 대기·이벤트 backlog를 기록한다.
  독립 DB 관측은 Product pool을 사용하지 않으며 fast sampler와 분리한다.
- burst 예정 종료부터 새 요청을 넣지 않는 구간에서 DB·worker·permit·backlog를 관측하고 이후 정상 요청을 별도로 실행한다.
  완료 관측에서 기준을 초과하면 FAIL, 필요한 sensor나 구간이 빠졌으면 INCOMPLETE다.
- 실제 Product 트랜잭션의 좁은 gate와 별도 물리 MySQL 조회로 현재 권한, Tenant/Customer 격리,
  업무 변경/이벤트 원자성, receipt/DONE과 유한 재시도를 확인한다.
  commit 후 응답 body 유실은 UNKNOWN으로 유지하고 이미 알려진 Location의 정확한 GET으로 확인한다. 변경 요청을 자동 재시도하지 않는다.
- 동기 Product 조회의 SQL은 Auth JDBC 실행을 포함하며 Hibernate 수와 구분한다.
  문서 검색은 다른 Knowledge 요청이 없는 구간의 MySQL statement digest 차이로 측정한다.
  목록의 SQL 증가를 실측하되 이 하네스는 persistence 구조를 바꾸지 않는다.

`M8CapacityQualificationTests`의 값과 PASS는 합성 계산 fixture다. 실행 증거가 아니다.
v1 자료는 기존 계산과 INCOMPLETE 의미를 유지하며 v2에 필요한 관측을 소급해서 채우지 않는다.
반복 중 하나의 실패를 평균으로 상쇄하지 않는다. 최종 지원 판정은 frozen 반복 수를 충족한 series 결과로 확인한다.

## 자료 보관과 한계

원자료·요청별 기록·전체 로그·DB snapshot·실측 summary·결과표·manifest·환경 정보·실행 기록·FROZEN 기준 JSON·digest·재계산 결과는
모두 로컬 ignored 경로에만 보존한다. `clean` 전에 필요한 `backend/build/` 자료를 저장소 루트의 ignored `build/`로 복사한다.
기존 자료를 덮어쓰지 않는다. Git에는 하네스·자동 검증·합성 테스트·일반 실행 방법만 남긴다.
독립 리뷰에 필요한 로컬 보관 위치·접근권한·보존 기간과 #182/#183/#185가 재사용할 지원 설정·규모는 로컬 인계 기록으로 연결한다.

발생기·서버는 같은 JVM, DB는 같은 Docker 호스트를 공유한다. 수치는 해당 환경과 고정된 요청 구성에 한정한다.
동일 키 재호출과 새 키 충돌을 새 예약 생성 처리량과 구분한다. Docker의 block-device 세부 통계가 비어 있으면
MySQL file IO의 범위를 명시하며 장치 포화 한계나 외부 네트워크 용량을 주장하지 않는다.
검증하지 않은 데이터 규모·유입률, 공개 로그인, 다중 인스턴스·HA, 실제 모델, 재시작 복구는 별도 검증 대상이다.
