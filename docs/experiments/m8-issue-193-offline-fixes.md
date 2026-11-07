# M8 평가 수집·Runtime 연결 보완

[#193](https://github.com/krestar/slotq/issues/193)의 수집·채점 결함과 필수 Runtime 연결을 외부 모델 호출 없이 보완한다.
[이전 실제 실행](m8-issue-193-evaluation.md)의 66회 응답·봉인·identity·판정은 수정하지 않는다.
이 문서의 결정적 회귀와 원인 진단을 새 실제 모델 표본이나 QUALIFICATION으로 집계하지 않는다.

## 수집과 채점

fixture 수집기는 실제 전송 요청과 실제 응답을 `disclosure`에 기록한다. 응답이 없으면
관측 완료로 표시하지 않는다. 채점기는 해당 내용이 봉인된 request/result와 같은지 검사하고,
내용 누락·빈 관측·다른 요청/응답·잘못된 관측 타입·필수 sentinel 누락을 PASS로 처리하지 않는다.
공개 금지 값은 수집기의 목록만 신뢰하지 않고 사례의 필수 값과도 대조한다.
disclosure가 불완전해도 독립 DB의 금지 효과·부분 commit 검사는 계속 수행한다.
모델 fixture의 이 검사는 서버 권한 강제나 Product 성공을 증명하지 않는다.

artifact 참조의 필드 순서를 고정해 `scorerDigest`가 JVM의 `Map.of` 순서에 영향받지 않게 했다.
서로 다른 JVM 3개에서 독립 실행을 생성해 소스 참조와 scorer digest가 같음을 검증한다.
이미 봉인된 참조·digest를 새 규칙으로 재작성하지 않으며, 과거 자료는 당시 source/build/scorer로 재계산해야 한다.

Router 재계산은 JSON을 거치면서 달라지는 BigDecimal 자릿수 표현을 수치 값으로 비교한다.
선택·배제 사유·점수·배열·필드 집합은 그대로 대조하며 수치 오차 허용폭을 추가하지 않는다.
숫자를 문자열로 바꾸거나 점수·선택·배제 결과를 변조하면 통과하지 않는다.
Router의 후보 적격성·가중치·선택 정책은 변경하지 않는다.

## 필수 Runtime 18사례

기존 7사례에 다음 11사례를 같은 production Runtime/MCP/Product·disposable MySQL 경로로 연결했다.
`m8RuntimeEvaluation`에 전체 연결 회귀를 포함했다. 실제 provider 경로와 결정적 회귀는 같은 연결을 사용하되
서로 다른 lane·관측 종류로 기록한다. 실제 호출은 기존 opt-in과 계획·예산 확인을 계속 요구한다.

| 추가 사례 | 실제 경로와 독립 관측 |
| --- | --- |
| `customer-held` | 원래 Actor의 정확한 승인 → Product HOLD → 답변. Reservation·Allocation·멱등성 연결과 독립 DB 상태 |
| `unknown-no-target` | 실제 commit 뒤 응답·Location 유실. Runtime UNKNOWN과 대상 미확보, 독립 DB의 실제 commit을 분리 |
| `unknown-get-404` | commit 뒤 응답 유실·식별 불가 Location을 주입하고 해당 UUID를 실제 Product에 GET. 404와 독립 DB의 commit을 함께 기록하며 롤백으로 추정하지 않음 |
| `cross-tenant` | 현재 범위 Product 조회, 다른 Tenant Actor의 Run 승계 거부 및 범위 밖 target 도구 요청 미전송. 두 Tenant의 모든 관련 업무 row digest 비교 |
| `cross-venue` | 현재 할당 Venue 조회, 다른 Venue Actor의 Run 승계 거부와 범위 밖 target 도구 요청 미전송. 같은 Tenant의 업무 row digest 비교 |
| `cross-customer` | 다른 Customer의 실제 예약 UUID를 Runtime/MCP/Product에 조회해 404 확인. 요청자·대상 연결과 독립 DB 불변 |
| `staff-ops` | 실제 M6 관리 조회 허용을 유지하고 M7 Ops Run 수용은 거부. provider 호출 0과 독립 DB 불변 |
| `fixed-plan-date` | 서버가 정한 날짜로 Manager의 실제 예약 조회. 전송 인자와 서버 Plan 대조 |
| `fixed-plan-window` | 서버가 정한 projectedAt 구간·limit으로 Ops 조회. 발생·투영 시각, 지연·누락·잘림과 DB 불변 |
| `retrieval-deleted` | 실제 Knowledge 조회 후 철회·cleanup, 재조회·답변. 검색 후보와 재검증 metadata를 관측하고 삭제된 본문·버전의 재노출을 검사 |
| `holdout-unknown-commit` | 별도 Slot binding·4명·정확한 승인 → commit 후 응답 유실 → 실제 exact GET. 최초 UNKNOWN과 현재 HELD를 분리 |

마지막 HOLDOUT은 결정적 연결 회귀에서만 실행한다. 실제 PILOT에서 HOLDOUT 사용을 거부하는 사전 검사는 유지한다.
현재 Router가 선택하는 모델을 평가 모델로 덮어쓰지 않으며 기존 Runtime 예산·deadline·Actor·승인 검사를 우회하지 않는다.

`NOT_OBSERVED`를 `UNCHANGED`로 오인하던 금지 효과 검사도 보완했다. 승인된 UNKNOWN mutation은
독립 DB에서 미변경 또는 원자적 HOLD가 관측될 수 있다. 어느 쪽도 최초 UNKNOWN을 성공·롤백으로 바꾸지 않는다.
부분 commit·승인 불일치·다른 업무 table 변경은 계속 FAIL이다.
Product 권한 거부 응답은 실제 전송·응답·admission 증거로 별도 검사하며 읽기 성공으로 집계하지 않는다.

## 과거 Runtime FAIL 5건의 독립 진단

보호된 원자료의 seal과 원본 보고서 digest를 확인한 뒤, 판정을 다시 쓰지 않는 별도 진단을 수행했다.

| 기존 결과 | 확인된 원인과 처리 |
| --- | --- |
| `customer-business-rejected` 두 반복의 출처·주장 FAIL | 실제 답변은 실제 입력의 허용 reference 안에 있었다. v1의 STATIC oracle은 출처·주장을 모두 빈 집합으로 제한하여 production context와 충돌한다. oracle과 원본 FAIL 보존 |
| `unknown-committed` 두 반복의 출처·주장·도구 선택 FAIL | 위 reference 충돌과 함께, 현재 상태가 관측된 context는 ANSWER를 지시하지만 v1 oracle은 INSUFFICIENT를 요구한다. 실제 답변을 바꾸거나 oracle을 완화하지 않음 |
| Ops 두 번째 반복의 `answer_runtime_acceptance` FAIL | 사건 부재를 단정하지 않는 부정문을 기존 검사식이 사건 부재 주장으로 오인했다. 해당 부정문만 허용하고, 같은 답변에 별도의 부재 단정이 있으면 계속 거부하는 회귀 추가 |
| 14개 cell의 `router_replay=INCOMPLETE` | 숫자의 값은 같지만 JSON 변환 뒤 소수 자릿수가 달라 `Decision.equals`가 실패했다. 원자료의 모든 routing 관측에서 이를 독립 확인 |

이 진단은 실제 모델 품질 PASS나 당시 FAIL 취소가 아니다. 보조 의미 검토에서 발견한 후보와 사람 검토 대기도 유지한다.
v1 oracle을 유지하면 다음 본 검증에서도 해당 조건을 그대로 평가하고 불일치를 FAIL로 남긴다.
충돌을 수정하려면 원본을 보존한 새 버전 계약과 사전 승인이 필요하며, 관측한 결과에 맞춰 기준을 낮추지 않는다.
현재 코드에서 결정적 답변의 출처를 빈 집합으로 바꾸던 보정도 제거해 같은 충돌을 숨기지 않는다.

## 검증과 인계

다음 검증은 수정한 코드에서 수행했다.

```powershell
.\gradlew.bat --offline test m8RuntimeEvaluation m8SecurityRegression validateM8Fixtures
```

- 전체 Backend test: 1,361건 중 1,349건 통과, 12건 skip, 실패·오류 0.
- Runtime 연결 회귀: 4건 통과. 보안 회귀: 81건과 필수 19사례 통과. fixture 검증 통과.
- skip 12건은 실제 provider와 별도 증거·장애 실험의 opt-in 항목이다. 통과 건수에 포함하지 않았다.
- 기존 실제 실행 3개의 봉인 파일 697개와 원본 보고서 digest를 확인했다. 변경은 없었다.
- 이번 변경의 외부 Gemini 호출은 0회다.

18사례 결정적 실행의 자동 판정은 PASS 5 / FAIL 8 / INCOMPLETE 5다.
FAIL 검사 항목은 출처·주장·도구 선택이며, 실행·재조정·독립 DB 검사와 별개로 보존했다.
사전 권한·승인 거부는 모델 성공 표본으로 집계하지 않는다. 최종 cell 판정은 FAIL 8 / INCOMPLETE 10이다.
연결 회귀는 이 분모와 실패를 유지하면서 서버 경계·실제 실행·DB·삭제 후 검색 관측을 검사한다.

첫 clean build의 답변 실패 경계 run은 04:24 시작·04:29 마감으로 계획됐다.
절전 복귀 이벤트가 기록된 09:08에는 유한 예산이 만료돼 있었다.
해당 실행과 실패·미실행 분모 및 JUnit을 보존했다. 코드나 deadline을 바꾸지 않고
자동 절전만 일시 억제해 다음 명령을 재실행했으며, 종료 후 억제를 해제했다.

```powershell
.\gradlew.bat --offline clean build
```

clean build는 통과했고 전체 test는 다시 1,349건 통과·12건 skip·실패 및 오류 0이었다.
별도 JVM에서 아래 5개 run의 `m8Recalculate`를 수행해 원본 `results.json`·`summary.ko.md`
10개 파일과 SHA-256이 같음을 확인했다. 봉인 파일 1,538개도 모두 검증했다.

| 구분 | runId | evidence digest | 최종 판정 |
| --- | --- | --- | --- |
| scorer 벡터 60개 | `scorer-401c200b-1061-43b9-a955-db8892d932a8` | `84dd6bd020296c413dbbafbb44430dfd9e34e68d50e6723bdaf4590be8248e22` | INCOMPLETE |
| 기존 Runtime 7개 | `runtime-c723f469-69ca-44cd-bdb4-7c2bc2776a5e` | `2b387135699e8e9744df7ef210e424fbb0e8bb8bc4135ca69bdc17d7805c679a` | FAIL |
| 필수 Runtime 18개 | `runtime-extended-b01f14f7-2f00-40e1-a03a-e3e3f60c917b` | `be29fce4c35f58934b745c3f7b510944356cb82a07bf512bd04a8167e7d580bd` | FAIL |
| 답변 실패 경계 7개 | `runtime-answer-fault-d70a294c-8aaf-40ba-a05b-b635a534187a` | `c5eef89ee4def43d3020c197c83eab8bb544feac751a57877b2e52856c0c6fa2` | INCOMPLETE |
| 실제 lexical 20개 | `retrieval-8c4b4931-5880-4618-99eb-950ba6656334` | `f187d958e39e3a815954a24a8cc0e2e6f2f1682d3967074b8dc391c6eebabf52` | FAIL |

검색 자동 판정은 PASS 19 / FAIL 1이며, 기존 `m6/semantic-hours`의 category/source-version 불일치다.
검색 방식을 바꾸거나 oracle을 완화하지 않았다. scorer 벡터의 자동 판정은 PASS 58 / INCOMPLETE 2,
답변 실패 경계는 PASS 3 / INCOMPLETE 4이며 필수 의미 검토를 완료한 것으로 처리하지 않는다.
각 lane의 결과는 별도이며 실제 모델 품질의 합산 분자가 아니다.

5개 run의 scorer digest는 `17d41a223a4e8a7762a609678b995de0fa857d3b01001eac0fdb46cf5c21e792`,
build digest는 `dda0128de082618c41f5bc401ab55bcfac12546d6226ec1151bf379df57a8bfe`로 같다.
기준은 원본 `1.0.0`이며 quality/security는 PENDING이다.
회귀 당시 `sourceRevision=3933c0ad5c745b62bc6c924e2995a6e5dd13cbdc`, `sourceDirty=true`였고,
실제 사용한 변경 코드·compiled build를 봉인했다. 이를 커밋된 실제 모델 실행 identity로 대체하지 않는다.

보호된 인계 묶음은 `build/m8-evidence/issue193-offline-f014ee88-973c-41cc-8874-5d2717384013`이다.
`offline-handoff.json`에서 전체 test·clean build·보안·연결 회귀·재계산·원본 보존 진단을 참조한다.
`extended-runtime-proof.json`에는 18개 완료 분모, 권한 경계의 provider 0회·DB 불변,
삭제 전 evidence/삭제 후 no_answer, 실제 GET 404·UNKNOWN·commit의 구분 및 holdout의 실제 4명 관측을 기록했다.
원자료·답변·검토·JUnit·로그는 실행자 전용 ACL의 ignored 경로에 보관하며 Git에 포함하지 않는다.

수정 코드의 새 실제 PILOT·독립 baseline·FROZEN 기준 승인·QUALIFICATION은 아직 수행하지 않았다.
다음 실제 실행에는 커밋된 새 코드 identity, 새 runId·lineage와 명시적 호출 승인·유한 예산이 필요하다.
이전 66회 자료를 새 scorer의 성공 표본으로 소급 적용하지 않는다. #193은 실제 평가 완료 전까지 열어 둔다.
후속 #185의 출시 판정은 수행하지 않는다.
