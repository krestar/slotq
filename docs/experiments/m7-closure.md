# M7 최종 통합 검증

[#157](https://github.com/krestar/slotq/issues/157)의 검증 기준은 fetch로 확인한
`415e01c8a4a0fe03b2266ba785ef995ca584f5c7`이다. Issue 본문의 historical 기준 revision을 사용하지 않았다.
검증 대상 production source와 frozen fixture/config는 이 main과 같으며, 이번 변경은 연결 경합 회귀,
evidence 대조 도구와 문서 보완이다. 실제 호출 manifest의 source revision과 소스 digest를 현재 파일에 대조한다.

최종 판정은 **현재 bounded synthetic/process-local profile에서 M7 종료 가능**이다.
Roadmap 네 기준에 fresh actual-provider 및 deterministic/injected direct evidence가 있고,
전체 Backend `test`와 최종 `clean build`가 통과했다. 미해결 M7 security/correctness blocker는 없다.
작은 pool 구성의 P2 availability 제한과 restart·fallback·disclosure 한계는 아래처럼 명시적으로 남긴다.
이는 production release/SLA 승인이 아니다.

## 계약과 선행 결과

| Primary owner | 병합 근거 | 최종 계약 |
| --- | --- | --- |
| #151 | PR #158 / `03a88be` | [M7 Architecture SSOT](../architecture/m7-model-router-agent-runtime.md) |
| #152 / corrective #165 | PR #159 / `7c51b17`, PR #167 / `8a0bbe8` | [ADR-0010](../adr/0010-m7-bounded-model-router.md), [v3 보정 비교](m7-router-corrective.md) |
| #153 | PR #160 / `d9bcb32` | [ADR-0011](../adr/0011-m7-process-local-agent-runtime.md) |
| #154 | PR #164 / `6650ee9` | Owner/assigned Manager 전용 Product 운영 관측 조회 |
| #155 | PR #168 / `fcab654` | [동일 production Runtime의 세 flow](m7-representative-flows.md) |
| #156 | PR #169 / `415e01c` | [현재 profile의 durable 불필요 판정](m7-execution-lifetime.md) |

ADR-0009의 Auth-owned current authority·Product HTTP·single-live-instance 경계와 ADR-0005/0006의
HOLD atomicity·effective occupancy·Slot locking·commandNow 계약을 보존한다.
README의 #155 미완료/#156 미판정과 ADR register의 현재 Management/Ops unavailable 서술만 바로잡았다.
#152 당시의 no-candidate와 실패 기록은 historical evidence로 보존한다.

## Roadmap closure matrix

아래 source revision은 모두 `415e01c8a4a0fe03b2266ba785ef995ca584f5c7`이며, 새 회귀는 이 PR의 test source다.
Actual comparison과 실제 MCP/Product flow는 별도 표본이다. Injected/deterministic regression을 실제 모델 성공 수에 더하지 않는다.

| Roadmap criterion | Primary Issue owner | Final contract | Source revision | Executed test/experiment | Actual provider/model/config revision | Result | Known limitation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| cost·latency·quality·security routing 재현 | #152 / #165 | ADR-0010, quality ≥ 0.85·safety 0·disclosure/capability/measurement hard gate, weights 0.60/0.30/0.10·ID tie-break | 위 main, Router/provider/config digest 대조 | `ModelRouterTests`, `CorrectiveComparisonTests`, `ComparisonOracleTests`; fresh v3 원본·전체 내용 검토·summary/routing 재계산 | Gemini Developer API, 두 exact Flash-Lite 모델, `m7-comparison-v3` / `m7-workload-prompt-v3` / `m7-proposal-v2` / `m7-http-v2` | fresh 96회 비교·전체 검토·replay 완료; 세 업무 3.5 적격·선택 | 작은 합성 표본이며 production quality/SLA가 아님; no-training·region·retention 요구 입력은 부적격 |
| fallback·provider failure 명시 정책 | #152 / #153 | 모든 failure terminal, same-provider retry·alternate-model·cross-provider fallback 미지원 | 위 main / ADR-0010·0011 | `ProviderAdapterTests`, `AgentRuntimeTests`, `RepresentativeFlowIntegrationTests`; 실제 실패와 주입 실패 구분 | 같은 adapter/API/config, JVM HTTP/1.1·redirect NEVER·attempt 1 | 429·503·auth/config·quota·malformed·refusal·timeout 분류는 deterministic/injected 검증, 실제 관측은 아래 별도 기록 | actual fallback은 unsupported로 실행하지 않음; remote timeout은 원격 종료·무료 실행의 증거가 아님 |
| budget·timeout·retry·cancel·confirmation 강제 | #153 / #156, M6 Auth/Product owner | Exact immutable origin/current authority, 누적 예산·absolute deadline, original Actor 승인, unknown 보존, process-local | 위 main / ADR-0011 | `AgentRuntimeTests`, `ProductToolsIntegrationTests`, `RepresentativeFlowIntegrationTests`, `ProductDatabaseBoundsIntegrationTests`, architecture/security 회귀 | fresh Customer proposal/answer는 실제 모델; 취소·장애·JVM 종료는 deterministic/injected | 아래 Backend 결과와 Pool 재현 참조 | restart Run/budget/result/cancel continuity·remote stop 보장 없음; same-intent retry도 명시적 current authority/유효 window 필요 |
| Customer·Owner/Manager·Ops의 공통 Runtime | #154 / #155 | 같은 `AgentRuntime`·registry·MCP/Product HTTPS·Knowledge, Product 사실과 안내·관측 한계 분리 | 위 main / flow context/profile digest 대조 | fresh Customer·Owner Management·Manager Ops; flow별·세트 재계산 및 known exact GET; Owner/Manager positive·Staff negative는 deterministic 회귀 | `gemini-3.5-flash-lite`, `m7-integrated-flow-evidence-v6` / `m7-flow-context-v6` / `representative-router-v1.json` | fresh 4/4 실제 응답·oracle·내용 검토 통과, 세 flow `complete=true` | synthetic/demo data만 허용; Staff의 M6 예약 조회와 M7 operator Run 권한은 다름; recovery mutation 미제공 |

## Fresh actual-provider evidence

사용자는 2026-10-15에 기존 계정이 현재 무료 tier·billing 비활성화 상태임을 확인했다.
같은 날 [공식 가격](https://ai.google.dev/gemini-api/docs/pricing),
[약관](https://ai.google.dev/gemini-api/terms), [quota 안내](https://ai.google.dev/gemini-api/docs/rate-limits)를 확인했다.
모델 정보 GET은 두 exact 모델의 `generateContent`, 입력 1,048,576·출력 65,536 token을 확인했다.
Metadata version은 각각 `3.5-flash-lite-07-2026`, `3.1-flash-lite-05-2026`이다.
생성 응답의 `modelVersion`과 내부 가중치 고정을 혼동하지 않는다.

무료 입력·출력 가격 snapshot `gemini-free-standard-2026-10-13`은 유지하면서 현재 가격이 같은지 재확인했다.
계정별 남은 quota 수치는 unavailable이며, 사용자의 무료 접근 확인과 실제 모델 정보 조회·생성 결과를 기록했다.
계정/quota/config 거부가 발생하면 비교를 중단하는 기존 하네스 정책을 유지했다.
측정 token·latency, 확인한 가격으로 계산한 estimated USD 0, usage 미확보의 unavailable을 구분한다.
청구서와 effective region/retention은 측정하지 않았다. 제품 개선·사람의 검토가 가능한 조건에서
민감정보 없는 frozen synthetic fixture와 disposable demo 자료만 전송했다. Credential은 출력·evidence에 기록하지 않는다.

### 두 모델 비교

새 전체 실행은 `build/model-router-closure-resumed-20261015/`다.
기존 v3의 primary 12 case와 독립 holdout 4 case를 두 모델에 각각 3회 적용하고,
사전 oracle·내용 검토 정책·hard gate·request/schema·96회/45분/USD 0/호출 간 7초 한도를 유지한다.
전체 응답/실패 검토 후 원본에서 summary와 snapshot routing을 다시 계산한다.
실행은 25분 20초에 완료됐다. **96회는 비교 시도 수이며 성공 응답 수가 아니다.**
구조화 응답 78회와 실제 실패 18회를 모두 검토한 뒤 재계산·snapshot replay를 통과했다.

| 업무 | 모델 | Primary oracle | Holdout oracle·내용 검토 | Safety | p95 (ms) | 확인한 total token / usage unavailable | 현재 적격성 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| Customer | 3.5 Flash-Lite | 12/12 | 해당 없음 | 0 | 3,866 | 6,435 / 0회 | 선택, score 0.961340 |
| Customer | 3.1 Flash-Lite | 9/12 | 해당 없음 | 0 | 45,000 | 4,840 / 3회 | 제외 |
| Management | 3.5 Flash-Lite | 12/12 | 6/6 | 0 | 5,596 | 11,009 / 0회 | 선택, score 0.944040 |
| Management | 3.1 Flash-Lite | 9/12 | 1/6 | 0 | 45,000 | 6,173 / 8회 | 제외 |
| Ops | 3.5 Flash-Lite | 12/12 | 6/6 | 0 | 5,969 | 12,296 / 0회 | 선택, score 0.940310 |
| Ops | 3.1 Flash-Lite | 6/12 | 5/6 | 0 | 44,999 | 7,871 / 7회 | 제외 |

3.5는 primary 36/36·holdout 12/12, safety 0이며 세 업무 모두 유일한 적격 후보다.
3.1 Customer는 timeout 3회, Management는 timeout 1회·실제 503 7회,
Ops는 timeout 2회·실제 503 5회다. 전부 한 시도 뒤 terminal이며 후속 독립 case를 retry/fallback으로 세지 않는다.
3.1의 실제 workload별 비용은 unavailable이고, Router exclusion은
`measurement_missing_invalid_or_unavailable`다. Primary 품질과 holdout gate도 충족하지 못했으나
불명 사용량을 0으로 바꿔 score를 계산하지 않았다.

확인한 사용량은 48,624 token, 그 부분의 비용 소계는 estimated USD 0이다.
18회 실패를 포함한 전체 사용량·비용은 unavailable이며 실제 청구액을 측정하지 않았다.
선택된 3.5의 48회 사용량·비용은 모두 measured token / estimated USD 0이다.
응답에 없는 thinking token은 null을 유지한다. 실제 429·auth/config/account quota·policy refusal·malformed는
이번 실행에서 미관측이다. 그 분류·terminal 동작은 별도 deterministic/injected 회귀 결과다.
Safety 0은 고정된 fact/authority/outcome 기준이다. 일부 Ops 설명의 “배송/영수증” 같은 어색한 번역 표현까지
일반적인 한국어 품질 통과로 확대하지 않는다. 해당 응답도 DONE/null/truncated와 전체 완료 불확실성은 유지했다.

중단된 `build/model-router-closure-20261015/`의 31개 관측은 별도 보존했다.
이 세트를 완료된 96회 비교에 합산하지 않는다. 31개 전체 응답을 검토했으며,
3.1 Customer 정상 case의 두 번째 응답이 근거 없이 예약을 “주차 예약”이라고 설명한 내용을
`unsupported_claim`으로 남겼다. 중단 당시 진행 중이었을 수 있는 다음 호출의 remote completion/usage는 추정하지 않는다.

### 실제 통합 flow

실험 세트는 `build/representative-flow-closure-20261015/`다. 첫 실행 전에 고정한 experiment identity에
Customer·Management·Ops가 모두 일치한다. 중단 전에 완료된 Customer/Management와 재개 뒤 Ops는
같은 revision·13개 소스 digest·profile/context/schema/HTTP 설정을 사용하며 이전 Issue의 성공을 가져오지 않았다.

| Flow | 실제 응답 / 필수 case | Provider latency (ms) | 측정 total token | 마지막 Run duration / approval wait (ms) | 결과 |
| --- | ---: | --- | ---: | --- | --- |
| Customer proposal → original approval → HOLD → answer | 2/2 | 1,533 / 1,065 | 1,487 | 12,045 / 118 | fresh complete, 실제 HOLD·별도 known GET HELD 확인 |
| Owner Management | 1/1 | 2,207 | 839 | 4,205 / 0 | fresh complete, Product 목록과 Knowledge reference 구분 |
| Manager Ops | 1/1 | 2,196 | 1,342 | 3,480 / 0 | fresh complete, projected window·지연·PENDING·null·truncated 보존 |

네 실제 호출 모두 HTTP 200·사용량 measured·비용 estimated USD 0, failure/usage unavailable 0이다.
전체 자연어 답변을 검토하고 request/answer digest와 연결한 뒤 flow별·세트 전체 재계산을 통과했다.
Customer의 모델 성공을 HOLD 성공 근거로 사용하지 않았다. 승인·HOLD 결과·원래 Run budget/deadline과
동일 intent continuity는 recorded Runtime와 실제 MySQL 상태로 확인하고, exact GET은 별도 현재 관측으로 남겼다.
승인은 synthetic original Actor credential을 사용하는 controlled adapter handoff다.
표의 짧은 approval wait를 실제 사용자의 승인 UX나 대기 시간 측정으로 해석하지 않는다.

## Pool starvation 가설

가설은 실제로 재현된다. 범위는 **작은 pool의 중첩 acquisition에 따른 bounded availability 저하**다.
Hikari 기본 30초 대기나 무한 교착, capacity invariant 위반, 부분 commit의 근거는 발견하지 않았다.
`REQUIRES_NEW`를 제거하거나 current authority를 완화하지 않는다.

`ProductDatabaseBoundsIntegrationTests.nestedCurrentAdmissionKeepsOuterConnectionsAndFailsClosedWithoutASpare`
는 실제 MySQL 8.4·Spring `JpaTransactionManager`·Hikari를 사용한다. 서로 다른 Slot에 exact material-bound
PRODUCT credential과 key를 가진 세 유효한 HOLD를 보낸다. 외부에서 pool 연결을 빌려 소진하지 않는다.
테스트 gate는 첫 두 요청의 실제 Slot lock 획득 뒤와 세 번째 요청의 첫 admission 직전에서 멈춘다.
각 Product transaction의 `CONNECTION_ID()`가 서로 다르고 첫/두 번째 admission 전후 동일함을 확인한다.
세 번째 첫 admission에서는 관측용 SELECT를 실행하기 전부터 active connection이 3개임도 확인한다.
따라서 진단 SQL이 지연된 outer acquisition을 강제로 유발해 만든 현상으로 해석하지 않는다.

| 관측 | Pool 3 | Pool 4 |
| --- | --- | --- |
| Outer transaction 물리 연결 | 세 개 모두 점유; 첫 두 요청은 다른 Slot lock 보유 | 동일한 세 요청·동일한 gate |
| Gate 해제 뒤 Auth admission | 첫 두 요청은 두 번째 admission, 세 번째는 첫 admission에서 추가 연결 요청; active 3 / awaiting 3 확인 | 여유 연결 하나로 current Auth transaction 진행 |
| 응답 | 세 요청 HTTP 503 / `AccessFailure.UNAVAILABLE`, business capacity rejection 아님 | 세 요청 HTTP 201 |
| Reservation / Allocation / idempotency | 모든 대상 0/0/0 | 대상마다 1/1/1 |
| 회복 | active/awaiting 0 확인, 이후 일반 HOLD 201·1/1/1 | active/awaiting 0 확인 |

최초 acquisition 실패 뒤 outer rollback을 다른 실패 관측까지 잠시 gate하여 모든 대기의 종료를 독립 확인한다.
따라서 모든 자연 발생 경합이 세 요청 전부 실패한다는 빈도·부하 주장은 하지 않는다.
한 요청이 먼저 rollback하면 반환된 연결로 나머지가 진행할 수도 있다.
각 실패 acquisition은 1.5~4초 범위, 세 HTTP 응답은 fixture/gate 시간을 포함해 10초 미만을 검증한다.
전체 `test`에서 세 acquisition은 각각 2,002ms, gate 준비를 포함한 세 응답은 2,070ms였다.
Pool 4의 세 응답은 103ms였다. 이는 해당 테스트의 작은 표본이며 일반 성능 수치가 아니다.

기존 `poolExhaustionRejectsAtAuthorityAcquisitionThenRecoversWithoutBusinessEffect`는 모든 연결을
테스트가 빌린 뒤 Product 인증 입구의 실패·회복을 확인한다. Slot 잠금 뒤 nested 대기는 이번 새 회귀가 다룬다.
외부 transaction suspension은 Hikari 연결 반환이 아니다. 별도 current Auth transaction이 추가 연결을 필요로 한다.

현재 write activation은 acquisition/socket/connect를 각각 최대 2초, transaction을 최대 10초,
Auth transaction/query를 2초, MySQL lock wait를 최대 2초로 제한한다. 기본 MCP ingress/worker는 각 8,
quota concurrency는 4다. `LocalAdmission`의 tool/resource bucket도 공유되므로 같은 HOLD tool을
호출하는 여러 Actor가 quota를 우회해 worker 수까지 항상 실행되는 것은 아니다.
이 한도와 pool size의 여유를 연결하는 자동 검증은 없고, direct Product traffic은 MCP ingress로 제한되지 않는다.

심각도는 pool 여유가 없는 구성의 **P2 availability 문제**로 분류한다. 이 재현은 oversubscribed pool에서
명시적으로 허용된 bounded unavailable 종료이며, 현재 M7 correctness/security 종료 blocker로 판정하지 않는다.
정상 운영 구성의 supported workload에서도 지속되는 중요한 availability 결함이라는 근거는 아직 없다.
Supported throughput·전체 Product/Auth 동시 부하의 capacity qualification은
미실행이다. 계약의 primary owner는 M6 [#131](https://github.com/krestar/slotq/issues/131)의 Auth/MCP 접근 통제와
[#132](https://github.com/krestar/slotq/issues/132)의 Product invocation 경계다. 최소 후속 방향은 current Auth transaction을
보존한 pool/동시성 여유 조정과 다른 트래픽까지 포함한 M8 capacity qualification이다.
단순 pool 숫자 확대를 SLA 보장으로 해석하지 않는다. 보안·transaction 정책 변경이 필요해지면 별도 corrective다.

테스트에서 authoritative DB rollback을 확인한 사실과 MCP 호출자의 관측은 다르다.
`ProductTools.result`는 Product 5xx/transport loss를 `outcome_unknown`으로 보존하며,
Runtime은 이를 business rejection/rollback으로 축소하지 않는다. 기존 실제 DB outage·COMMIT 후 response loss,
known/unknown target·explicit same-intent retry 회귀를 함께 실행한다.

## Backend 검증과 보존

JDK 25·Docker/MySQL을 사용한다. 일반 전체 suite는 외부 provider credential/network를 사용하지 않는다.
Frontend는 변경하지 않아 검증 대상에서 제외했다. 중단된 전체 `test`는 성공으로 세지 않고 다시 실행했다.
전체 `test`는 24분 25초에 통과했다. 110 suite / 962개 중 951개 실행, 실패·오류 0, 기존 opt-in skip 11개다.
Skip은 Booking 진단 2개, Kafka opt-in 6개, M6 실제 검색 비교 1개, Waitlist 진단 1개,
이번 일반 suite에서 비활성화한 actual-provider flow 1개다. 마지막 항목은 위 별도 opt-in 실행에서 실제로 검증했다.
다른 미실행 항목을 성공으로 계산하지 않는다.
이후 연결 ID 관측 SQL 이전의 물리 연결 점유 assertion을 보강하고 해당 MySQL 회귀 7개를 다시 실행해
실패·오류·skip 0으로 통과했다(54초). Pool 3의 세 acquisition은 각각 2,001ms,
세 응답은 2,114ms였고 pool 4의 세 응답은 136ms였다. 진행 중이던 첫 `clean build`는 이 보강을 위해
중단했으며 성공으로 세지 않는다. 최종 `clean build`는 보강한 전체 소스에서 다시 실행했다.
최종 `clean build`는 24분 24초에 통과했다. 110 suite / 962개 중 951개 실행,
실패·오류 0, 동일한 기존 opt-in skip 11개다. 배포 jar의 production Router profile 포함과
테스트 하네스·raw evidence 제외를 확인했다. Provider 비교 7개와 flow별 13개 실행 소스 digest도 현재 파일과 일치한다.
기존 #156의 16개 latency cohort 재계산도 다시 통과했으며 fresh provider evidence와 분리했다.

외부 리뷰의 나머지 가설은 다음 기존 계약·회귀에 대조한다. Slot별 활성 점유는 단순 UNIQUE 대신
Slot row lock과 effective occupancy predicate로 직렬화하며 `ReservationHoldIntegrationTests`,
`BookingCapacityLockEvidenceTests`, `ReservationConfirmExpiryIntegrationTests`의 동일 Slot 경합·만료·replacement
HOLD/CONFIRM ordering을 확인한다. `ReservationTransitionIntegrationTests`와 idempotency scope 회귀도 유지한다.
ADR-0006 및 #70/#88의 lock 이전 commandNow는 재캡처하지 않는다. 신규 주체별 HOLD 남용 정책과
multi-instance quota는 현재 인증·MCP quota의 검증과 분리하며 이번 종료 조건으로 선도입하지 않는다.

```powershell
Set-Location backend
.\gradlew.bat test
.\gradlew.bat clean build
```

실제 호출을 재현할 때는 그 시점의 계정·무료 quota·모델·약관을 재확인하고 새 ignored 디렉터리를 지정한다.
기존 output을 덮어쓰거나 성공 디렉터리만 골라 합산하지 않는다.

```powershell
.\gradlew.bat modelComparison -PmodelComparisonProfile=corrective-v3 -PmodelComparisonOutput=model-router-closure-resumed-20261015 -PmodelComparisonOptIn=true -PmodelComparisonFreeTier=true -PmodelComparisonPaidCalls=false
foreach ($flow in @('customer','management','ops')) {
    .\gradlew.bat representativeFlowEvidence -PflowEvidenceOutput=representative-flow-closure-20261015 "-PflowEvidenceFlow=$flow" -PflowEvidenceOptIn=true -PflowEvidenceFreeTier=true -PflowEvidencePaidCalls=false
}
# 전체 응답 검토 후 각 output의 content-review.json 작성
.\gradlew.bat modelComparison -PmodelComparisonProfile=corrective-v3 -PmodelComparisonOutput=model-router-closure-resumed-20261015 -PmodelComparisonRecalculate=true
foreach ($flow in @('customer','management','ops')) {
    .\gradlew.bat representativeFlowRecalculate -PflowEvidenceOutput=representative-flow-closure-20261015 "-PflowEvidenceFlow=$flow"
}
.\gradlew.bat representativeFlowRecalculate -PflowEvidenceOutput=representative-flow-closure-20261015
Set-Location ..
python infra/experiments/m7_closure_summary.py --comparison model-router-closure-resumed-20261015 --flows representative-flow-closure-20261015
```

재계산 도구는 Java oracle/review/routing 검증 뒤 실행한 actual source와 현재 파일 digest, 계산기 identity,
검토/raw hash, 여섯 model/workload summary와 세 complete flow를 대조한다. 자연어 의미 검토를 자동 대체하지 않는다.
Raw JSON/CSV·전체 모델 응답·source hash 목록·로그·XML·소스 사본은 ignored `build/`에 둔다.
Git에는 재현 가능한 회귀·계산 코드·기존 frozen manifest/config와 간결한 결과만 남긴다.

## 종료 범위와 후속 ownership

#156의 bounded process-local durable 불필요 판정을 반박할 새 evidence는 없다. Full suite의
confirmation wait/re-entry·포화·취소·late completion·JVM 종료/재시작 회귀와 이번 fresh flow의
유한 duration을 대조한다. Known target은 현재 권한으로 새 read Run의 exact GET을 명시적으로 실행하며,
unknown target은 unknown으로 남긴다. 재시작 후 old Run/approval을 복원하거나 자동 fresh-key HOLD를 하지 않는다.

M8은 generic evaluation dataset/threshold history/release gate, production load/security/SLA·HA·backup/restore
qualification을 소유한다. Multi-instance/shared quota, 새 provider/disclosure class·fallback, restart continuation은
해당 primary owner의 새 근거와 결정이 먼저 필요하다. Durable positive decision이면 별도 M7-D가 선행한다.
Product HOLD 남용의 새 주체별 정책, Slot UNIQUE 대체 설계, commandNow 재캡처, Redis/Kafka queue는 추가하지 않았다.
