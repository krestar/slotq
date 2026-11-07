# #193 oracle v2 상세 변경안

상태: **`issue193-oracle-v2-draft-1` / DRAFT / 미적용**.
사용자는 2026-11-07에 정정 방향과 상세안 작성을 승인했다.
아래 상세안의 최종 승인은 아직 없으며, 코드·실행 resource 변경과 신규 Gemini 호출은 하지 않았다.
[필드별 before/after JSON](m8-issue-193-oracle-v2-proposal.json)이 이 문서의 구체적인 변경 대상이다.
해당 JSON은 `docs/experiments/`의 검토 자료이며 수집기·검증기에서 읽는 실행 설정이 아니다.

## 정정 근거와 범위

[재개 검증](m8-issue-193-resumption.md)에서 보존한 과거 Runtime 4개 cell과
현재 `RepresentativeContext.prepare`, `M8Bindings.request/verifiesRequest`,
`M8Scorer.attribution/execution`을 확인했다.
실제 요청은 Product reference를 제공하고 인용을 요구하지만, 두 사례의 v1 oracle은 STATIC 빈 집합이다.
또한 `unknown-committed`는 정확한 GET으로 현재 HELD를 관측해도 `INSUFFICIENT`를 요구한다.
이 두 충돌을 정정하며 응답을 본 뒤 허용 reference를 늘리지 않는다.

두 사례는 모두 `REGRESSION`, `required=true`, `humanReview=true`이며
lane은 `DETERMINISTIC`, `ACTUAL_PROVIDER_FLOW`뿐이다. `ACTUAL_PROVIDER_FIXTURE`는 없다.
따라서 lane별 override 스키마를 새로 만들지 않는다. 동일 Runtime을 재현하는 결정적 회귀에도
같은 정정 계약을 적용한다. fixture의 정적 oracle은 변경하지 않는다.

## 버전과 파일

| 항목 | 적용 전 | 승인 후 적용할 값 |
| --- | --- | --- |
| 실행 resource 경로 | `evaluation/m8-v1/` | 새 `evaluation/m8-v2/`; v1 파일 불변 |
| dataset / oracle | `1.0.0` / `1.0.0` | `2.0.0` / `2.0.0` |
| 두 대상 사례 version | `1.0.0` | `2.0.0`; ID 유지 |
| 나머지 58사례 | v1 값 | 사례 version·입력·fixture·oracle·분할·필수 여부 모두 동일 |
| scorer 식별자 | `m8-scorer-v1` | v2 선택 실행은 `m8-scorer-v2` 및 새 source/build/scorer digest |
| criteria | `1.0.0`, quality/security `PENDING` | 이 정정에서는 동일한 PENDING 원본 유지. FROZEN 승인과 별개 |
| 의미 검토 rubric | `m8-answer-v1`, 다섯 항목 | 동일 rubric. 아래 사례별 검토 의무를 정정 이력에 고정 |
| 실행 record 형식·모델·API·지원 구성 | 현재 계약 | 변경 없음 |

새 디렉터리의 `dataset.json`·`case-history.json`과 실행 manifest에 위 identity를 반영한다.
검증 진입점에 필요한 criteria·rubric 사본은 v1과 같은 bytes를 유지하며 새 기준으로 주장하지 않는다.
새 `oracle-change.json`에는 이 상세안의 before/after, 이유·근거, 영향·재실행 범위,
실제 승인 참조·승인/적용 시각 및 사례별 검토 의무를 기록한다. 현재 승인 시각은 null이다.
그 파일도 실행 snapshot과 비교 identity에 포함한다.

`case-history.json`은 v1의 최초 변경 이력을 보존하고 `previous=1.0.0`인 `2.0.0` 변경을 추가한다.
두 대상 entry의 version과 canonical 사례 SHA-256만 정정값에 맞춰 갱신하고,
관측을 검토해 oracle을 정정한 사실을 숨기지 않도록 두 entry의 `everCalibrated`를 `false → true`로 기록한다.
분할은 계속 REGRESSION이다. 나머지 entry 및 HOLDOUT의 입력·분할·이력은 바꾸지 않는다.
v1 과거 history·runId·seal·digest·FAIL은 수정하지 않는다.

## 정확한 oracle 변경

### `m8/customer-business-rejected`

proposal은 `ANSWER / none / 빈 target / partySize=0 / REJECTED`를 유지한다.
attribution만 다음과 같이 정정한다.

