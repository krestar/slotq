# M8 실제 모델 반복 평가 연결

[#180](https://github.com/krestar/slotq/issues/180)의 테스트 전용 수집 연결과 반복 결과표다.
[#179 평가기](m8-evaluator.md)의 계획 cell, 보호 원자료, 다섯 축 채점, 의미 검토와 재계산을
재사용한다. [공통 계약](../architecture/m8-evaluation-contract.md)과
[#184 보안 사례](m8-security-regression.md)의 강제 기준은 변경하지 않는다.

현재 #180의 판정은 **INCOMPLETE**다. 이 변경에서 외부 모델 호출은 승인받거나 실행하지 않았다.
새 사전 실험, 품질·회귀폭·표본·반복·안정성 기준 승인, 독립 본 검증과 답변 의미 검토가 없다.
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
   원본이 봉인된 pilot·baseline을 현재 scorer로 재계산할 수 있어야 한다. 실패 run을 교체 baseline으로 사용하지 않는다.
6. rubric의 **모든 필수 항목**을 request/answer digest에 연결해 검토하고, 같은 원자료·검토·scorer·criteria로 재계산한다.
   답변 원문은 보호된 `request/answer/provider-call-*` artifact에서만 검토한다.
   미검토·digest 불일치는 INCOMPLETE, 확인한 의미 위반은 FAIL이다.

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
| 실제 생성 호출·usage | 두 모델 모두 0회. 호출 승인·프로젝트 무료 여부·quota 확인 후 #180 실행 담당이 새 pilot 확보 |
| 사전 기준·baseline | PENDING. #180에서 실제 pilot 변동·실패와 전체 rubric 검토를 근거로 새 버전 승인 |
| 본 검증·안정성 | 미실행. 실제 연결 가능한 필수 경로·표본 확장과 독립 holdout을 먼저 확정 |
| 보안 | #184 결정적 suite와 실제 모델 평가 분리. 공격성 입력의 실제 모델+서버 coverage·필수 관측은 #180에서 추가 확보 |
| 출시 판단 | #185 담당. 현재 INCOMPLETE와 blocker를 전달하며 완료 조건 체크·GO 선언 금지 |

#185에는 실행별 계획·source/build·지원 구성·dataset/oracle/prompt/schema/scorer/criteria·Router/security catalog digest,
lane별 봉인 digest·외부 보관 위치·검토 digest, 결과표, 실패·누락 분포와 재계산 동일성 자료를 전달한다.
모델/API·전송 조건·데이터셋·oracle·prompt·schema·scorer·Runtime/Router/권한·빌드 구성이 바뀌거나
원자료/검토를 사용할 수 없으면 비교 가능성을 다시 확인하고 해당 경로를 새 run으로 검증한다.
과거 결과를 새 구성의 PASS로 옮기지 않는다. #181~#183의 부하·장애·복원은 별도 담당 범위다.

## 이번 로컬 검증 결과

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
