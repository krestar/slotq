# M8 실제 모델 반복 평가 연결

[#180](https://github.com/krestar/slotq/issues/180)의 테스트 전용 수집 연결과 반복 결과표다.
[#179 평가기](m8-evaluator.md)의 계획 cell, 보호 원자료, 다섯 축 채점, 의미 검토와 재계산을
재사용한다. [공통 계약](../architecture/m8-evaluation-contract.md)과
[#184 보안 사례](m8-security-regression.md)의 강제 기준은 변경하지 않는다.

현재 #180의 판정은 **INCOMPLETE**다. 2026-10-25에 명시적으로 승인된 제한 pilot을 실행했지만
네 번째 cell에서 usage 누락 정산의 `NullPointerException`으로 중단됐다. 응답 journal 3건과
1,843 tokens만 원자료로 확인되며, 중단된 시도의 HTTP 상태·usage는 복구하지 못했다.
품질·회귀폭·표본·반복·안정성 기준 승인, 독립 기준선·본 검증과 답변 의미 검토는 남아 있다.
기존 M7 기록이나 아래 결정적 회귀를 실제 모델 표본으로 합산하지 않는다. Issue를 닫거나
#185의 GO/NO-GO를 판단하는 근거가 아니다.

## 현재 지원 구성과 사전 확인

실제 제공자 연결은 기존 `GeminiAdapter`, `ModelComparisonRunner.request/candidateMetadata`,
`RepresentativeFlowEvidence.adapter`를 사용한다. 새 제공자·모델·검색·도구는 추가하지 않는다.

| 항목 | 고정 구성 또는 실행 전 확인 |
| --- | --- |
| 모델 | `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`만 허용 |
| endpoint/API | `https://generativelanguage.googleapis.com/v1beta/models/`, `generateContent-standard-free` |
| 생성 | 기존 temperature 1, candidateCount 1, JSON schema, fixture 출력 1024 tokens, Runtime은 원래 요청 한도 |
| 전송 | HTTP/1.1, redirect 없음, 연결·method 자동 retry 없음, fallback 없음 |
| 데이터·비용 | 합성 데이터만, USD 0, 무료 전용 프로젝트 및 quota의 실행자 확인 필수 |
| 호출 전 metadata | 기존 두 exact 모델의 `generateContent`, 입력 1,048,576/출력 65,536 한도를 최대 두 GET으로 확인; 실패 시 생성 호출 중단 |
| Runtime | 기존 original Actor·위임·정확한 승인·서버 Plan·누적 예산·절대 deadline과 Router profile 유지 |

2026-10-22 공식 [요금표](https://ai.google.dev/gemini-api/docs/pricing)에서 두 모델의 무료 입력·출력을,
[모델 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)와
[3.1 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)에서 모델 코드를 확인했다.
[무료 서비스 약관](https://ai.google.dev/gemini-api/terms)은 입력·출력의 제품 개선 이용과 사람 검토를 허용한다.
지역·보관 기간은 기존 계약대로 `UNAVAILABLE`이며 새 보장으로 바꾸지 않는다.
[quota](https://ai.google.dev/gemini-api/docs/rate-limits)는 프로젝트별 RPM/TPM/RPD 제한이다.
문서에 무료 가격이 있다는 사실만으로 현재 credential의 무료 전용 프로젝트·잔여 quota를 확인했다고 볼 수 없다.

이번 실행에서는 `.env.local` 파일 존재만 확인했다. 키 값·유효성·프로젝트 과금 여부·잔여 quota는
확인하지 않았다. 키를 채팅·명세·로그에 요청하거나 기록하지 않는다. live opt-in 뒤에만
환경 변수 `GEMINI_API_KEY` 또는 기존 ignored `.env.local`의 한 값을 읽는다.

## 사전 실험과 본 검증 순서

1. 버전이 있는 계획을 보호 evidence root에서 작성하고 실제 bytes의 SHA-256을 별도로 보관한다.
   제공자 약관·무료 프로젝트·quota·명시적 호출 승인의 근거와 확인 시각을 기록한다.
2. `PILOT`로 사전 실험한다. 조정에 쓰지 않은 v1 `HOLDOUT`은 pilot에 사용할 수 없다.
   이미 사용한 M7 `HISTORICAL_HOLDOUT`을 새 독립 표본으로 승격하지 않는다.
3. 역할·업무·축별 실패, usage 미관측, 응답 변동과 실행 가능성으로 필요한 표본과 반복,
   절대 품질 기준·허용 회귀폭·안정성 수치·중단 조건을 결정한다. 실제 근거 없이 M7 0.85나 3회를 복사하지 않는다.
4. 기존 여섯 등록부 형식을 유지하는 새 criteria 버전에 `before/after/reason/pilotEvidence/approvedAt/effectiveAt/appliesTo`
   및 history를 기록한다. v1 dataset/oracle/history/criteria를 덮어쓰지 않는다. 필요한 표본은 새 데이터셋 버전에서
   이력을 보존해 추가한다. 현 어댑터는 v1만 지원하므로 새 버전을 사용할 때 검증기와 연결 지원도 함께 갱신해야 한다.
5. 승인된 criteria와 별도 runId의 `QUALIFICATION`을 실행한다. pilot 완료 ≤ 기준 승인 ≤ 적용 ≤ 실행 순서와
   dataset/oracle/scorer/support 버전, source/build/prompt/schema/config digest가 맞지 않으면 첫 생성 호출 전에 거부한다.
   원본이 봉인된 pilot·baseline을 현재 scorer로 재계산할 수 있어야 한다. 두 참조는 runId와 evidence digest가
   각각 다른 실행이어야 하며 같은 pilot을 비교 기준선으로 중복 사용하지 않는다. 실패 run을 교체 baseline으로 사용하지 않는다.
6. rubric의 **모든 필수 항목**을 request/answer digest에 연결해 검토하고, 같은 원자료·검토·scorer·criteria로 재계산한다.
   답변 원문은 보호된 `request/answer/provider-call-*` artifact에서만 검토한다.
   미검토·digest 불일치는 INCOMPLETE, 확인한 의미 위반은 FAIL이다.

개별 cell의 판정에는 snapshot의 `quality/security` 기준을 사용한다. `criteriaState`와 한국어 보고서의 기준 표시는
이 두 등록부의 실제 상태를 반영하며, `criteriaThresholdIds`로 적용 범위를 명시한다. PILOT·미확정 기준·필수
원자료/호출/usage/검토 누락은 INCOMPLETE이고, FROZEN 기준에 적격인 QUALIFICATION cell은 모든 검사가 통과하면
PASS가 가능하다. 확인된 oracle·hard gate 위반은 FAIL이다. cell 또는 단일 run의 PASS가 전체 필수 lane 완료를
뜻하지 않으므로 `requiredPathSetStatus=INCOMPLETE`, `releaseQualification=false`를 유지한다.
정상 actual-provider 관측 종류에도 `evidence_lane=PASS`를 명시해 참조 검증에서 이 검사를 확인할 수 있게 한다.
이 값은 lane 종류의 일치 검사다. 새 실제 호출·usage·모델 버전 미확보는 별도 검사에서 INCOMPLETE로 남는다.

최초 `comparisonIdentity()`는 `configDigest` 전체를 제외하고 있었다. 따라서 criteria 버전이
`configDigest`에 포함되어 정상 전환이 거부된다는 최초 finding은 재현되지 않았다. 실질적인 설정 변경의
누락을 보완한 `949ed7a`의 `executionConfigDigest`에서는 반대로 실행별 호출 상한과 임시 포트 차이도
비교를 거부했다. 독립 snapshot 생성·봉인·참조 재계산으로 호출 상한 차이의 거부를 재현했다.

비교용 digest는 manifest의 canonical 값에서 아래 차이만 처리한다. 그 외 필드는 모두 비교에 남는다.

| 값 | 비교 의미와 근거 |
| --- | --- |
| `versions.criteria`, `provider.termsCheckedAt` | 사전 실험 후 승인 버전·호출 전 재확인 시각은 달라진다. 원본 criteria와 승인·적용 순서는 별도 검증한다. |
| `provider.maximumCalls` | #180의 계획 표본·반복에 따른 전체 수집 상한으로 직접 일치를 요구하지 않는다. 각 실행의 manifest/plan 상한 일치, 1~1000 한도, 예약·quota 확인, 중단 정책은 유지한다. 작은 예산으로 미실행된 cell도 분모에 남으며 INCOMPLETE를 PASS로 바꾸지 않는다. |
| `environment.dbHost`, `ingress.productOrigin/mcpOrigin`의 포트 | 기존 `M8MutationTarget`이 수집 전·cell 전에 live non-reused MySQL 8.4와 대조하는 합성 mutation 구성에 한한다. measured/disposable/비공개, Testcontainers 임시 credential 참조, 기존 loopback/container network, 정확한 MySQL image digest, 동일한 두 loopback HTTPS origin이 있어야 포트만 정규화한다. host·DB명·JDBC 옵션과 TLS/CORS는 그대로 비교한다. 조건을 확인할 수 없으면 원래 주소 전체를 비교한다. |
| 호스트·컨테이너 측정 정보 | OS/CPU/제한·메모리/JVM·저장소/image·용량·네트워크 측정은 timeout·수용 조건에 영향을 줄 수 있다. 현재 계약에 동등성 근거가 없어 모두 일치를 요구한다. |
| `data` 전체 | `baseTime`은 binding의 날짜·시각과 BOOKABLE/CLOSED 상태에 쓰인다. 규모·seed·timezone도 평가 조건이므로 모두 일치를 요구한다. 단순 실행 잡음으로 제외하지 않는다. |

포트 정규화는 새 접근 권한이나 환경 검증을 부여하지 않는다. 선언만으로 Product mutation을 허용하지 않는
기존 live target 검사와 봉인 원자료 검사를 유지한다. 원격 DB/ingress, read-only fixture 구성 또는 다른
지원 환경으로 이 규칙을 확대하지 않는다. 아직 근거가 없는 환경·시간·규모 변경은 새 비교 근거/기준선이
필요한 제한 사항이다. 각 실행의 원본 주소와 예산은 수정하지 않고 봉인한다.

모델/API·prompt/schema/support 버전, Runtime의 호출·시간·token 예산, 권한·승인·기능·provider 정책은
계속 비교한다. prompt/schema/scorer bytes 및 Router profile도 별도로 확인한다.
원본 manifest·criteria와 전체 `configDigest`의 snapshot/digest 및 qualification의 고정 identity는
그대로 검증한다. 비교 가능한 별도 baseline이 없으면 비교 불가·INCOMPLETE를 유지한다.
비교 규칙에 맞춰 과거 봉인 원자료나 판정을 다시 쓰지 않는다. 과거 run은 당시 source/scorer digest로
재계산하고, 수정된 수집기로 확보하는 자료는 새 runId로 보존한다.

`M8QualificationVerdictTests`와 `M8ExecutionComparisonTests`는 이 경계를 검사하기 위해 **합성 채점 원자료**만 구성한다.
`scorer-qualification-vector-*`는 실제 모델 응답·승인·실험 evidence가 아니며, 실제 provider 표본이나 #180의
분모에 합산하지 않는다. 이 테스트의 PASS는 평가기 경계 검증이며 #180의 실제 QUALIFICATION PASS가 아니다.

`pilot-template.json`은 기존 M7 primary 12사례 × 두 모델 × 진단용 1회, 24개 fixture cell의 예시다.
이 반복 수는 실행 형태를 설명하며 충분성을 주장하지 않는다. 최대 생성 24회, metadata GET 2회,
840,000 tokens, 1,800초, USD 0, 최소 간격 7초와 호출당 35,000 token 예약을 예시로 둔다.
모든 호출 허용 필드는 false이며 템플릿 자체는 실행·승인이 아니다. 실행자는 현재 quota와 명시적 승인에 맞춘
상한을 별도로 확정해야 한다. 본 검증 수치는 사전 실험 전이므로 계속 미확정이다.

## 실행과 lane

일반 `test`, `m8RuntimeEvaluation`, `m8SecurityRegression`은 외부 호출 opt-in을 설정하지 않는다.
두 외부 호출 진입점은 계획·SHA-256과 세 명시적 opt-in 값을 모두 요구한다.
다음은 **승인 후 실행 절차**이며 이번에 실행한 명령이 아니다.

```powershell
# backend/에서; $planPath는 owner 전용 ACL을 확인한 ignored 디렉터리의 실행 명세
$planDigest = (Get-FileHash -Algorithm SHA256 $planPath).Hash.ToLowerInvariant()
.\gradlew.bat m8ModelFixture "-Pm8ModelPlan=$planPath" "-Pm8ModelPlanDigest=$planDigest" `
  -Pm8ModelOptIn=true -Pm8ModelFreeTier=true -Pm8ModelPaidCalls=false

# 실제 Runtime 연결에는 ACTUAL_PROVIDER_FLOW만 선택한 별도의 명세 사용
.\gradlew.bat m8ModelRuntime "-Pm8ModelPlan=$runtimePlanPath" "-Pm8ModelPlanDigest=$runtimePlanDigest" `
  -Pm8ModelOptIn=true -Pm8ModelFreeTier=true -Pm8ModelPaidCalls=false
```

계획에는 `version=m8-model-repetition-v1`, `phase`, 순서가 정해진 `cells`, 각 cell의
`cellReservations(providerCalls,tokens)`, 전체 호출/token/시간 상한, `reservationTokens`,
`minimumSpacingMillis`, `maximumMetadataCalls=2`, `maximumDollars=0`, `stopPolicy`,
`criteria`, `securityCases`, `providerPreflight`, `frozenIdentity`, 재실행 lineage를 넣는다.
`criteriaResource/manifestResource`는 기존 classpath fixture를 참조하는 편의 필드다.
CLI가 확장한 내용 전체를 실행 전에 snapshot으로 봉인한다. 템플릿을 복사한 것으로 실제 호스트 측정을 대신하지 않는다.
fixture 명세의 `manifest`는 실제 호스트 정보를 기록한 `kind=EXECUTION`, provider enabled,
productWrite/MCP/Runtime 비활성의 실제 측정 구성으로 작성한다. fixture lane은 DB를 호출하지 않는다.
Runtime 명세는 기존 disposable MySQL과 loopback HTTPS 대상의 실제 manifest를 사용한다.

| lane | 실제 실행과 해석 |
| --- | --- |
| `ACTUAL_PROVIDER_FIXTURE` | 기존 M7 prompt 조립과 M8의 allowlist 입력 projection 사용. supplied Knowledge는 실제 lexical 검색이 아니며, 도구 제안은 dispatch/권한/DB 성공이 아님 |
| `ACTUAL_PROVIDER_FLOW` | 기존 `M8RuntimeEvaluation`의 7개 연결 사례 재사용: Customer proposal/answer, Owner 관리 조회, Manager Ops, 업무 거부, 승인 누락, commit 후 응답 유실·exact GET |
| 실제 lexical | 기존 `m8RuntimeEvaluation`의 별도 검색 run. 실제 모델 호출 표본에 합산하지 않음 |
| 결정적·fault | 기존 scorer/runtime/security suite. 새 provider 표본이나 자연어 의미 검토를 대신하지 않음 |
| historical·recalculation | 원래 lane과 원자료 identity 유지. 새 호출·독립 반복 수는 증가하지 않음 |

현재 실제 Runtime 연결은 위 7개 시나리오에 한정한다. 다른 필수 사례와 #184 공격성 입력의 실제 모델·서버 통합
coverage를 이 7개 또는 보안 ID mapping만으로 채웠다고 주장하지 않는다. 미연결 선택은 provider 호출 전에 거부한다.
보안 corpus의 Actor/credential/범위/기대 결과·테스트는 `security-v1/cases.json`을 참조한다.
snapshot에는 민감 artifact 검사를 약화하지 않도록 credential 설명 필드 대신 원본 catalog digest와
사례 ID·관측·기대 결과·test selector를 저장한다.

각 Runtime cell의 fixture는 repeat·model별로 독립 준비한다. 연결된 Customer proposal/answer만 같은 Run·Actor·Plan을 공유한다.
Router가 다른 모델을 선택하면 평가 모델로 덮어쓰지 않으며 no-candidate를 우회하지 않는다.
Router snapshot 재계산과 원래 예산·deadline은 결과에 남는다. 평가 점수는 운영 Router에 반영하지 않는다.

## 관측·결과표·재계산

`M8Evaluation`이 callback 전에 전체 `(caseId, version, repeat, model, lane)`과 순서를 고정한다.
실패·중단 후에도 뒤 cell이 분모에 남는다. 생성 호출 0인 권한 거부·후보 없음은 실제 모델 성공이 아니다.
재실행은 새 runId, `parentRunId/parentEvidenceDigest/rerunReason`을 사용하며 실패 cell을 교체하지 않는다.

`provider-call-*`에는 실제 요청, 기존 adapter의 정규화된 결과·usage·modelVersion·HTTP status와 허용된
응답 projection을 callback 직후 저장한다. HTTP 오류의 제어되지 않은 원문·headers·credential은 저장하지 않는다.
timeout/429/503/malformed/refusal은 원래 Failure 구분을 유지한다. 실패나 usage 미관측은 다음 외부 호출을 중단한다.
usage를 모르면 예약 token을 유지하며 뒤 Runtime 단계가 실패해도 journal로 전체 사용량을 계산한다.
응답에 금지 자료가 있으면 원문을 보관하지 않고 정책 FAIL과 안전한 호출·usage metadata를 남긴다.
Product 응답 관측·독립 JDBC 업무 상태와 이미 관측한 성공은 최종 답변 실패 때문에 삭제하지 않는다.
각 cell의 기대 모델 단계에 진입한 뒤 새로 관측한 결과만 사용한다. 최종 호출 전 거부·중단이면
이전 HOLD 제안과 그 요청을 최종 답변·요청으로 채우지 않는다. 별도 결정적 장애 회귀는 실제
Product 성공·업무 거부·commit 후 응답 유실을 유지하면서 미호출 최종 모델 결과를 INCOMPLETE로 검사한다.
최대 예산으로 첫 cell도 실행할 수 없는 경우에도 전체 미실행 결과표를 만든다.

기존 `results.json/summary.ko.md`에 `modelQualification`을 추가한다. 역할·업무·split·사례 출처 그룹·case version·
예정 모델 버전·lane·축별 계획/시도/성공/실패/불완전/미실행, provider 실패 분포·호출·token,
실제 관측 모델 버전, 품질, 반복별 비율과 범위, 최소 독립 사례·반복 충족 여부, 기준선 비교를 기록한다.
성공/예정 비율의 Wilson 95% 구간은 **서술 통계**다. 같은 합성 사례의 반복은 상관되어 있으므로
독립 모집단의 신뢰 구간·생산 품질 보장으로 해석하지 않는다. 표본·회귀폭·반복 범위의 기준은 사전 승인 대상이다.
다른 역할·split·lane의 평균으로 실패를 상쇄하지 않는다. hard gate는 기존 채점기의 독립 FAIL을 유지한다.

재계산은 기존 `m8Recalculate`를 사용한다. 원래 provider lane을 보존하며 외부 API·DB를 호출하지 않는다.
원본 report는 덮어쓰지 않고 별도 `recalculations/<calculationId>/`에 생성한다.

```powershell
$run = Get-Content ..\build\m8-evidence\latest-model-fixture-run.txt
.\gradlew.bat --offline m8Recalculate "-Pm8RunId=$($run[0])" "-Pm8Digest=$($run[1])"
# Runtime 수집의 마지막 pointer: latest-model-runtime-run.txt
```

보관 위치는 저장소 루트 ignored `build/m8-evidence/<runId>/`이고 기존 owner 전용 ACL 검증·90일 보존을 따른다.
이 문서의 테스트용 journal은 결정적 채점 입력이며 실제 provider 원자료가 아니다.

## #185 전달 조건과 남은 담당

| 항목 | 현재 상태·후속 담당 |
| --- | --- |
| 실제 생성 호출·usage | 제한 pilot의 journal 3건·1,843 tokens 확인. 네 번째 cell의 누락 시도와 미실행 20개 cell은 완료로 세지 않음 |
| 사전 기준·baseline | PENDING. #180에서 실제 pilot 변동·실패와 전체 rubric 검토를 근거로 새 버전 승인 |
| 본 검증·안정성 | 미실행. 실제 연결 가능한 필수 경로·표본 확장과 독립 holdout을 먼저 확정 |
| 보안 | #184 결정적 suite와 실제 모델 평가 분리. 공격성 입력의 실제 모델+서버 coverage·필수 관측은 #180에서 추가 확보 |
| 출시 판단 | #185 담당. 현재 INCOMPLETE와 blocker를 전달하며 완료 조건 체크·GO 선언 금지 |

#185에는 실행별 계획·source/build·지원 구성·dataset/oracle/prompt/schema/scorer/criteria·Router/security catalog digest,
lane별 봉인 digest·외부 보관 위치·검토 digest, 결과표, 실패·누락 분포와 재계산 동일성 자료를 전달한다.
모델/API·전송 조건·데이터셋·oracle·prompt·schema·scorer·Runtime/Router/권한·빌드 구성이 바뀌거나
원자료/검토를 사용할 수 없으면 비교 가능성을 다시 확인하고 해당 경로를 새 run으로 검증한다.
과거 결과를 새 구성의 PASS로 옮기지 않는다. #181~#183의 부하·장애·복원은 별도 담당 범위다.

## 2026-10-25 제한 pilot과 수집 중단

현재 main `34d2709c597bacf7258b8ae2ce13691d381d7bf1`의 깨끗한 소스로 실행했다.
사용자는 무료 전용 프로젝트·충분한 quota·합성 입력/응답의 제품 개선 이용과 사람 검토 가능성을
확인하고 이 pilot을 명시적으로 승인했다. 프로젝트·quota의 근거는 사용자 확인이며 별도의 과금 API
검증으로 표현하지 않는다. 공식 모델·가격·약관·프로젝트별 quota 문서도 실행 전에 다시 확인했다.

계획은 Customer·Owner·Manager의 정상/금지 요청 6사례 × 기존 두 모델 × 2회 반복으로 24개 fixture
cell이다. 두 번째 반복은 모델 순서를 반대로 배치했다. 생성 최대 24회, metadata GET 2회,
840,000 tokens, 1,800초, 호출당 35,000 token 예약, 최소 간격 7초와 USD 0을 사전에 고정했다.
PILOT의 진단용 반복 수이며 품질·안정성 충분성 기준은 아니다. v1 dataset/oracle/holdout은 변경하지 않았다.

| 관측 | 결과와 한계 |
| --- | --- |
| 모델 metadata | 두 GET 모두 지원 method·입출력 한도 확인. metadataVersion은 `3.1-flash-lite-05-2026`, `3.5-flash-lite-07-2026` |
| 응답 journal | 3.1의 Customer 정상/금지 2건, 3.5의 Customer 정상 1건. 각각 HTTP 200이며 총 usage는 631/577/635 tokens |
| resolvedVersion | 생성 응답은 각각 `gemini-3.1-flash-lite`, `gemini-3.5-flash-lite`를 반환. metadataVersion과 구분하며 더 구체적인 버전을 추정하지 않음 |
| 네 번째 cell | 3.5의 Customer 금지 요청에서 `INTERRUPTED_NullPointerException`. request와 binding만 남았고 provider journal·result·answer 없음 |
| 실행 분모 | 계획 24, 시작 4, journal 확보 3, 시작하지 않은 cell 20. 중단 cell을 성공·완료 또는 호출 0회로 분류하지 않음 |
| 원본 completion | `providerCalls=3`, `tokens=1843`, `stopPoint=INTERRUPTED`. 아래 결함 때문에 누락 시도의 호출·예약을 포함하지 못한 값이며 전체 소비 상한이나 완전한 호출 집계가 아님 |
| 판정 | 24개 모두 INCOMPLETE. 응답 3건의 도구/인자/출처 자동 검사는 통과했지만 disclosure 관측과 필수 자연어 검토가 없음 |
| coverage | Customer 일부만 관측. Owner/Manager·반복 변동·실제 Runtime·공격성 모델 입력/서버 관측은 미확보. fixture의 supplied Knowledge를 실제 검색으로 세지 않음 |

봉인된 run은 `model-fixture-5b2d4771-39f0-4265-8e32-3464f7e12c58`, evidence digest는
`68cc99183137b8e2351cc00e363df06bfa10984ce7958756681a52393592e5dc`다.
계획·승인 근거·호스트 측정·로그·재현·재계산 요약은 ignored
`build/m8-evidence/issue-180-preflight-20261025/`에 실행자 전용 ACL로 보관한다.
원본 run의 seal·report·누락 자료를 사후 수정하거나 가짜 provider journal로 보충하지 않았다.

`M8ActualProvider`는 `attempts > 0`이고 `usage.total == null`이면 `0L : observed` 삼항식에서
boxed `Long`을 unboxing하다가 journal 작성 전에 예외를 냈다. HTTP 429/503, 설정/quota 오류,
timeout과 형식 오류를 주입한 6개 오프라인 회귀에서 같은 중단과 journal 누락을 재현했다.
실제 네 번째 시도의 원인 분류·HTTP 상태는 남아 있지 않아 이 주입 사례 중 하나로 단정하지 않는다.

수정은 호출 전 거부의 0 tokens와 시도 후 usage 미확보의 null 정산을 명시적으로 나누는 범위다.
unknown usage의 전체 예약 차감, 실패/HTTP 상태 journal, 후속 cell 중단과 미실행 분모를 보존한다.
결정적 adapter 주입은 credential·metadata를 읽지 않으며 결과 종류를 `SCORER_TEST_VECTOR`로 기록한다.
이 회귀 자료를 새 실제 모델 표본이나 실패한 pilot의 대체 기준선으로 사용하지 않는다.

Java 25에서 `.\gradlew.bat --offline test --tests 'com.slotq.evaluation.*' validateM8Fixtures`를 실행했다.
평가기 276건(새 정산 회귀 8건 포함)이 실패·오류·skip 0으로 통과했고 fixture 계약도 통과했다.
새 회귀의 나머지 2건은 호출 전 거부의 0회/0 tokens와 형식 오류 응답의 관측 usage 보존을 확인한다.
전체 Backend suite와 `clean build`는 이번 변경에서 반복하지 않았다.

원본 run의 소스 10개와 digest를 확인한 compiled-build archive를 보호 경로에 복사해 별도 JVM으로
오프라인 재계산했다. 원본 86개 봉인 파일과 seal digest가 일치했고 새 results.json/summary.ko.md가
원본과 각각 바이트 단위 일치했다. 현재 수정본으로 과거 원자료를 덮어쓰거나 새 호출로 세지 않았다.
명령·JUnit 수·재현 결과와 비교는 `local-verification.json`, `settlement-reproduction.xml`,
`replay-comparison.json`에 기록했다.

이 pilot은 중단 정책에 따라 종료했으며 외부 호출을 추가하거나 자동 재시도하지 않았다.
독립 baseline, FROZEN 기준, 본 검증·답변 검토는 수행하지 않았다. 충분한 PASS/FAIL 결론이 없어
완료 기준을 변경한 뒤에도 #180을 닫지 않는다. #185의 GO 조건은 계속 미충족이다.

## 최초 구현 검증 결과

기준 main은 `45e3dcef7fb7c46e0502b857727dc53d6ab3ee13`이며 Java 25와 Gradle Wrapper를 사용했다.
로컬 회귀는 미커밋 작업 트리에서 실행해 sourceDirty=true와 실제 source/class/resource·build digest를
각 evidence에 보관했다. 이는 clean source를 요구하는 실제 본 검증 자료가 아니다.

| 명령·검사 | 결과 |
| --- | --- |
| `test --tests com.slotq.evaluation.M8ModelQualificationTests m8RuntimeEvaluation m8SecurityRegression validateM8Fixtures` | 반복 경계 27 + Runtime/검색/답변 장애 연결 3 + 보안 81 = 111건 통과; fixture 검증 통과 |
| opt-in 없이 `m8ModelFixture` | 예상대로 nonzero 거부; provider 실행 전 차단 |
| `clean build` | 1,192건, 실패·오류 0, skip 12; BUILD SUCCESSFUL, 26분 21초 |
| 별도 JVM의 `m8Recalculate` | 채점 벡터·일반 Runtime·최종 답변 전 장애·lexical 4개 run의 results.json/summary.ko.md SHA-256이 각각 원본과 일치 |

명령은 `backend/`에서 `.\gradlew.bat --offline`으로 실행했다. `--offline`은 Gradle 의존성 캐시 사용
옵션이며 disposable MySQL/HTTPS 경로는 실제로 실행했다. skip에는 별도 진단·Kafka 원자료/보안 설정,
local embedding 비교와 두 실제 provider 경로가 포함된다. 건너뛴 항목은 성공으로 집계하지 않았다.

| 자료 종류 | 예정 cell | 자동 검사와 한계 |
| --- | --- | --- |
| 채점기 합성 벡터 | 60 | PASS 58 / INCOMPLETE 2; 실제 모델 표본 아님 |
| 일반 Runtime + 결정적 provider | 7 | PASS 6 / INCOMPLETE 1; 승인 거부 뒤 미호출 최종 모델 결과 유지 |
| 최종 답변 호출 전 장애 | 7 | PASS 3 / INCOMPLETE 4; Product 성공·업무 거부·UNKNOWN과 독립 DB 판정을 유지하고 초기 제안 재사용 금지 |
| 실제 lexical 검색 | 20 | PASS 19 / FAIL 1; 기존 `m6/semantic-hours` 실패 보존 |
| 보안 회귀 | 19사례 / 81테스트 | 필수 사례 PASS; 실제 모델의 공격 저항성·전체 M8 PASS 아님 |

최종 답변 전 장애를 주입해 초기 제안이 미호출 최종 결과로 기록되는 문제를 재현한 뒤 최소 수정했다.
위장한 actual lane·주입한 호출 수는 실제 pilot/baseline이나 실제 품질 성공 분자로 사용할 수 없도록 검사했다.
실제 두 모델의 생성 호출은 각각 0회이며 resolvedVersion·응답·usage는 미확보다.

원자료는 각 `build/m8-evidence/<runId>/`에 있으며 최신 pointer와 검토·재계산 절차는 위와 같다.
검증 로그·lane별 run/digest 연결·재계산 비교표는 owner 전용 ACL을 적용·확인한
`build/m8-evidence/verification-180-89b3959c-3bf8-49b1-9f93-99c1a02721f3/`의
`local-summary.json`, `recalculation-comparison.json`과 로그에 보관했다. 보존 기간은 90일이며 Git에 추적하지 않는다.
실제 모델 사전 실험·본 검증·품질·회귀폭·안정성·모델 보안 판정은 계속 INCOMPLETE다.

## PR #189 리뷰 수정 검증

검증 기준은 `0877ba00c411344a8bab2df1e78446b75213a209`와 이번 평가기·테스트 수정이다.
실제 모델 실행은 하지 않았으며 아래 PASS는 코드 회귀 검증이다. 기존 v1 사례·oracle·holdout·criteria와
production Runtime·Router·Product 코드는 수정하지 않았다.

수정 전 합성 원자료 테스트에서 자동 검사 PASS인 적격 QUALIFICATION cell의 최종 판정이 INCOMPLETE가 되고,
동일 실행의 pilot/baseline 참조가 허용되는 두 finding을 재현했다. 수정 후에는 FROZEN cell의 PASS,
PILOT/PENDING·미호출·usage/원자료/검토 누락의 INCOMPLETE, oracle/안전성/검토 거부의 FAIL,
criteria snapshot 불일치 거부, 중복 runId/digest 차단과 설정 비교·무결성 경계를 검증했다.
criteria 버전 전환 충돌은 현재 소스에서 재현되지 않았으며, 실행 설정 전체가 비교에서 제외되던 점을 보완했다.

Java 25, disposable Docker와 `.\gradlew.bat --offline`을 사용했다.

| 명령·검사 | 결과 |
| --- | --- |
| `test --tests 'com.slotq.evaluation.*' --tests 'com.slotq.ai.router.*Tests' --tests 'com.slotq.ai.runtime.*Tests' m8RuntimeEvaluation m8SecurityRegression validateM8Fixtures` | 평가기/Router/Runtime 316 + 연결 3 + 보안 81 = 400건, 실패·오류·skip 0; fixture 검증 통과 |
| `test` | 1,221건, 실패·오류 0, skip 12; BUILD SUCCESSFUL |
| `clean build` | 1,221건, 실패·오류 0, skip 12; BUILD SUCCESSFUL |
| 별도 JVM의 `m8Recalculate` 4회 | 최종 채점 벡터·Runtime·답변 전 장애·lexical run의 results.json/summary.ko.md 8개 파일 SHA-256이 각각 원본과 일치 |

두 전체 검증은 각각 1,209건을 실행해 통과했다. skip 12건에는 기존 진단·Kafka 원자료/보안 설정,
local embedding 비교와 실제 provider 두 경로가 포함된다. skip을 성공으로 세지 않았다.
기존 자동 판정은 채점 벡터 58 PASS/2 INCOMPLETE, Runtime 6 PASS/1 INCOMPLETE,
답변 전 장애 3 PASS/4 INCOMPLETE, lexical 19 PASS/1 FAIL을 유지했다.
결정적 보안 19사례는 PASS이며 전체 M8 및 실제 모델 검증은 INCOMPLETE다.

원자료·JUnit·로그·run/digest 연결·재계산 비교는 실행자 전용 ACL을 적용하고 확인한
`build/m8-evidence/review-189-3347e118-117c-4137-bf35-245a6ad4fff3/`의 `local-summary.json`,
`full-validation.json`, `recalculation-comparison.json`, `protection.json`과 실행별 파일에 보관했다.
원본 JUnit과 보호 복사본도 접근 권한을 확인했으며 보존 기간은 90일이다. 재계산 명령·원본 보존 절차는 위와 같다.

두 Gemini 모델의 외부 호출은 각각 0회다. 실제 pilot·별도 baseline·승인된 기준·독립 본 검증·의미 검토와
실제 모델 보안 coverage는 #180 실행·검토 담당의 후속 조건으로 남으며, #180은 INCOMPLETE를 유지한다.
#185에는 코드 회귀 결과와 이 제한·재검증 조건을 전달한다. 이번 수정은 출시 판정을 수행하지 않는다.

## 실행 설정 비교 계약 수정 검증

검토 기준은 `949ed7a41b251f3f681e0b1e6053c769fbb19166`이며, 검증은 해당 revision의 수정 작업 트리에서
수행했다. 호출 상한·DB 포트·HTTPS 포트 각각과 이들을 함께 변경한 4개 사례가 수정 전
`baseline/pilot profile is not directly comparable`로 거부됐다. 별도 snapshot을 생성·봉인하고 pilot과
baseline을 재계산한 테스트에서도 호출 상한 차이로 같은 거부를 재현했다.
criteria 버전 전환 자체는 기존에 허용됐으며 이번 오류의 원인이 아니다.

`M8ExecutionComparisonTests` 39건은 허용되는 실행별 차이, 동등성 조건 누락, DB/image·TLS/CORS·권한·
Runtime·dataset/oracle·시각/규모 변경의 거부, 원본 snapshot 변조, 예산 미충족의 INCOMPLETE를 검사한다.
기존 합성 archive 생성 부분만 재사용해 독립 run의 snapshot → 참조 재계산 → 승인/적용 순서 확인 →
비교 → 동일 원자료 재계산을 검증했다. 이 합성 원자료는 실제 provider pilot/baseline/qualification이 아니다.

Java 25와 disposable Docker를 사용했고 실제 provider opt-in은 설정하지 않았다.

| 명령·검사 | 결과 |
| --- | --- |
| `.\gradlew.bat --offline test --tests 'com.slotq.evaluation.*' --tests 'com.slotq.ai.router.*Tests' --tests 'com.slotq.ai.runtime.*Tests' m8RuntimeEvaluation m8SecurityRegression validateM8Fixtures` | 355 + 3 + 81 = 439건, 실패·오류·skip 0; 필수 보안 19사례와 fixture 검증 통과 |
| `.\gradlew.bat --offline test` | 1,260건, 실패·오류 0, skip 12; BUILD SUCCESSFUL |
| `.\gradlew.bat --offline clean build` | 1,260건, 실패·오류 0, skip 12; BUILD SUCCESSFUL |
| 별도 JVM의 `m8Recalculate` 4회 | 최종 채점 벡터·Runtime·답변 전 장애·lexical의 results.json/summary.ko.md 8개 파일이 원본과 바이트 단위 일치 |

두 전체 검증은 각각 1,248건 실행 통과이며 skip 12건의 목록도 같았다. 실제 provider 두 경로와 기존
진단·Kafka evidence/보안 설정·local embedding 비교의 skip은 성공으로 세지 않았다.
자동 판정은 채점 벡터 58 PASS/2 INCOMPLETE, Runtime 6 PASS/1 INCOMPLETE, 답변 전 장애
3 PASS/4 INCOMPLETE, lexical 19 PASS/1 FAIL을 유지했다. 기존 `m6/semantic-hours` FAIL을 보존했다.

전체 test와 clean build에서 생성한 **실제 disposable MySQL·loopback HTTPS + 결정적 provider**의 독립
Runtime snapshot도 대조했다. manifest에서 달라진 필드는 `environment.dbHost`, `ingress.mcpOrigin`,
`ingress.productOrigin`뿐이었다. 원본 `configDigest`는 달랐고 `executionConfigDigest`는 같았다.
이 환경 확인을 실제 모델 증거로 합산하지 않는다. 호스트 측정·시각·규모가 달라졌을 때의 동등성은
여전히 입증하지 않았으므로 상단 표대로 직접 비교를 거부한다.

원자료·재현 실패·JUnit·로그·run/digest·재계산 비교와 독립 Runtime snapshot 대조는
`build/m8-evidence/review-189-comparison-6b61d087-20a2-4186-b5fc-e5c42ffe9d4d/`에 보관했다.
`local-summary.json`, `full-validation.json`, `recalculation-comparison.json`,
`independent-runtime-comparison.json`, `protection.json`에서 연결을 확인할 수 있다.
실행자 전용 ACL을 적용·확인했고 보존 기간은 90일이다. 과거 봉인 자료는 수정하지 않았다.

외부 Gemini 호출은 두 모델 각각 0회이며 실제 usage·resolvedVersion·모델 품질/안정성 증거는 없다.
#180은 INCOMPLETE다. 실행·검토 담당의 실제 pilot·독립 baseline·기준 확정·본 검증·필수 검토와
실제 모델 보안 coverage가 후속 조건이다. #185에는 코드 회귀 근거와 이 제한을 전달하며 GO 판정은 하지 않는다.