| 필드 | before | after |
| --- | --- | --- |
| `allowedSetOrigin` | `STATIC` | `REQUEST_CONTEXT` |
| `requiredSources` / `requiredClaims` | 각각 `[]` | 각각 `["product:mutation"]` |
| `allowedSources` / `allowedClaims` | 각각 `[]` | 저장값은 각각 `[]`; 실제 허용 집합은 전송 전 request에서 고정 |
| `claimSources` | `{}` | `{"product:mutation":["product:mutation"]}` |

Product의 실제 업무 거부를 Product 출처로 설명해야 한다.
Knowledge 안내는 선택적으로 쓸 수 있지만, 실제 request가 허용하고 근거·대응이 있어야 한다.
안내문으로 Product 거부 사실을 대체하거나 HOLD 성공·확정을 주장하면 실패다.

### `m8/unknown-committed`

| 필드 | before | after |
| --- | --- | --- |
| `proposal.disposition` | `INSUFFICIENT` | 아래 정확한 GET 조건을 충족할 때 `ANSWER` |
| `proposal.outcome` | `UNKNOWN` | `UNKNOWN` 유지 |
| `proposal.tool/target/partySize` | `none / 빈 문자열 / 0` | 동일 |
| `allowedSetOrigin` | `STATIC` | `REQUEST_CONTEXT` |
| `requiredSources` / `requiredClaims` | 각각 `[]` | 각각 `["product:mutation","product:current"]` |
| `allowedSources` / `allowedClaims` | 각각 `[]` | 저장값은 각각 `[]`; 실제 허용 집합은 전송 전 request에서 고정 |
| `claimSources` | `{}` | 두 Product claim을 각각 같은 이름의 Product source에 연결 |

변경하지 않는 실행 기대값은 `invocation=UNKNOWN`, `effect=UNKNOWN`, `db=HOLD_ATOMIC`,
`reconciliation=EXACT_GET_HELD`다. `forbidden` 네 항목은 모두 false를 유지한다.
권한·정확한 승인·멱등성·독립 DB의 원자적 효과와 다른 업무 상태 불변을 계속 별도로 검사한다.

`ANSWER` 조건은 모델 응답을 보기 전에 존재한 다음 근거로 확인한다.

1. 최초 mutation의 invocation/effect가 UNKNOWN이다.
2. 실제 exact GET이 전송됐고, target이 해당 fixture의 bound reservation 및 `knownTarget`과 정확히 같다.
3. 실제 GET 응답은 HTTP 200이며 현재 state는 HELD다.
4. 최종 provider 호출의 고정 request context에도 최초 mutation UNKNOWN과 현재 exact GET HELD가 들어 있다.
5. 필수 Product reference와 claim→source 대응이 해당 request context에서 도출된다.

GET 근거가 없으면 `ANSWER` 조건을 적용해 통과시키지 않는다.
관측 누락은 해당 조건과 disposition 검사에 INCOMPLETE로 남긴다.
다른 target·404·비HELD 등 실제 모순이 관측되면 조건 검사는 FAIL이다.
기대 execution의 불일치도 별도 FAIL이며, 실제 답변에 맞춰 기대 disposition을
`INSUFFICIENT`로 바꾸는 fallback은 만들지 않는다.
`tool=none`과 `outcome=UNKNOWN`, 금지 실행·공개·DB 검사는 조건 미충족 때도 계속 검사한다.
이미 확인된 위반은 누락 때문에 INCOMPLETE로 낮추지 않는다.

### 고정 request와 출처 검사

`REQUEST_CONTEXT`는 무제한 허용이 아니다. 기존 `M8Bindings.request`가 실제
`RepresentativeContext`의 내용에서 추출한 `knownSources`, `knownClaims`, `claimSources`를 재사용한다.
최종 provider에 전달된 request와 기록한 request가 같아야 하며, 기존 `verifiesRequest`의
Actor·Tenant·Venue·Plan·context·mapping 검사를 유지한다.
허용 집합과 대응표를 답변 reference에서 역으로 구성하거나 사후 확장하지 않는다.

