# 공통 Runtime의 세 업무 흐름 검증

[#155](https://github.com/krestar/slotq/issues/155)의 fresh actual-provider 조건을 충족했다.
Customer·Management·Ops를 독립 실행하고 네 실제 호출의 전체 내용 검토와 재계산을 완료했다.
각 flow는 `complete=true`이며 이전 revision의 성공이나 주입 장애를 합산하지 않았다.

구현 기준은 `main`의 `8a0bbe8`이고, 이번 보완은 PR #168의 `23fecea`에서 시작했다.
계약은 [M7 Architecture](../architecture/m7-model-router-agent-runtime.md)와
[ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)을 따른다.

## 최신 실제 실행 결과

실험 세트는 `representative-flow-independent-20261014`, 명세는
`m7-integrated-flow-evidence-v6`, context는 기존 `m7-flow-context-v6`다.
Customer·Owner Management·배정된 Manager Ops를 각각 한 번 실행했다.

| Flow | 통과 / 필수 case | 요청 body | Provider latency | 측정 usage |
| --- | --- | --- | --- | --- |
| Customer | 2/2 | 2,959 / 3,445 byte | 1,488 / 1,809 ms | 684 + 913 = 1,597 token |
| Owner Management | 1/1 | 3,526 byte | 1,435 ms | 886 token |
| Manager Ops | 1/1 | 4,791 byte | 2,070 ms | 1,370 token |

네 호출 모두 HTTP 200이다. 각 flow의 bounded quality는 1.0, 안전성 위반과 usage unavailable은 0이다.
승인된 무료 tier의 비용은 estimated USD 0이며 실제 청구서는 측정하지 않았다.

Customer는 proposal → original Actor의 review·명시적 승인 → 실제 HOLD → answer를 같은 Run에서
완료했다. 답변은 Product 상태·인원수·시각을 유지하고 HELD를 임시 확보로 설명했다.
HOLD 뒤 exact GET에서도 HELD를 확인했다. 이 GET은 추가 provider 생성으로 계산하지 않는다.
Run duration은 12,973 ms, approval wait는 154 ms이며 원래 admission·deadline을 유지했다.

Management는 Product 예약 사실과 operator Knowledge 안내를 구분했다.
Ops는 projected window, 발생·투영 시각과 지연, PENDING 전달 상태, null 수신 기록,
잘림과 범위 한계를 설명했다. 두 Run의 duration은 각각 3,687/3,596 ms다.

실행 시작 revision은 `23fecea`다. 수정된 하네스의 소스 사본·digest를 보존했다.
9개 production 소스와 context·profile·schema·생성 설정·HTTP controls는 이전 실행과 동일하다.
전체 내용 검토, 각 flow 독립 재계산과 세트 전체 재계산이 모두 통과했다.

## 독립 실행과 재계산

#155는 flow마다 최소 하나의 fresh 실행을 요구한다. 세 flow를 한 연속 실행에서 모두 성공시킬
필요는 없다. `flowEvidenceFlow=customer|management|ops`로 하나씩 선택하며,
Customer의 두 모델 호출과 승인·HOLD는 같은 Run으로 유지한다.

첫 호출 전에 `experiment.json`에 전체 명세와 소스·profile·context·schema·HTTP 설정을 고정한다.
다음 flow는 같은 identity여야 시작하며, 기존 flow manifest는 덮어쓸 수 없다.
새 실험 세트에서는 모든 flow를 새로 검증한다. 다른 revision의 성공을 가져와 완료할 수 없다.

Flow별 재계산은 다른 flow의 성공 여부에 의존하지 않는다. 전체 재계산은 고정된
`customer/`, `management/`, `ops/`를 모두 검사하고 실패·미관측도 남긴다.
성공 디렉터리를 임의 선택하는 입력은 없다.

- Adapter 진입·완료·최종 행을 대조해 실제 실패 호출과 local completion 누락을 확인한다.
- Request·schema·profile·oracle·검토 identity와 normalized/raw response를 대조하고 routing을 replay한다.
- Customer의 Run ID·plan·원래 budget/deadline·HOLD success를 proposal과 answer 사이에서 대조한다.
- 전체 검토나 필수 case·quality·usage가 부족하면 완료 판정을 거부한다. Usage unknown은 비용 0으로 채우지 않는다.

세트는 Customer 2회·Management 1회·Ops 1회로 최대 4회다. 각 flow는 최대 10분·USD 0,
출력 1,024 token, 호출 간 최소 7초, attempt 최대 45초를 유지한다.
실패한 Run은 terminal이며 자동 retry/fallback은 없다.

## 재현 방법

JDK 25와 Docker가 필요하다. `backend/`에서 targeted 회귀를 실행한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.mcp.RepresentativeFlowIntegrationTests' --tests 'com.slotq.ai.*' --tests 'com.slotq.architecture.*'
```

무료 tier 조건을 확인한 뒤 새 output 이름으로 실제 flow를 실행한다.
Key는 ignored `.env.local`의 `GEMINI_API_KEY`에서 읽으며 argument·로그·원본에 복사하지 않는다.

```powershell
$flowEvidenceOutput = 'representative-flow-new-run'
$flowEvidenceOptions = @(
    '-PflowEvidenceOptIn=true'
    '-PflowEvidenceFreeTier=true'
    '-PflowEvidencePaidCalls=false'
    "-PflowEvidenceOutput=$flowEvidenceOutput"
)
foreach ($flow in @('customer', 'management', 'ops')) {
    .\gradlew.bat representativeFlowEvidence @flowEvidenceOptions "-PflowEvidenceFlow=$flow"
}
```

각 flow의 **모든 답변과 실패**를 검토해 `m7-content-review-v1` 형식의
`content-review.json`을 작성한다. 그 뒤 flow별·세트 전체 재계산과 Backend 전체 검증을 실행한다.

```powershell
foreach ($flow in @('customer', 'management', 'ops')) {
    .\gradlew.bat representativeFlowRecalculate "-PflowEvidenceOutput=$flowEvidenceOutput" "-PflowEvidenceFlow=$flow"
}
.\gradlew.bat representativeFlowRecalculate "-PflowEvidenceOutput=$flowEvidenceOutput"
.\gradlew.bat clean build
```

산출물은 Git에서 제외한 root `build/representative-flow-*/`에 보존한다.
Git에는 하네스·명세·결과 요약만 추적한다.

| 산출물 | 내용 |
| --- | --- |
| `experiment.json`, flow별 `manifest.json`·소스 사본 | 사전 명세·13개 소스 digest·실행 조건 |
| `provider-starts.jsonl`, `provider-completions.jsonl` | Case별 adapter 진입·요청 크기·완료·실패·usage |
| `attempts.jsonl`, `known-get.json` | 전체 실제 호출·Runtime·routing·duration/wait와 별도 현재 GET |
| `content-review.json` | 전체 내용 검토와 request/answer digest·findings |
| `summary.json`, `oracle-marks.json`, `recalculation.json` | 품질·latency·usage·cost·failure와 재계산 provenance |

## 구현 범위와 회귀 검증

`RepresentativeFlows`는 기존 Auth·MCP·Product HTTP·Knowledge와 동일 production
`AgentRuntime`을 사용하는 서버 내부 client다.

| Flow | 범위와 계약 |
| --- | --- |
| Customer | exact Slot·인원수 또는 known Reservation, public Knowledge. Original Actor 승인 후 HOLD, mutation 결과·현재 GET·답변 구분, HELD를 예약 확정으로 표시하지 않음 |
| Owner·Manager | granted Venue의 현지 하루와 operator Knowledge. 읽기만 수행하며 Product 사실과 문서 안내 구분 |
| Ops | 최대 31일의 projected-at `[from,to)`, 최대 3건. 발생/투영·전달·수신·누락·잘림 구분, 관측 없음으로 사건 부재를 단정하지 않음 |

Provider에는 예약 목록 최대 3건과 반환 건수·부분 사용 여부, Knowledge의 provenance와 한 개의
untrusted excerpt만 전송한다. Tenant/Venue/Actor/customer reference와 credential/Product key는
제외하며 unknown 조회와 Ops null을 보존한다. 새 전송 전에 같은 Run budget으로 Knowledge를 갱신한다.

[#165 보정 실험](m7-router-corrective.md)의 적격·제외 후보와 측정을 그대로 사용한다.
품질 0.85·안전성 위반 0·disclosure·측정·예산 gate를 유지한다.
Synthetic 전송 승인, current authority와 provider admission은 전송 경계마다 다시 검증한다.

Runtime은 co-located production `McpEngine`을 호출하고, 같은 registry/delegation으로
HTTPS initialize/initialized/list/call도 검증한다. Operator 조회용 사전 synthetic 예약은
Customer flow 자신의 HOLD와 구분한다.

일반 테스트는 deterministic provider와 실제 Auth/MCP/Product HTTPS/MySQL/Knowledge를 사용한다.
다음 장애·보안 검사는 actual-provider 성공 기록에 합산하지 않는다.

| 회귀 | 확인한 경계 |
| --- | --- |
| Run control | 다른 Actor/Tenant/Venue, replacement·revoked·expired delegation, Staff·역할 강등, budget reset·restart resume 거부 |
| 승인·제안 | 승인 없는 HOLD·잘못된 approver/material, old intent 재사용, malicious corpus·forbidden proposal·wrong target dispatch 차단 |
| Provider | 주입한 unavailable/rate-limit/malformed/timeout은 terminal. 미지원 fallback·disclosure 철회 뒤 미호출 |
| Product/MCP unknown | 실제 COMMIT 뒤 응답 유실 주입, MySQL durable HOLD·same-intent 단일 예약·known-target GET 확인. Generic unknown과 structured Product unknown 구분 |
| 취소·답변 | Work 종료까지 permit 유지, late effect 보완. Product success 뒤 answer failure·답변 거부도 structured success 보존 |
| Context·Ops | Withdrawn Knowledge 재전송 차단, evidence/insufficient/no_answer/unavailable 구분, incomplete semantics와 읽기 전후 상태 불변 |

답변 회귀는 HELD/CONFIRMED 혼동, 관측 부재의 사건 부재 일반화,
PENDING/null의 전달 완료·수신 성공 왜곡을 차단한다. 정상 부정 표현과 실제 CONFIRMED/DONE 조회는
유지한다. 계약 용어에 한정한 검사이므로 실제 답변의 전체 내용 검토도 필요하다.

### 이번 자동 검증

- Targeted: 16 suite / 148개 중 147개 실행, actual-provider opt-in skip 1개, 실패·오류 0(1분 20초).
- 실제 실행: Customer·Management·Ops 각각 성공 exit. 네 원본의 전체 검토와 독립/전체 재계산 성공.
- Backend 전체 `clean build`: 110 suite / 950개 중 939개 실행, opt-in skip 11개, 실패·오류 0(24분 43초).
- 배포 jar의 production flow/profile 포함과 실험·credential 제외, 실제 key 미포함, 문서 링크와 `git diff --check`를 확인했다.

## 이전 실패 기록과 원인 분류

아래는 당시 revision의 전체 실행 결과다. 최신 v6 완료 근거로 합산하거나 새 identity를 붙이지 않았다.

| 기록 디렉터리 (`build/`) | 관찰 | 전체 실행 결과 |
| --- | --- | --- |
| `representative-flow-v1` | Customer 2회. Proposal·승인·HOLD 성공 뒤 answer의 잘못된 `partySize`를 MALFORMED로 거부 | 미완료. Structured Product success 보존 |
| `representative-flow-v2` | 30초 Run timeout 뒤 local completion 누락 | 미완료. Remote completion·usage/cost 확인 불가 |
| `representative-flow-v2-complete` | 1회 TIMEOUT_REMOTE_UNKNOWN, local completion 확보 | 실패. Usage/cost unavailable |
| `representative-flow-v2-operators-first` | 응답 7회·Runtime 정상 반환, 전체 검토에서 Ops 시각·receipt·잘림 설명 부족 | Ops 완료로 계산하지 않음 |
| `representative-flow-v3` | Management 2회·Ops Owner 성공, Ops Manager timeout | 부분 실행·미완료, timeout usage/cost unavailable |
| `representative-flow-v3-complete` | 첫 Management 호출 TIMEOUT_REMOTE_UNKNOWN | 실패. Usage/cost unavailable |
| `representative-flow-final` | Context v4의 첫 Management 호출 HTTP 503(2,312 ms) | 실패. Usage/cost unavailable |
| `representative-flow-final-complete` | 첫 Management 호출 TIMEOUT_REMOTE_UNKNOWN(45,002 ms) | 실패. 1개 원본 재계산, usage/cost unavailable, Customer/Ops 미관측 |
| `representative-flow-recheck-20261014` | Context v5·5-case. Management·Ops·Customer proposal/승인/HOLD/answer 성공, 추가 known-GET 생성 timeout | 전체 FAIL. Customer 2/3, cost unavailable |
| `representative-flow-required-20261014` | 4-case. Management·Customer proposal timeout, Ops 성공 | 전체 FAIL. Answer 미관측·승인/HOLD 미실행, 실패 usage/cost unavailable |
| `representative-flow-final-20261014` | Context v6·명세 v5. Management 성공(24,036 ms·814 token), Ops 503(22,284 ms), Customer proposal timeout(45,000 ms) | 전체 FAIL. Answer 미관측·승인/HOLD 미실행, 실패 usage/cost unavailable |

재계산한 이전 실패·부분 실행은 `complete=false`와 실패 exit를 유지했다.
503은 실제 HTTP 오류 응답을 adapter가 `TEMPORARY_OVERLOAD`로 분류한 결과다.
Timeout은 HTTP status 없이 끝난 `TIMEOUT_REMOTE_UNKNOWN`이며 원격 완료·사용량·비용은 미확인이다.

직전 v5의 Management/Ops/Customer proposal envelope를 재구성하면 3,526/4,791/2,959 byte다.
새 기록과 같고 adapter 상한 16,384 byte 이하다. Production adapter와 schema·생성 설정·
HTTP/1.1 no-redirect/no-retry controls도 동일하므로 크기 상한의 로컬 거부와 실제 503/timeout을 구분한다.
독립 실행은 flow마다 새 테스트 프로세스·Runtime·HttpClient·fixture를 사용한다.
이 차이를 포함한 한 세트의 비교만으로 latency 변화의 원인을 분리할 수 없다.
요청 복잡도·provider 부하·네트워크 등의 상세 원인은 확정하거나 배제할 근거가 없다.
새 호출 성공도 독립 실행 분리가 provider 장애를 해결했다는 증거는 아니다.

[공식 오류 안내](https://ai.google.dev/gemini-api/docs/troubleshooting)와 별개로 no-retry/fallback을
유지한다. Metadata GET의 HTTP 200은 generation 성공이나 timeout 원인의 증거로 사용하지 않는다.
분리 전 자동 검증은 targeted 144개, Backend `test`와 `clean build` 각각 936개 실행으로 통과했다.
이는 과거 검증 기록이며 이번 실행 결과와 구분한다.

## 데이터 조건과 후속 판단 입력

승인된 무료 tier의 `gemini-3.5-flash-lite`와 민감정보 없는 disposable synthetic/demo 데이터만 사용했다.
[공식 가격](https://ai.google.dev/gemini-api/docs/pricing)과 [이용 약관](https://ai.google.dev/gemini-api/terms)을
2026-10-14에 확인했으며 제품 개선·사람의 검토 조건을 적용한다. Effective region/retention은 unavailable이다.

Run은 bounded process-local이며 exact delegation의 live re-entry만 지원한다.
Approval wait는 synthetic original Actor의 짧은 handoff다. Restart 회귀는 Runtime 종료 뒤
새 instance의 이전 Run 재개 거부이며 실제 JVM 강제 종료나 장시간 사용자 대기 실험은 아니다.
Duration/wait/cancel/restart evidence와 한계는 #156의 판단 입력이다.
Durable 채택·#157 종료 감사·production load/SLA·non-synthetic disclosure·restart continuity는 검증하지 않았다.
