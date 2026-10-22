# M8 보안 회귀 검증

[#184](https://github.com/krestar/slotq/issues/184)는 기존 인증·권한 강제 지점을 반복 실행하고,
실제 요청 전송·정보 공개·최종 업무 상태를 검사한다. [M6 접근 계약](../architecture/m6-access-knowledge.md),
[M7 Runtime 계약](../architecture/m7-model-router-agent-runtime.md), ADR-0009와 기존 `401/403/404` 의미를 유지한다.

지원 범위는 단일 Product/MCP/Router/Runtime, MySQL 8.4, DB_DIRECT, process-local Run이다.
Product 역할별 HTTP 회귀는 합성 인증 fixture를 사용한다. 실제 고객·운영자 SPA 로그인이나 공개 인증 서비스의
운영 검증 근거가 아니다. 외부 모델 credential과 모델 호출은 사용하지 않으며 공격성 입력의 실제 모델 평가는 #180 소유다.

## 실행과 판정

Java 25, 로컬 Docker와 `backend/`의 Gradle Wrapper를 사용한다.

```powershell
.\gradlew.bat test --tests com.slotq.evaluation.M8SecurityRegressionTests
.\gradlew.bat m8SecurityRegression
```

`m8SecurityRegression`은 [사례 목록](../../backend/src/test/resources/evaluation/security-v1/cases.json)의
필수 테스트만 선택한다. 기존 통합 테스트를 복제하지 않으며 외부 모델 대신 결정적으로 주입한 provider만 사용한다.
매번 새로운 JUnit 경로를 사용하므로 이전 성공 결과로 누락을 채울 수 없다.
완료 후 `m8SecurityReport`가 자동 실행되어 ignored `build/m8-evidence/security-<UUID>/`에
`catalog.json`, 원문을 제외한 `observations.json`, `results.json`, `summary.ko.md`를 만든다.
마지막 실행 ID와 evidence digest는 저장소 루트의 `build/m8-evidence/latest-security-run.txt`에 기록한다.

결과표는 #179의 `M8Scorer.Mark`, 상태 결합, `rows/checks/hardGateViolations/cohorts` 형식과 한국어 report writer를
재사용한다. JUnit 오류 원문·stdout을 결과표로 복사하지 않고 selector별 실제 실행·성공·실패·skip과 원본 XML digest를
연결한다. 사례 목록 digest와 실행한 test class digest도 남긴다. 같은 보호 자료를 읽어 같은 결과를 재계산하는
오프라인 회귀를 포함하며, Runtime 원자료의 별도 재계산은 [#179 안내](m8-evaluator.md)의 `m8Recalculate`를 따른다.

`securityStatus=PASS`는 고정된 19개 보안 사례의 필수 assertion이 모두 실행·통과했다는 뜻이다.
하나라도 실패하면 `FAIL`, 누락·skip이면 `INCOMPLETE`다. 중복 관측도 실패하며 정상 사례 비율이나 품질 평균으로
상쇄하지 않는다. 테스트 실패에는 환경 장애도 포함될 수 있으므로 이를 모두 실제 침해 건수라고 부르지 않는다.
보안 회귀가 통과해도 전체 M8 `status`는 `INCOMPLETE`, `releaseQualification=false`로 유지한다.
사람의 의미 검토·#180 반복 품질·#181~#183 운영 검증·#185 출시 판단을 대신하지 않는다.

## 사례와 권한 경계

각 사례의 정확한 Actor·credential·Tenant/Venue·진입 경로·기대 결과·관측·test selector는 위 목록에 함께 고정한다.
다음 표는 검토를 위한 경계별 요약이다. 같은 테스트를 여러 계약의 근거로 재사용해도 새 모델 표본이나 독립 반복으로 세지 않는다.
이 목록은 보안 coverage를 위한 별도 `security-v1` 계약이다. 기존 `m8-v1`의 60개 사례·oracle·분모·holdout 분할은 변경하지 않는다.

| 사례 ID (`security/` 이하) | 허용·거부 기준과 주요 관측 |
| --- | --- |
| `product-auth` | 공개 조회와 보호 API 401, 명시 origin CORS, no-store와 credential 비노출 |
| `reservation-roles` | Customer 본인 조회, Owner·Manager·Staff 명령 행렬; 403/404와 저장된 업무 상태 |
| `waitlist-roles` | Customer 본인 명령, Owner·할당 Manager 조회; 타인/Staff 거부와 offer 등 업무 상태 |
| `management-scope` | 서버가 조회한 Tenant/Venue scope, Staff 설정 거부와 실제 설정 보존 |
| `credential-audience` | ORIGINAL/MCP/PRODUCT/복구/스크래핑 용도 분리; 실제 HTTP와 audit |
| `product-material` | method·path·operation·target·Slot·partySize·key를 실제 PRODUCT에서 검증 |
| `current-authority` | 인증 뒤 역할 축소·할당 해제·만료·철회; current read와 결과 공개 재검사 |
| `run-identity-budget` | 타 Actor/Tenant/Venue·새 delegation의 Run/승인 승계 거부와 누적 예산·deadline |
| `exact-approval` | original Actor의 정확한 승인만 허용; 바꿔치기·만료 승인 미전송과 DB 불변 |
| `untrusted-instructions` | RAG·모델·자연어가 숨은 도구·승인·target 변경 권한을 만들지 못함 |
| `retrieval-prescope` | 타 Tenant/Venue·operator-only sentinel의 후보·provider 입력·반환 결과 비노출 |
| `publication-revalidation` | bounded latch로 실제 교체/철회 후 이전 본문·version 제외; 마지막 검증 이후 소급 취소 없음 |
| `provider-predispatch` | 데이터 종류·전송 조건 부적합·권한 거부 시 provider 호출 0; 기존 terminal 규칙 |
| `staff-and-ops-authority` | Staff M6 예약 조회 유지; M7 관리/Ops와 이벤트 관측은 현재 Owner/할당 Manager만 허용 |
| `ops-observation` | projected-at·누락·null·지연·잘림의 의미와 조회 전후 상태 불변 |
| `redaction` | 일반 오류·audit·trace·metric 이름/tag의 credential·key·본문·비공개 sentinel 비노출 |
| `runtime-configuration` | production 개발 bootstrap 비활성, 명시 CORS·고정 origin·TLS, 비활성 MCP/복구 비노출·명시 registry |
| `architecture` | Product/Auth의 AI 역의존과 MCP의 Product DB 우회 금지, 공개 Run/approval surface 부재 |
| `evaluator-observations` | 실제 Runtime→MCP→Product/DB 연결과 #179 거짓 PASS 방지·결과 재계산 |

## 보완한 관측

HOLD 전송별 `authorized`는 실제 Product application admission이 성공한 뒤에만 기록한다.
credential record ID로 해당 HTTP 전송에 연결하고 original principal·Tenant/Venue·작업·target을 대조한다.
승인은 별도 JDBC 조회로 저장된 intent와 confirmation의 original 승인자·정확한 delegation·범위·Slot·partySize·유효기간을
확인하며, 실제 전송한 key와 material이 그 intent에 연결되는지도 대조한다. key 원문은 artifact에 남기지 않는다.
Auth를 mock 성공으로 바꾸거나 production `REQUIRES_NEW`를 수정하지 않는다. 관측이 없으면 기존 #179처럼 `INCOMPLETE`다.

금지된 예약 명령·승인 바꿔치기·만료 승인·PRODUCT material 변경·Waitlist offer 명령·악성 제안에는 기존 HTTP/Runtime
assertion에 독립 업무 snapshot을 추가했다. 새 JDBC connection에서 Reservation, Allocation, 멱등성 기록, Waitlist demand/entry/
등록 receipt/offer/승급·알림, event record/delivery/projection의 **모든 row 값**을 읽어 전후 digest를 비교한다.
row 수가 같아도 상태가 바뀌면 실패한다. row 원문과 key는 일반 결과에 기록하지 않는다.
허용 HOLD도 관측된 Waitlist·이벤트 등 다른 업무 table digest가 바뀌면 #179 채점기가 `FORBIDDEN_EXECUTION`으로 실패한다.
MCP에서 거부돼야 하는 경로는 실제 Product client 호출 0도 검사한다. Product가 거부하는 경로의 실제 전송과
application 거부는 미전송과 구분한다.

범위 밖 sentinel 연결 검증은 실제 SQL catalog와 검색 후보, Runtime의 실제 provider callback 입력과 반환 결과를 함께
확인한다. 합성 공개 문서는 허용하고 operator-only·타 Venue·타 Tenant의 본문과 document/version ID는 제외한다.
문서 철회·교체의 순서 제어와 RR current-read 검증은 기존 테스트를 유지한다. 자연어 의미 검토나 실제 모델의
공격 저항성을 이 결정적 callback 검사만으로 승인하지 않는다.

원자료는 #179의 owner 전용 ACL/POSIX 보호 경로를 사용하고 기본 90일 보존 metadata를 기록한다.
Git에는 사례 목록·재현 가능한 코드·이 요약만 추적한다. JUnit XML·전체 로그·합성 prompt/response·실행별 JSON은 제외한다.

## 로컬 검증 결과

- `m8SecurityRegression validateM8Fixtures`: 필수 테스트 81개, 실패·오류·skip 0. 필수 보안 사례 19개 모두 PASS이며 fixture 검증도 통과했다.
- `m8SecurityRegression --tests com.slotq.evaluation.M8SecurityRegressionTests`: 의도적인 일부 실행으로 필수 관측을 누락시켰다. 보호 결과표의 `securityStatus=INCOMPLETE`와 nonzero 종료를 확인했다.
- #179 Runtime 연결 7개 cell의 자동 검사는 PASS 6, INCOMPLETE 1이다. 승인 없이 거부된 요청의 미호출 최종 모델 결과는 INCOMPLETE로 유지한다. 전체 M8 판정도 INCOMPLETE다.

전체 Backend `test`와 `clean build`의 최종 실행 결과는 PR의 검증 표에 기록한다. 실제 외부 모델 호출과 운영 출시 검증으로 해석하지 않는다.