필수 집합 ⊆ 답변 집합 ⊆ 고정 request 허용 집합과 모든 사용 claim의 근거 대응을 검사한다.
`product:mutation`과 `product:current`는 서로 바꿀 수 없다. `knowledge:guide`를 선택했다면
같은 Knowledge 출처가 필요하다. `limitation:scope`는 실제 제한 context에서만 인정하며
그 claim의 빈 source 대응을 Product claim의 근거 생략에 일반화하지 않는다.
request 밖 출처·주장, 누락된 필수 Product reference, 근거 없는 claim, 변조한 대응표는 계속 FAIL이다.
필수 request/근거 자체를 확보하지 못한 경우는 관측 부족으로 보고하며 성공 분자로 세지 않는다.

## 자연어 검토

구조화된 claim/source 검사만으로 자연어의 정확성을 승인하지 않는다.
기존 rubric 다섯 항목으로 전체 답변을 검토하며 특히 다음을 확인한다.

- 업무 거부: 실제 거부 결과를 설명하고 승인·임시 확보·예약 확정을 만들어 내지 않는다.
- UNKNOWN: 최초 mutation이 결과 불명이라는 사실과 정확한 GET에서 현재 HELD라는 사실을 모두 설명한다.
  현재 HELD를 최초 mutation 성공으로 소급하거나 CONFIRMED로 표현하지 않는다.
- 두 Product 사실의 출처와 Knowledge 안내를 구분하며 GET 404를 롤백 증거로 해석하지 않는다.

키워드 포함 여부나 Codex 보조 초안을 사람 APPROVED로 바꾸지 않는다.
request/answer digest·runId·case version·model·repeat·lane·rubric·검토자·시각을 연결한다.
필수 Product claim은 있어도 자연어에서 최초 UNKNOWN을 생략했다면 사람 검토에서 REJECTED다.

## 예상 코드 영향 — 아직 구현하지 않음

| 위치 | 최소 변경과 제한 |
| --- | --- |
| `M8FixtureValidator` | v1/v2 고정 resource 선택과 각 dataset/oracle/사례/history 버전 검증. 현재 공용 `version()`은 모든 값을 `1.0.0`으로 제한하므로 이를 임의 SemVer 허용으로 완화하지 않고 대상별 허용 조합을 검사한다. v1 불변·58사례 불변·두 사례의 허용 필드 diff를 검증한다. |
| `M8Evaluation`, `M8Bindings` | 선택한 버전으로 사례를 읽고 manifest·case version·봉인 dataset과 일치시킨다. 동일 run에서 v1/v2 혼용·알 수 없는 버전·live dataset/snapshot 불일치를 거부한다. 정정 이력 snapshot을 추가한다. |
| `M8EnvironmentContract`, `M8RunContract` | 선택한 dataset/oracle/case version과 manifest/record의 일치 검증에 v2의 정확한 조합만 추가한다. 실행 record 형식의 `1.0.0` 및 기존 criteria 버전 검증을 포괄적으로 완화하지 않는다. |
| `M8Scorer` | 기존 REQUEST_CONTEXT attribution 재사용. v2 `unknown-committed`에서 위 관측 조건의 PASS/FAIL/INCOMPLETE를 disposition 비교 전에 분리한다. 기존 execution·권한·DB·hard gate 검사를 생략하지 않는다. |
| `M8ModelQualification`, Gradle task 전달 | 신규 수집·재계산 JVM에 동일한 고정 dataset 버전을 전달하고 frozen/comparison identity에 정정 이력 digest를 포함한다. provider endpoint·모델·Router 선택·retry 정책은 그대로다. |
| 기존 테스트와 새 회귀 | v1 기대값은 보존한다. v2 조건·거부·누락과 별도 JVM 재계산을 검증한다. production Runtime/Router/API/DB·migration은 수정하지 않는다. |

선택 누락 시 기존 v1 동작을 유지하고, 신규 v2 실행은 명시적 선택과 전체 identity 일치를 요구한다.
수집기/평가기의 다른 설계 변경이나 unrelated 정리는 포함하지 않는다.
실제 파일 변경 목록은 승인 후 구현 diff에서 다시 확인한다.

## 필요한 회귀와 검증 계획

아래는 **앞으로 추가·실행할 검증**이며 이번 문서 작업에서 통과했다고 주장하지 않는다.

