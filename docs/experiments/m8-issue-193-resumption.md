# #193 재개 검증과 실제 호출 승인안

2026-11-07에 최신 `origin/main`의 `ab3d06e72c73944cc7cad97794bf813dfbd2e56d`
(PR #197 squash)에서 `chore/m8-gemini-evaluation`을 시작했다.
기존 `.gitignore` 변경은 보존하고 이번 변경에 포함하지 않는다.
이 기록은 신규 실제 모델 평가 결과가 아니다. **#193은 INCOMPLETE이며 종료하지 않는다.**

## 실행 전 재검증

PR #197의 실제 요청·응답 결합 disclosure, 결정적인 scorer identity, 숫자 값 기준 Router replay,
Runtime 18사례 연결이 현재 코드에 포함돼 있다. 새 실제 호출은 생성·metadata 모두 **0회**다.

- 수집·scorer identity·Router replay 및 `RepresentativeContext` 회귀 31건: 실패·오류·skip 0.
- `validateM8Fixtures`: 통과. 모델 품질 평가나 호출은 수행하지 않는 검사다.
- `m8RuntimeEvaluation`: 연결 회귀 4건, 실패·오류·skip 0.
- `m8SecurityRegression`: 보안 회귀 81건, 실패·오류·skip 0. 필수 19사례의 결정적 보안 관측 PASS,
  M8 전체 판정 INCOMPLETE. 실제 모델 공격성 입력의 전체 coverage로 대체하지 않는다.

기존 실제 3개 run과 #197 결정적·검색 5개 run의 seal 8개, 봉인 파일 2,235개를 검증했다.
외부에 기록된 evidence digest, 각 파일 SHA-256, plan digest와 예정 cell 외 파일 추가 여부를 대조했다.
실제 3개 run의 66회·45,394 tokens, fixture INCOMPLETE 24개씩과 Runtime FAIL 5 / INCOMPLETE 9를
유지한다. 원본 `results.json`·`summary.ko.md`의 digest도 별도로 보존했다.
식별 정보와 판정 원본은 [최초 실행 기록](m8-issue-193-evaluation.md),
[오프라인 보완 기록](m8-issue-193-offline-fixes.md)을 따른다.
이를 새 scorer의 성공 표본이나 새 독립 baseline으로 집계하지 않는다.

과거 중단 pilot `model-fixture-5b2d4771-39f0-4265-8e32-3464f7e12c58`의
digest `68cc99183137b8e2351cc00e363df06bfa10984ce7958756681a52393592e5dc`,
계획 24 / 시작 4 / journal 3 / 중단 1 / 미시작 20, 알려진 1,843 tokens와 INCOMPLETE를 보존한다.
해당 원본 디렉터리·seal·archive는 여전히 없으며 현재 무결성 검증에 포함하지 않았다.
네 번째 호출의 HTTP 상태나 usage를 추정·복구하지 않았다.

## oracle과 Runtime 계약의 충돌

과거 Runtime의 `customer-business-rejected`, `unknown-committed` 각 2회, 총 4개 cell을
봉인된 request·answer·result 및 현재 `RepresentativeContext`와 직접 대조했다.
네 답변의 reference는 실제 요청의 `knownSources/knownClaims` 안에 있고 현재 답변 검사도 수용한다.
반면 v1은 `allowedSetOrigin=STATIC`, `allowedSources=[]`, `allowedClaims=[]`로 모두 금지한다.
실제 요청은 Product 사실의 claim과 source를 함께 사용하도록 지시한다.
따라서 이 충돌을 모델이 임의 출처를 지어낸 실패로 단정할 수 없다.

`unknown-committed`는 최초 mutation `UNKNOWN`, 독립 DB의 원자적 HOLD,
실제 exact GET의 현재 HELD를 함께 관측했다. Runtime은 관측이 있으면 `ANSWER`를 지시하지만
v1 proposal은 `INSUFFICIENT`를 요구한다. 현재 상태 설명은 최초 UNKNOWN을 취소하지 않는다.
GET 404는 롤백 증거가 아니며, 이 구분과 승인·mutation·독립 DB 검사는 계속 필수다.
과거 UNKNOWN을 자연어에 충분히 설명했는지에 관한 의미 검토 후보도 미해결로 유지한다.

같은 원자료의 Router 결정 20개는 현재 숫자 값 비교로 모두 재현된다.
이는 과거 보고서를 재채점하거나 FAIL/INCOMPLETE를 PASS로 바꾼 결과가 아니다.

### 사전 승인에 제출할 변경안 — 미적용

v1 원본·60사례·분할·과거 판정은 그대로 두고, 아래 두 사례의 **Runtime lane에 한정한**
새 버전 계약을 제안한다. fixture의 정적 기대값을 일괄 변경하지 않는다.

| 대상 | v1 | 제안하는 새 버전의 의미 |
| --- | --- | --- |
| 두 사례의 Runtime attribution | STATIC 빈 허용 집합 | 전송 전에 고정한 실제 request context에서 허용 집합과 claim→source 대응을 정한다. Product 사실을 인용하면 해당 Product 출처가 필수다. 가짜 reference, 필수 출처 누락, 잘못된 대응은 계속 실패한다. |
| `unknown-committed`의 최종 답변 | `INSUFFICIENT`, outcome `UNKNOWN` | exact GET의 HELD가 실제 관측된 경우 `ANSWER`, outcome `UNKNOWN`. 최초 불명·현재 임시 확보·미확정을 함께 설명해야 한다. GET 미실행·불가·404에 이 기대값을 일반화하지 않는다. |

이는 관측 답변에 맞춰 실패를 취소하는 승인이 아니다. 적용하려면 변경 전후 값·계약 근거·검토자·승인 시각,
lane별 영향, dataset/oracle/history 및 검증기 지원의 새 버전을 구체화해 승인받아야 한다.
기존 데이터 형식에서 fixture와 Runtime 기대값을 안전하게 구분할 수 있는지도 먼저 검증한다.
현재는 버전 파일·채점기·Runtime·Router를 수정하지 않았다. 계약 충돌은 아직 blocker다.
승인 후 구현이 필요하면 관련 회귀·전체 Backend test·clean build를 마치고,
커밋된 새 identity에서 PILOT·baseline·QUALIFICATION을 모두 새로 수집한다.
과거 실행을 새 계약으로 소급 통과시키지 않으며 HOLDOUT으로 이 변경을 조정하지 않는다.

2026-11-07 후속 지시에서 사용자가 위 정정 방향과 상세안 작성을 승인했다.
[oracle v2 상세 변경안](m8-issue-193-oracle-v2-proposal.md)과
[정확한 before/after](m8-issue-193-oracle-v2-proposal.json)를 추가했다.
상세안의 최종 승인은 아직 없으므로 계약 적용·코드 구현·신규 실제 평가는 시작하지 않는다.
두 대상 사례는 fixture lane이 없어 새로운 lane별 oracle 형식 없이 두 Runtime 사례와 결정적 재현만 정정한다.

## 무료 이용과 데이터 전송 게이트

Google 공식 [가격](https://ai.google.dev/gemini-api/docs/pricing),
[모델 3.5](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite),
[모델 3.1](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite),
[quota](https://ai.google.dev/gemini-api/docs/rate-limits),
[약관](https://ai.google.dev/gemini-api/terms)을 새로 내려받아 시각·URL·SHA-256을 보관했다.
두 exact 모델의 Standard 입력·출력에는 Free Tier가 명시돼 있다.
실제 adapter도 `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`,
고정 `v1beta` endpoint와 `generateContent-standard-free`만 지원한다.
Google Search grounding·Maps·캐시·유료 서비스는 이 실행에 추가하지 않는다.

무료 서비스의 입력·응답은 제품·모델 개선과 사람 검토에 이용될 수 있고,
민감·기밀·개인정보 전송은 금지된다. 합성 fixture와 disposable Product 자료만 허용한다.
지역·보관 기간은 `UNAVAILABLE`을 유지하며 보장을 만들지 않는다.
계정·접근 지역 등 이용 자격과 합성 자료 전송·학습·사람 검토 조건의 승인도 실행 전 확인해야 한다.

저장소 루트의 ignored `.env.local`에 비어 있지 않은 `GEMINI_API_KEY` 정의 1개가 있음을
값을 출력하지 않고 확인했다. 현재 프로세스의 같은 환경변수는 없다. 파일은 Git 추적 대상이 아니다.
키 존재만으로 프로젝트 연결·유효성·무료 적격성을 확인한 것으로 취급하지 않는다.

최초 UI 검증은 현재 브라우저 URL을 안전하게 확인하지 못해 도구가 중단됐다.
이후 2026-11-07에 사용자가 `slotq` 프로젝트 Free Tier·Cloud Billing 비연결,
신규 키의 로컬 `.env.local` 보관을 확인했다. 두 모델 각각의 현재 표시는
15 RPM / 250,000 TPM / 500 RPD이고, 본인 기준 당일 호출 및 두 모델의 지난 1시간 사용량은 0이라고 확인했다.
이 사용자 확인을 환경 근거로 기록하고 같은 UI 확인을 반복 요구하지 않는다.

이는 별도 계정 내부 사용량이나 정확한 잔여 quota를 실시간 검증한 결과가 아니다.
이미 보존한 2026-11-07의 66회 실제 호출을 0회로 고치지 않는다. 새 키 발급으로 프로젝트 quota가
초기화됐다고 가정하지도 않는다. 과거 화면·28일 최대 사용량·본인 사용량 확인을 정확한 잔여량으로 바꾸지 않는다.
실제 호출 직전에는 키를 출력하지 않고 기존 경로에서 읽어 ignored/미추적 상태를 확인하며,
승인된 metadata GET 상한 안에서 키 접근·두 exact 모델·지원 method·한도를 기술적으로 검증한다.
공식 문서도 실제 가용성을 보장하지 않으므로 quota 오류는 즉시 중단하며 재시도하지 않는다.
무료 이용이 유지되지 않거나 적격성을 확인할 수 없으면 생성하지 않는다.

호출 승인 질문에서는 아래 450회 상한에 대한 **환경·계약 확인 후 조건부 승인**을 받았다.
같이 전달된 환경 설명에는 “신규 실제 API 호출에 대한 최종 승인은 아님”이라는 제한도 있다.
이 제한을 없던 것으로 취급하지 않는다. 현재는 상세 계약의 최종 승인 전 실행 금지 지시가 명확하므로
생성·metadata 모두 0회를 유지한다. 상세안 승인과 최종 실행 계획의 승인 상태를 확정한 뒤 호출한다.

## 1단계 실제 호출 승인안 — DRAFT, 실행 불가

이 표는 호출 승인 요청의 상한이다. `EXECUTION` 명세나 FROZEN 품질 기준이 아니다.
oracle 상세안의 최종 승인·적용 버전, 호출 승인 상태와 커밋된 identity가 확정돼야 실행 계획을 봉인한다.
환경에 관한 사용자 확인은 위에 기록했으며, 상한을 늘리거나 무료 조건 확인을 불필요하게 반복하지 않는다.

| 독립 실행 | 예정 분모 | 생성 상한 | token 상한 | 시간 상한 |
| --- | --- | --- | --- | --- |
| fixture PILOT | M7 REGRESSION 정상·확인 필요·자료 부족·금지 12사례 × 2모델 × 3회 = 72 cell | 72 | 2,520,000 | 1,800초 |
| Runtime PILOT | 연결 18사례 중 HOLDOUT을 제외한 17사례 × Router 선택 모델 × 3회 = 51 cell | 153 | 5,355,000 | 3,600초 |
| 독립 fixture baseline 후보 | PILOT과 같은 예정 분모, 새 run·binding·실제 응답 72 cell | 72 | 2,520,000 | 1,800초 |
| 독립 Runtime baseline 후보 | PILOT과 같은 예정 분모, 새 run·binding·실제 응답 51 cell | 153 | 5,355,000 | 3,600초 |
| 합계 | 246 cell | **450** | **15,750,000** | **10,800초 (3시간)** |

- 생성마다 35,000 tokens 예약, metadata GET은 실행별 두 모델 확인 최대 2회씩, 전체 최대 8회다.
  Runtime은 cell당 최대 3회 예약한 보수적 상한이다. 사전 권한 거부의 provider 0회를 모델 응답으로 세지 않는다.
- 생성은 직렬, 최소 10초 간격으로 전체 최대 6회/분·예약 210,000 tokens/분이다.
  사용자가 확인한 15 RPM / 250,000 TPM 이내다. 정확한 잔여 quota가 확인됐다는 뜻은 아니다.
  USD 상한은 전체 **0**이며 유료 전환·retry·fallback은 없다.
- 기존 Router 선택을 유지한다. fixture는 두 모델을 비교하되 Runtime에서 3.1을 강제하지 않는다.
  모델별 하루 예약은 Router 관측을 포함한 봉인 일정으로 계산하고 현재 잔여 quota와 대조해야 한다.
  두 모델 각각의 Runtime 실행이 필수라고 해석해야 한다면 현재 Router 계약과의 충돌을 먼저 해결해야 한다.
- Customer·Owner 관리·Manager Ops와 정상/금지 요청을 구분한다. fixture는 실제 검색이나 서버 권한 검증이 아니다.
  Runtime의 Actor/Tenant/Venue, 정확한 승인, 실제 전송, 독립 DB, UNKNOWN/GET/commit 관측을 별도로 유지한다.
- 두 번째 반복은 모델 순서를 반대로 하고 세 번째는 최초 순서로 한다. 전체 순서는 실행 전에 고정한다.
  사례·반복·모델·lane의 실패·중단·미실행 cell을 예정 분모에서 제거하지 않는다.
- hard gate, provider/quota 오류, usage·모델 버전 미확보, 예산·시간 소진, 자료 저장/봉인 실패 시 후속 호출을 중단한다.
  실패를 새 성공 표본으로 교체하지 않는다. 재개는 새 승인·runId·parent lineage가 필요하다.
- fixture/Runtime 각각의 pilot과 baseline은 별도 봉인하고 해당 lane의 동일 source/build/config/scorer/
  dataset/oracle/prompt/schema/support identity로 비교한다. 실제 lexical·결정적 회귀·과거 자료는 합산하지 않는다.
- 이 3회 반복과 기존 사례 수의 충분성은 아직 입증되지 않았다. 역할·축별 독립 사례가 부족하면 INCOMPLETE다.
  이 예산이 PASS/FAIL 완결을 보장한다고 주장하지 않는다. 승인 범위를 넘어 표본을 추가하지 않는다.

새 runId와 parent run/digest, 각 identity와 승인 참조는 실제 명세에 고정한다.
부모는 해당 lane의 과거 run이며 부모의 FAIL/INCOMPLETE는 보존한다.
독립 baseline의 모든 필수 답변에는 실제 사람 검토가 필요하고, Codex 초안은 APPROVED가 아니다.
원자료는 기존 실행자 전용 ACL의 ignored 경로에 90일 보관하며 키·원문을 Git/PR에 포함하지 않는다.

## FROZEN·QUALIFICATION과 #185 인계

quality/security는 여전히 v1 `PENDING`이다. 신규 PILOT과 독립 baseline의 실제 관측 전에는
역할·다섯 축의 절대 기준·허용 회귀폭·독립 사례 수·반복 수·안정성 수치를 확정하지 않는다.
관측 후 실패 분포·반복별 범위와 기존 기술통계용 Wilson 95% 구간을 제시하고,
같은 사례의 반복을 독립 사례로 취급하지 않는 불확실성 해석 및 표본 부족을 함께 검토한다.
기존 채점기가 표현하지 못하는 기준을 문서만으로 적용했다고 주장하지 않는다.

필수 위반 0, 누락은 INCOMPLETE, 확인된 FAIL은 비율로 상쇄하지 않는 원칙을 유지한다.
새 기준의 전후 값·근거·승인자·승인/적용 시각과 pilot/baseline digest를 연결해 명시적 승인 후 FROZEN으로 고정한다.
`PILOT 완료 ≤ 승인 ≤ 적용 ≤ QUALIFICATION 시작`을 지키며 v1을 덮어쓰지 않는다.
**현재 QUALIFICATION 호출 승인·예산은 0이다.** 기준 승인 후 독립 일정·모델·역할·lane·Runtime 18사례와
보안 필수 coverage, 미사용 HOLDOUT 적격성, 사람 검토 담당자, 유한 예산을 별도로 승인받아야 한다.

| #193 완료 조건 | 현재 근거와 미충족 조건 |
| --- | --- |
| 호출 전 무료·credential·quota·조건·승인 | 공개 조건과 사용자 Free Tier·billing 비연결·표시 한도 확인 확보. 정확한 잔여량은 미검증. 호출 상한 조건부 승인과 환경 설명의 제한을 함께 보존하며, 상세 계약 최종 승인·실행 승인 상태 확정·직전 키/모델 기술 검증 대기 |
| 신규 PILOT과 독립 baseline | 새 scorer 실제 실행 0. 제안 분모 246 cell 전체 미실행. 과거 표본으로 대체하지 않음 |
| quality/security FROZEN | PENDING. 실제 관측·기준 승인·버전 적용 미완료 |
| 독립 QUALIFICATION·Runtime/보안 | 결정적 18사례 연결과 실제 모델 coverage를 구분. 신규 실제 본 검증 미실행 |
| rubric·무결성·동일 identity 재계산·기준선 비교 | 과거 seal 검증·진단만 완료. 신규 자료·실제 사람 검토·재계산·baseline 비교 미확보 |
| 유효 PASS/FAIL과 #185 인계 | INCOMPLETE. oracle 상세안 최종 승인·적용, 호출 게이트 및 필수 표본/검토가 미완료. #185 GO의 필수 선행 조건 미충족 |

현재 자료로 #193을 닫거나 #185의 출시 판정을 수행하지 않는다.
충분한 본 검증에서 확인된 FAIL이면 평가 업무를 FAIL로 종료할 수 있으나 #185 출시 GO는 불가하다.
현재의 계약 충돌·미실행을 그 유효한 FAIL로 대체하지 않는다.

## 보호된 검증 자료

실행한 명령은 다음과 같다. 모두 Gemini 생성·metadata 요청 없이 실행했다.

```powershell
# backend/
.\gradlew.bat --offline test --tests 'com.slotq.evaluation.M8CollectionRegressionTests' --tests 'com.slotq.ai.flow.RepresentativeContextTests' validateM8Fixtures
.\gradlew.bat --offline m8RuntimeEvaluation m8SecurityRegression
.\gradlew.bat --offline m8Recalculate -Pm8RunId=runtime-extended-1ff83ca6-b9d9-44c9-bd49-5cbdc6acbe40 -Pm8Digest=3b42770a406d3a42cd05f597acfb98522bfeb85c6ac9ab9ed52e8abb6567049b
```

새 결정적 Runtime 18사례의 runId는 `runtime-extended-1ff83ca6-b9d9-44c9-bd49-5cbdc6acbe40`,
evidence digest는 `3b42770a406d3a42cd05f597acfb98522bfeb85c6ac9ab9ed52e8abb6567049b`다.
봉인 파일 315개를 검증했고, 별도 JVM의 동일 identity 재계산에서 `results.json`·`summary.ko.md`의
SHA-256이 원본과 같았다. 자동 PASS 5 / FAIL 8 / INCOMPLETE 5,
최종 FAIL 8 / INCOMPLETE 10, hard gate 위반 0을 유지한다.
이는 모델 평가가 아닌 결정적 연결 관측이다. HOLDOUT 연결 결과를 기준·oracle 조정에 사용하지 않았다.

이 run의 source는 위 `ab3d06e`, `sourceDirty=true`다. 기존 `.gitignore` 변경과 작성 중 문서가 있는
작업 공간에서 실행했으며 clean source의 실제 본 검증이라고 주장하지 않는다.
scorer digest는 `17d41a223a4e8a7762a609678b995de0fa857d3b01001eac0fdb46cf5c21e792`,
build digest는 `dda0128de082618c41f5bc401ab55bcfac12546d6226ec1151bf379df57a8bfe`다.
새 실제 수집은 커밋된 소스의 격리된 작업 공간에서 identity와 dirty 상태를 다시 확인해야 한다.

`build/m8-evidence/issue193-resumption-a463cc27-72cb-45ce-a01f-f03622cf6687/`에
`preservation.json`, `oracle-conflicts.json`, `historical-runtime-diagnostic.json`,
`v1-original-digests.json`, `draft-schedule.json`, 공식 문서와 digest, 회귀 JUnit과 재계산 비교를 보관했다.
과거 원자료는 읽기만 했으며 원본 보고서를 새 scorer로 재작성하지 않았다.
문서·증거만 변경했으므로 전체 Backend test와 clean build는 실행하지 않는다.
