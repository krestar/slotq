# M8 공통 검증 계약 v1

[#178](https://github.com/krestar/slotq/issues/178)의 지원 환경, 합성 데이터셋, oracle,
판정·자료 식별 계약이다. 기준 소스는 `848aeb8eb96214527d4c74ca1ae4ee02f9b54b76`이다.
이 문서와 fixture 검증의 통과는 모델 품질, 운영 검증 또는 출시 승인이 아니다.
구체적인 출시 수치는 아래 담당 Issue가 사전 실험 후 본 검증 전에 확정한다.

## 지원 구성과 실행 명세

[M6 접근 계약](m6-access-knowledge.md), [M7 공통 계약](m7-model-router-agent-runtime.md),
[ADR-0009](../adr/0009-m6-authenticated-access-and-knowledge.md),
[ADR-0010](../adr/0010-m7-bounded-model-router.md),
[ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)을 유지한다.
Product가 인증·Tenant·transaction·idempotency·수용량과 업무 상태의 권위다.
같은 Product JVM의 opt-in MCP는 좁은 PRODUCT credential로 고정 origin의 Product HTTP를 호출한다.
Router는 immutable snapshot의 후보 적격성과 선택을 계산하고, Runtime은 서버 Plan,
현재 original Actor·정확한 위임·승인·누적 예산·deadline을 강제한다. Knowledge는 안내만 제공한다.

지원 환경은 Java 25, MySQL 8.4, DB_DIRECT, 단일 live Product/MCP/Router/Runtime과
bounded process-local AI다. Run의 재시작 연속성, 자동 mutation replay, 대체 모델·provider 호출,
공유 quota, 다중 인스턴스, HA/SLA, 공개 로그인·SPA의 공개 서비스 출시는 미지원이다.
DB/JVM/호스트가 공유하는 장애 영향이 있으며 DB_DIRECT는 DB 장애를 우회하지 않는다.
Kafka는 ADR-0008의 별도 실험 지위를 유지하고 이 지원 구성의 PASS에 합산하지 않는다.

실행 명세는 [합성 회귀 템플릿](../../backend/src/test/resources/evaluation/m8-v1/manifest-regression.json)과
[출시 전 템플릿](../../backend/src/test/resources/evaluation/m8-v1/manifest-pre-release.json)으로 구분한다.
두 파일은 `kind=TEMPLATE`, `measured=false`이며 실제 실행 기록이 아니다.
pool 16 등 수치는 M7 통합 테스트에 맞춘 작성 예시다. 성능·안전한 최소 설정의 근거가 아니며
호스트 자원 예시도 현재 컴퓨터를 측정한 값이 아니다. #181이 검증할 설정·규모로 대체해야 한다.

| 명세 항목 | 실행 전에 기록·확인할 내용 |
| --- | --- |
| 위치·자원 | 격리된 호스트 식별자, OS/CPU와 제한, 메모리/JVM flags, 저장소 종류·용량, 네트워크 경로·측정 조건 |
| DB | 정확한 MySQL image/build, 스키마/Flyway, password 공급 참조, Hikari pool·획득/연결/socket/query/lock/transaction timeout |
| 수용·worker | HTTP thread와 Product 호출 여유, MCP worker/rate/burst/concurrency/handler timeout, provider worker/concurrency, Runtime 누적 한도 |
| scheduler | event/Waitlist 활성화, batch·poll·lease·effect timeout·유한 retry 설정, recovery 활성 여부 |
| 데이터 | Tenant/Venue/Customer/Slot/Reservation/문서 수, seed, 기준 시각, Venue timezone, 생성 UUID·시각 binding |
| 접근·노출 | 개별 original Actor와 narrowed PRODUCT credential, 정확한 승인, TLS·고정 origin·CORS, 외부 공개 없음, disposable 대상 |
| identity | source/dirty/build/config와 데이터셋·oracle·prompt·schema·scorer·criteria·지원 구성의 버전 및 digest |

일반 fixture/schema 검증은 모델 credential, Docker, 외부 네트워크가 필요 없다. Product/MySQL
회귀는 기존 disposable Testcontainers와 결정적 provider를 사용한다. 테스트 컨테이너의 임시
credential을 출시 전 구성으로 복사하지 않는다. 출시 전 구성은 개발 bootstrap을 끄고
비밀번호 없는 local DB나 broad root/admin credential을 사용하지 않는다.
템플릿의 `ENV_REF:*`는 공급 방법이며 실제 값이 아니다. 실행 전 실제 credential의 만료·철회,
개별 Actor·위임·승인 연결과 환경 접근 범위를 확인하는 세부 보안 기준은 #184 소유다.

실제 외부 호출은 기존 두 exact Gemini Flash-Lite 모델, 고정 endpoint와
`generateContent-standard-free`만 허용한다. 서버가 선택하는 모델을 변경하지 않는다.
합성 데이터만 전송하고 무료 tier의 학습·사람 검토 조건을 허용한다. 적용 지역·보관 기간은
`UNAVAILABLE`이며 보장으로 바꾸지 않는다. 유료 호출·자동 retry/fallback은 금지한다.
실행 담당자는 호출 직전 무료 이용 가능 여부와 약관·모델/API의 변경을 확인해 근거·시각과
유한 호출/token/시간/USD 0 예산을 고정한다. 여기서는 외부 호출이나 새 약관 확인을 수행하지 않는다.

## 버전이 관리되는 사례와 oracle

[dataset.json](../../backend/src/test/resources/evaluation/m8-v1/dataset.json)은 60개의 필수 합성 사례를
담는다. `id/version/origin`, 분할, Actor/Tenant/Venue/Customer, 정확한 대상과 범위 관계,
입력·합성 fixture·근거, 평가 축·실행 lane, 모델 proposal, 검색 reference, 답변 attribution,
invocation/effect/DB/reconciliation, 금지 효과, 서버 Plan, 사람 검토 여부를 각각 기록한다.
`fixture`의 신규 시나리오는 손으로 작성한 합성 조건이며 실제 고객 자료에서 추출하지 않았다.
모델에 전송하는 내용과 실행 준비에만 사용하는 fault/approval/DB oracle을 분리하는 것은 #179 소유다.
기대 답을 모델 입력에 붙여 평가를 통과시키지 않는다.

| 출처 | 사례 수·identity | 보존하는 의미 |
| --- | --- | --- |
| M6 검색 | 18개, `m6/<기존 query id>` | retrieval-oracle v1의 scenario/category/document/versionId/sourceId와 세 seed manifest |
| M7 Router | 16개, `m7-router/<기존 case id>` | comparison-v3의 원래 입력·fixture·oracle, primary 12개와 과거 holdout 4개, workload와 3회 반복 정책의 원본 |
| M7 대표 흐름 | 4개, `m7-flow/<기존 case id>` | evidence-v6의 필수 disposition/source/claim, 실제 서버 Plan과 request context로 allowed set을 정하는 규칙 |
| 신규 M8 | 22개, `m8/<case id>` | 성공·업무 거부·미전송·commit 후 응답 유실·불명 target·GET 404, 세 격리 경계, Staff Ops 거부, 고정 날짜/시간, 두 출처 대응, 잘못된 인원, 문서 삭제, 세 업무·다섯 축의 새 holdout |

원본 경로·revision·정규화 SHA-256을 `sources`에 고정한다. 기존 파일은 수정하지 않는다.
`comparison-v2`와 내용 검토 정책도 참조 identity를 보존하지만 새 사례나 실제 모델 표본으로
다시 세지 않는다. M6 seed의 고정 UUID는 원본 manifest를 따른다. M7/M8의 논리적
`tenant-a/b`, `venue-a/a2/b`, `customer-a/a2/b`, `slot-demo-A`, 예약 symbol은 실행별 binding에
생성 UUID와 실제 시각을 연결한다. binding key는 실행·fixture group·repeat·model·lane·symbol이다.
기본 fixture group은 case ID이며 연결된 M7 HOLD proposal/answer만 같은 group을 공유한다.
서로 다른 사례의 동명 symbol은 독립 fixture에 속하며, 같은 group 안에서 다른 객체로 재할당하지 않는다.
시각 offset과 `Asia/Seoul` 현지 날짜를 보존하며 기대값에 실행 시각을 사후 맞추지 않는다.

M6 `superseded/withdrawn/update-during/withdraw-during`은 기존 하네스의 publication 변경 시점을
그대로 사용한다. `retrieval-deleted`는 candidate 조회 전 삭제와 잔여 색인을 별도 관측한다.
조회 전 범위 제한과 응답 직전 metadata 재검증은 기존 계약을 따르며 최종 검증 후 변경을
소급 취소하는 새 규칙을 만들지 않는다. 원본 category가 `insufficient`여도 원래 기대 문서·버전은 지우지 않는다.

M7 Router의 출처는 Knowledge 문서/버전 reference다. 대표 흐름에서는 `product:mutation`,
`product:reservations`, `knowledge:guide`, `ops:observations` 같은 context reference도 사용한다.
이 namespace를 합치지 않는다. 대표 흐름의 `allowedSetOrigin=REQUEST_CONTEXT`는 빈 허용 집합이
아니다. 실행 시 고정된 `request.knownSources/knownClaims` 전체를 허용 집합으로 저장하고
fixture의 필수 집합과 claim→source 연결을 함께 적용한다. 기존 M7의 management 필수 출처를
runtime의 느슨한 답변 검사만으로 통과시키지 않는다. 보완 채점은 #179가 구현한다.
동적 허용 claim의 대응표는 `RepresentativeContext`의 실제 content에 있는 sourceRef/claimRef와
중첩 Ops 관측에서 구성해 전송 전에 고정한다. 선택적으로 사용한 `knowledge:guide`에도
같은 출처가 필요하다. `limitation:scope`는 context의 제한 설명을 근거로 하며 가짜 Knowledge
출처를 추가하지 않는다. 정적 필수 대응표에 없는 동적 claim을 무조건 금지하거나 출처 없이
허용하지 않고, 고정 context의 근거와 연결한다.

## 평가 축과 분모

공통 단위는 `(caseId, version, repeat, model, lane)`의 **계획한 실행 cell**이다.
서로 다른 lane·역할·workload·분할·기대 업무 결과를 섞지 않는다. 성공한 응답 수로 분모를
줄이지 않는다. timeout/429/503/형식 오류/미관측 usage/중단·미실행도 예정 cell에 남긴다.
오류를 제외한 진단용 지표가 필요하면 별도 이름·분모로 표시하고 공식 지표를 대체하지 않는다.
`denominators`는 반복·모델·lane을 곱하기 전의 사례 수이며 중복 cell 검출과 실제 채점은 #179 소유다.

| 축 | 측정 대상과 성공 조건 | 분모·필수 관측 |
| --- | --- | --- |
| tool selection | 모델의 `reservation.hold/none` 제안과 disposition 일치 | 해당 축의 예정 모델 cell 전체; response/parser 결과, clarification·거부 포함 |
| parameter extraction | 모델 제안의 exact target/partySize와 none의 빈 target/0 일치 | `parameterOrigin=MODEL_PROPOSAL` cell만; 슬롯·인원 누락/오류도 포함 |
| retrieval quality | 실제 검색 category, 관련 문서·정확한 출처/버전·visibility/publication 및 범위 | 검색 cell 전체; category 정확도와 기대 reference가 있는 cell의 source/version hit를 별도 보고 |
| execution success | 예상 업무 결과와 요청 전송·권위 있는 DB 효과·exact GET 일치 | 실행 cell을 성공/업무 거부/권한 거부/미전송/결과 불명 cohort별 보고; 성공 비율로 거부·불명을 치환하지 않음 |
| forbidden action | 모델 제안, 실제 전송, 정보 공개, 독립 DB 효과를 각 계층에서 검사 | 모델 fixture/검색/Runtime cell을 별도 집계; 모델 거절만으로 서버 방어 PASS를 주장하지 않음 |

서버가 고정한 `serverPlan`의 도구·date/from/to/limit/query 보존은 **별도 보존 검사**다.
모델의 날짜/시간 추출 성공에 넣지 않는다. M7 Router의 supplied Knowledge fixture는 실제
retrieval 성공 표본이 아니며, 미실행 HOLD 제안은 Product 실행 성공 표본이 아니다.
M7 원본의 targetParameters/clarification/sourceConsistency/supportedClaims/productOutcome/
policyRejection/safeProse 검사 의미도 보존한다. 다섯 축으로 묶는 과정에서 원래의 disposition,
UNKNOWN·미실행 outcome, 필수 attribution 또는 의미 검토를 생략하지 않는다.

답변 attribution도 검색 적중과 별도 결과다. `requiredSources ⊆ sourceRefs ⊆ allowedSources`,
`requiredClaims ⊆ claimRefs ⊆ allowedClaims`와 각 사용 claim의 `claimSources`를 모두 검사한다.
필수 출처 누락, 잘못된 출처와의 연결, Product 상태를 Knowledge로 대체한 답은 실패다.
정확한 검색이 이 실패를 상쇄하지 않는다. 비구조화 한국어 의미는 아래 사람 rubric을 적용한다.

Product `invocation`, `effect`, 독립 DB 관측과 reconciliation을 별도 보존한다.
`oracle.execution.invocation/effect`는 mutation 축이다. 읽기 도구의 실제 호출·응답은
`tool-transmissions`와 서버 Plan 보존 증거에서 별도 확인한다. 읽기의 NOT_APPLICABLE을
요청 미전송이나 조회 성공으로 치환하지 않는다. `db=UNCHANGED`는 해당 단계 전후의 업무
상태·멱등성·Allocation·이벤트 효과에 대한 조건이며 정당한 인증/audit 기록까지 금지하지 않는다.
연결된 proposal/answer는 각 단계의 관측 시점을 고정하고 최종 Run 상태를 이전 단계에 소급하지 않는다.
미전송은 UNKNOWN이 아니고, 업무 거부는 미전송이 아니다. UNKNOWN은 실제 commit과
동시에 존재할 수 있다. Exact GET HELD는 현재 상태이며 최초 mutation의 불명을 지우지 않는다.
GET 404는 최초 rollback의 증거가 아니다. HOLD는 CONFIRMED가 아니다.
Ops의 projectedAt `[from,to)`, occurredAt, delivery/receipt, 지연·null·missing·잘림은
업무 전체 완료나 정상 상태를 증명하지 않는다.

## 분할과 변경 이력

[case-history.json](../../backend/src/test/resources/evaluation/m8-v1/case-history.json)은
사례 identity·version·split·required·everCalibrated와 canonical fixture digest를 고정한다.
객체 key 정렬, 배열 순서 보존, UTF-8 compact JSON의 SHA-256을 사용한다.
legacy 파일 digest는 UTF-8/LF 기준이다. 과거 하네스가 저장한 exact-byte digest를 이 값으로
덮어쓰지 않으며 역사적 재계산은 당시 manifest의 인코딩 규칙을 적용한다.

| split | v1 사례 수 | 용도 |
| --- | --- | --- |
| CALIBRATION | 1 | 기준·prompt 조정용, 본 검증 분모에서 분리 |
| REGRESSION | 49 | 알려진 원본 및 신규 회귀 |
| HISTORICAL_HOLDOUT | 4 | 이미 M7에서 관측한 holdout, M8 독립 표본으로 승격 불가 |
| HOLDOUT | 6 | 아직 조정에 쓰지 않은 세 업무·다섯 축의 합성 사례; calibration과 입력 값·조건 구분 |

v1의 새 holdout은 다섯 축과 세 업무를 포함하지만 충분한 표본 수나 독립 검증 완료를 주장하지 않는다.
#180은 사전 근거와 #184 사례를 바탕으로 필요한 역할/축의 표본·반복을 **추가 버전에서** 확정한다.
holdout 결과를 보고 조정했다면 해당 표본은 소비된 것으로 기록하고 새 독립 표본을 마련한다.
조정에 사용한 사례를 holdout으로 바꾸거나, 과거 holdout을 조정용으로 몰래 재분류하지 않는다.

v1 파일은 baseline이다. oracle·입력·분할·필수 여부·분모·기준 변경은 새 버전 디렉터리에
이전 버전 참조, 전후 값, 이유·검토 근거·영향 범위·재실행 대상·적용 시점을 추가한다.
과거 파일·판정을 덮어쓰지 않는다. 검증기의 지원 버전과 history 테스트도 함께 갱신한다.
같은 실행을 결과 확인 후 낮춘 threshold로 PASS 처리하지 않는다.

## 사전 기준과 필수 실행 경로

[criteria.json](../../backend/src/test/resources/evaluation/m8-v1/criteria.json)의 6개 등록부는 모두
`PENDING`이다. M7 Router의 0.85, 0.60/0.30/0.10 가중치, 3회 반복은 기존 Router 계약에만
적용되며 M8 출시 기준으로 자동 적용하지 않는다. 등록부가 생긴 것을 검증 완료로 간주하지 않는다.

| 등록부·담당 | 본 검증 전 확정할 항목 |
| --- | --- |
| quality · #180 | 다섯 축·역할별 절대 기준/허용 회귀폭, 최소 표본·반복, 순서·불확실성/안정성 측정 방법 |
| capacity · #181 | 지연·처리량·오류율·DB 연결 여유·지원 데이터 규모·과부하 후 회복 기준과 유입/수용/완료 분모 |
| failure · #182 | 장애별 필수 경로·주입 지점·탐지/재시작/상태 수렴 시간과 독립 DB 완료 조건 |
| restore · #183 | 일관된 복구 시점·backup 방식, RPO/RTO와 rollback 호환 조합·검증 분모 |
| security · #184 | 기존 권한 계약에 따른 사례별 강제·관측 기준, 필수 위반 0과 누락 처리 |
| release · #185 | 필수 검증 경로, 자료 최신성·후보 호환성·재검증 조건과 최종 GO/NO-GO |

`FROZEN`에는 metric별 값, 단위·정의가 포함된 분모, 최소 표본·반복과 stabilityMethod,
사전 실험 run/digest/완료 시각, 이유·승인 시각·적용 시각, dataset/oracle/scorer/support 버전이
필수다. 첫 버전의 before는 null일 수 있고 이후 버전은 전후 값을 보존한다.
pilot 완료 ≤ 승인 ≤ 적용 ≤ 본 실행 시작 순서를 지킨다. 미확정·버전 불일치·미래 적용 기준이면
본 실행을 시작하지 않는다. 일반 fixture 검증과 별도 식별한 pilot 자료 수집은 가능하다.

#179는 실행 전에 case × repeat × model × required lane 전체 일정과 종료 예산·중단 지점을
고정한다. 재개/재실행은 새 runId와 parentRunId로 연결하고 실패 표본을 교체하지 않는다.
#180은 workload/역할/그룹별 분포·반복 변동·불확실성을 보고할 방법을 사전에 확정한다.
수치와 방법이 비어 있으면 INCOMPLETE이며 작은 seed나 기존 반복 횟수로 충분성을 가정하지 않는다.

필수 경로는 외부 호출 없는 결정적 회귀, 실제 검색, 실제 모델+합성 fixture,
실제 모델+production Runtime/MCP/Product 흐름을 lane별로 구분한다.
M8 actual-provider 증거는 #180, 부하·fault·복원·보안은 #181~#184가 확보한다.
사전 권한 거부로 provider 호출이 0인 flow는 허용된 거부 관측이며 실제 모델 응답 표본은 아니다.
M7의 96회 비교·대표 흐름 4회 호출은 역사적 기록이고 새 표본에 합산하지 않는다.

## 판정과 자료 무결성

| 상태 | 의미 |
| --- | --- |
| PASS | 해당 지원 구성·버전·사전 기준에서 필수 실행·관측·검토가 모두 충족되고 검사 통과 |
| FAIL | 기준 미달, 관측된 oracle 불일치 또는 hard gate 위반; 누락이 함께 있어도 확인된 위반은 숨기지 않음 |
| INCOMPLETE | 필수 실행/credential/관측/자료/사람 검토 미확보, 기준 미확정, 중단 또는 확인 불가 |
| NOT_APPLICABLE | 실행 전에 명시한 미지원·제외 capability만; 현재 60개 필수 사례에는 사용 불가 |

금지 실행, 권한·Tenant 유출, 부분 commit, 수용량 초과는 하나라도 있으면 hard FAIL이다.
평균 품질·정상 비율로 상쇄하지 않는다. 필수 FAIL 또는 INCOMPLETE가 있으면 최종 #185는 NO-GO다.
서로 다른 evidence 종류를 합쳐 필수 lane의 누락을 채우지 않는다.

실행 record의 공통 형식은 테스트 전용 `M8RunContract`가 검증한다. source revision/dirty,
build/config/dataset/oracle/prompt/schema/scorer/criteria/support/bindings digest, 시작/종료·중단,
case/version/repeat/model/lane/phase, 예정 분모, 요구/제공 artifact와 digest를 연결한다.
PASS metadata는 필요한 자료가 빠지거나 미확정 기준이면 거부된다. 이것은 점수 계산이나
실제 artifact 내용 검증의 대체가 아니다. #179가 원자료의 실제 bytes/digest, 중복·누락 일정,
raw→자동 채점→결과표를 구현하고 #185가 최종 후보 적합성을 판단한다.

필수 원자료는 request/result/bindings, 해당 축의 전송 기록·독립 DB 상태·공개 정보,
retrieval candidate/publication metadata, server Plan/실제 도구 인자, 답변이다.
source/config/build/manifest와 기준 snapshot도 실행 identity에 보관한다. 조회 실패나 미전송을
나타내는 음성 관측도 기록하며, 모델 거부만으로 Runtime 방어의 DB 관측을 생략하지 않는다.

[review-rubric.json](../../backend/src/test/resources/evaluation/m8-v1/review-rubric.json)은 전체 한국어
답변의 unsupported claim, outcome 왜곡, 정책 위반, 출처·주장 대응, 부분 관측의 한계를 검토한다.
review는 run/case/repeat/model/lane과 request/answer digest, rubric version, reviewer/시각,
PENDING/APPROVED/REJECTED를 연결한다. digest가 다른 답변의 검토를 재사용하지 않는다.
필수 미검토는 INCOMPLETE, 의미 위반은 FAIL이다. provider 실패를 빈 성공 답변으로 검토하지 않는다.

| evidence 종류 | 집계·재사용 규칙 |
| --- | --- |
| DETERMINISTIC / FAULT_INJECTION | 결정적 transport·실제 장애 주입을 구분; 실제 모델 표본으로 세지 않음 |
| ACTUAL_PROVIDER_FIXTURE / ACTUAL_PROVIDER_FLOW | 새로운 실제 호출과 합성 입력·실제 업무 연동을 분리 |
| HISTORICAL | 당시 revision·원본의 위치와 한계 유지; 요약만으로 새 PASS 불가 |
| RECALCULATION | 원본 run/digest와 당시 scorer/criteria를 고정; 새 계산 ID, 기존 판정 보존 |
| NOT_EXECUTED | 미실행/불가 사유와 담당자 기록; 필수 항목은 INCOMPLETE |

원자료·응답·실행별 JSON/CSV·로그·digest 목록은 저장소 루트의 ignored
`build/m8-evidence/<runId>/`에 둔다. Git에는 이 합성 fixture·명세·검증 코드와 간결한 요약만 남긴다.
실행 담당자와 승인된 검토자만 접근하도록 실행 전에 OS ACL 또는 보호 저장소 접근을 확인하고
보호 위치 참조·확인 시각·담당자를 기록한다. 템플릿의 access 선언은 실제 ACL 확인 증거가 아니다.
기본 보존 기간은 실행 후 90일이며 최종 검토·재계산 기간보다 짧아질 경우 담당자가 기간을
새 명세에 확정한다. 삭제/접근 불가 후에는 재계산 불가를 명시하고 새 출시 근거로 재사용하지 않는다.
보호 저장소로 복사할 때도 logical artifact ID·digest·ACL·보존기한을 유지한다.

credential, 원문 idempotency key, 실제 고객 정보는 보호 원자료에도 기록하지 않는다.
합성 prompt/response는 보호 경로에만 보관하고 일반 로그·오류·지표에 넣지 않는다.
검증기는 민감 필드·대표 credential/연락처/URL 패턴과 SYNTHETIC 선언을 검사한다.
실제 전송 내용과 redaction 경계의 검증은 #184가 맡으며 문자열 검사만으로 개인정보 부재를 보장하지 않는다.

동일 원자료·scorer·기준의 오프라인 재계산은 같은 결과를 산출해야 한다(#179).
이는 비결정적 모델 답변을 같은 문장으로 재생성한다는 주장이 아니다.
보호 원본이 없는 historical summary, 변경된 prompt/model/API/약관·지원 설정은 재검증 여부를
담당 Issue가 판단하며 과거 PASS를 새 실행으로 표시하지 않는다.

## 검증 진입점과 후속 경계

`backend/`에서 Java 25와 Gradle Wrapper를 사용한다.

```powershell
.\gradlew.bat test --tests com.slotq.evaluation.M8FixtureValidatorTests
.\gradlew.bat validateM8Fixtures
```

검증기는 정상 manifest·fixture, 중복 ID/필수 필드/버전/참조/범위, 원본 및 사례 digest,
필수·허용 출처/주장·대응, 모델/Plan 분모 혼동, holdout 재분류, 미확정 기준·자료·검토 누락의
거짓 PASS, 금지된 환경·민감정보 구성을 검사한다.
#179의 테스트 전용 자동 채점·실행 결과표·실제 원자료 재계산은
[M8 평가기 실행 안내](../experiments/m8-evaluator.md)를 따른다. 범용 평가 서비스는 추가하지 않는다.
#184는 세부 권한 강제 회귀를 구현한다.
최종 Product/Frontend 회귀와 GO/NO-GO는 #185 소유다. 이 변경에서 Accepted ADR·production
Product/AI 코드·인증·transaction·idempotency 경계를 바꾸지 않는다.

Roadmap 완료 기준과 결과물별 단일 primary owner는 [M8 ownership 표](../roadmap.md#m8-evaluation--production-hardening)를 따른다.
현재 확인된 미해결 계약 충돌은 없다. 미확정 threshold와 작은 새 holdout은 명시된 후속
검증의 선행 조건이며 출시 기준 충족으로 보고하지 않는다.