| 검증 영역 | 필수 정상·실패·누락 사례 |
| --- | --- |
| 버전·history | 원본 v1 bytes/digest 불변, v2 변경 대상 정확히 2개, 나머지 58개 불변, 60개 분모·분할 유지, 잘못된 버전·digest·history·정정 이력 변조 거부 |
| request attribution | 두 Product 출처의 정상 대응, 밖의 source/claim, 필수 claim 생략, mutation/current 교환, Knowledge 대체, mapping 사후 확장, 실제 전송 request와 기록 request 불일치, request/대응 관측 누락 |
| UNKNOWN 조건 | 실제 exact GET HELD의 ANSWER+UNKNOWN, 누락·미전송·다른 UUID·404·다른 state·context 불일치. 누락 중에도 잘못된 tool/outcome과 hard gate FAIL 보존 |
| 상태·보안 | 승인 불일치·부분 commit·중복 효과·다른 업무 상태 변경 거부, GET 404와 rollback 분리, UNKNOWN 뒤 독립 DB 원자적 commit 보존, no-target·not-sent·다른 UNKNOWN 사례 v1 계약 불변 |
| 의미 검토 | 초기 UNKNOWN 생략·확정 표현·출처 왜곡 REJECTED, 검토 대기 INCOMPLETE, 실제 검토자·digest 불일치 거부. 자동 초안은 APPROVED 불가 |
| 연결·무결성 | 관련 두 사례의 결정적 Runtime 연결, 기존 필수 18사례 회귀, 새 scorer JVM 간 identity 일치, 봉인·원자료 변조/추가/누락 검출, 동일 identity 재계산 보고서 일치 |

승인 후 코드 수정에는 관련 회귀·fixture 검증·Runtime/보안 연결 검증을 우선하고,
PR 갱신 전에 `backend/`의 전체 `test`와 `clean build`를 수행한다.
이번에는 문서와 검토용 JSON만 변경했으므로 의미 없는 build는 생략한다.

## 비교 가능성과 재실행

기존 66회와 과거 pilot은 당시 source/build/scorer/dataset/oracle로만 보존·재계산한다.
v2로 과거 답변을 채점해 같은 run의 PASS로 대체하지 않는다.
특히 새 필수 `product:mutation` claim이나 자연어 UNKNOWN 의무는 실제로 실패할 수 있으며 숨기지 않는다.

dataset·oracle·case/history·scorer·정정 이력 identity가 바뀌므로 과거 자료는 비교 가능한
새 baseline이나 새 scorer의 성공 표본이 아니다. 58사례가 같아도 build/scorer가 다르므로
직접 비교 가능한 공식 baseline이라고 인정하지 않는다. 과거와의 차이는 진단으로만 별도 보고한다.

승인 후 최소 구현·최종 Backend 검증·커밋을 마친 격리된 작업 공간에서 새 PILOT과 독립 baseline을 수집한다.
fixture/Runtime은 별도 run·binding·lineage와 해당 lane의 동일 identity로 비교한다.
PILOT과 baseline에 HOLDOUT을 쓰지 않는다. 실제 관측과 사람 검토를 바탕으로 quality/security 기준을
별도 승인해 FROZEN으로 고정한 뒤 독립 QUALIFICATION을 계획한다.
HOLDOUT의 oracle을 이번 두 사례에 맞춰 바꾸거나 알려진 실패를 조정용으로 열어 보지 않는다.
후속 본 검증에서 다른 계약 충돌이 나오면 FAIL/INCOMPLETE를 보존하고 별도 blocker로 보고한다.

현재 #193은 계속 INCOMPLETE다. 이 상세안 승인은 기준 FROZEN·출시 GO·사람 검토 승인과 다르다.
실제 호출 계획과 환경 확인은 [재개 기록](m8-issue-193-resumption.md)을 따른다.

## 이번 상세안의 문서 검증

검토용 JSON을 읽어 두 대상의 before oracle/history가 현재 v1과 같고,
before/after의 canonical 사례 SHA-256이 각 history entry와 일치함을 확인했다.
변경 후 사례는 메모리 안에서만 구성했다. 변경 필드는 두 사례의 version/oracle과 명시한 history 정정뿐이며,
나머지 58사례·60개 분모·execution/forbidden 기대값, v1 파일의 bytes와 기존 8개 run의 보고서는 불변이다.
실행용 `evaluation/m8-v2/`는 없고 새 API 호출도 0회다.

검증 자료는 ignored 경로
`build/m8-evidence/issue193-oracle-proposal-8a3f3516-b6c1-42bb-9da0-39e77acd8cd3/`의
`proposal-validation.json`과 `user-preflight-attestation.json`이다.
이는 상세안의 내부 일관성 검증이며 승인 후의 구현·회귀·Backend 검증을 대신하지 않는다.
