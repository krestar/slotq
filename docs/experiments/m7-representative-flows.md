# 공통 Runtime의 세 업무 흐름 통합 검증

[#155](https://github.com/krestar/slotq/issues/155)는 Customer·Owner/Manager·Ops가 같은
production `AgentRuntime`과 실제 MCP/Product/Knowledge 연결을 사용하도록 통합한다.
구현 기준은 PR #167을 포함한 `main`의 `8a0bbe886627832659ca68e7b45c84f428d4bf6e`다.
계약은 [M7 Architecture](../architecture/m7-model-router-agent-runtime.md)와
[ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)을 따른다.

## 구현과 데이터 경계

`RepresentativeFlows`는 서버 내부 client다. 기존 Auth, MCP registry/engine, `HoldApprovals`,
인증된 Product HTTP와 Knowledge 검색을 재사용한다. 별도 Ops Runtime이나 공개 Run/approval API는 없다.

| 흐름 | 입력·조회 범위 | 실행과 답변 |
| --- | --- | --- |
| Customer | exact Slot·인원수 또는 known Reservation, 선택적 public Knowledge | Original Actor가 immutable review를 확인하고 명시적으로 승인한 뒤 HOLD. Mutation 결과·현재 exact GET·답변을 분리하며 HELD를 예약 확정으로 표시하지 않음 |
| Owner·Manager | granted Venue의 현지 단일 날짜, 선택적 operator Knowledge | 기존 날짜 조회를 하루로 제한. Product 예약 사실과 문서 안내를 구분하며 읽기만 수행 |
| Ops | 최대 31일의 `[from,to)`, 최대 3건 | #154의 projected-at 조회 사용. 발생/투영 시각·간격·전달 상태·수신 기록·누락·잘림을 설명하며 recovery 실행 없음 |

Provider context에는 허용된 필드만 넣는다. 예약 목록은 최대 3건의 상태·인원수·시각과 반환 건수,
부분 사용 여부를 포함한다. Knowledge는 source/document/version/visibility와 한 개의 untrusted excerpt를
보존한다. Tenant/Venue/Actor/customer reference, credential과 Product key는 제외한다.
Unknown 조회를 0건이나 잘리지 않은 빈 결과로 바꾸지 않으며 Ops의 null 필드도 보존한다.

새 모델 전송 전 `readPlanned`가 admitted plan의 Knowledge 조회를 갱신한다. 현재 publication과 scope는
기존 검색 경계가 검증하며 이 조회도 같은 Run의 tool/step budget을 사용한다. Context 조립에는 I/O가 없다.
Product·Knowledge의 atomic snapshot이나 최종 조회 이후 변경의 소급 제거는 보장하지 않는다.

[#165 보정 실험](m7-router-corrective.md)의 workload별 후보 설정을 사용한다.
기존 #152의 `no_candidate`는 과거 baseline이다. 필요한 실행 설정인 `representative-router-v1.json`에는
적격·제외 후보의 측정과 가격·controls를 함께 보존하며, 기존 96회 원본에서 재계산한 설정과 일치한다.
품질 0.85·안전성 위반 0·disclosure·측정·예산 gate는 유지한다.

#165의 적격성은 synthetic 조건에 한정된다. Trusted server predicate가 현재 Tenant/Venue의 synthetic
전송을 승인해야 하며 조립 시점과 dispatch 직전에 다시 확인한다. Owner/Manager 권한이나 모델 입력은
전송 승인을 대신하지 못한다. Current authority, provider admission과 Run budget도 독립적으로 적용한다.
실제 고객·비공개 운영 데이터의 외부 전송 승인을 검증한 결과가 아니다.

## 실제 provider 검증

현재 고정 명세는 `m7-integrated-flow-evidence-v3`, context는 `m7-flow-context-v4`다.
Customer proposal·HOLD answer·known GET,
Owner/Manager 각각의 Management·Ops 답변으로 7개 case를 구성한다. 같은 Runtime을 사용하며
original Actor가 직접 승인한 실제 Product HOLD, 실제 문서 publish/search와 event append/observer projection을 연결한다.

Runtime 호출은 co-located production `McpEngine` 경로다. 같은 registry/delegation으로 HTTPS
initialize/initialized/list/call도 별도로 확인한다. 새로운 원격 Runtime transport를 검증한 결과는 아니다.
Operator 조회를 위한 별도 synthetic 예약은 Product HTTP로 사전 생성하며 Customer flow 자신의 HOLD와 구분한다.

사용자 승인 무료 tier에서 `gemini-3.5-flash-lite`만 사용한다. 실행별 최대 7회·10분·USD 0,
출력 1,024 token, 호출 간 최소 7초, attempt 대기 최대 45초다. 실패한 Run은 terminal이며 자동 retry/fallback은 없다.
[공식 가격](https://ai.google.dev/gemini-api/docs/pricing)과 [이용 약관](https://ai.google.dev/gemini-api/terms)을
확인했으며 제품 개선·사람의 검토 조건 때문에 민감정보 없는 disposable synthetic 데이터만 전송한다.
Effective region/retention은 unavailable이고 실제 청구서는 측정하지 않는다.

### 완료 실행과 실패 기록

**상태: 구현·자동 검증 완료, 최신 실제 provider 통합 검증 미완료.** 현재 후보는 적격하지만
승인된 무료 provider의 timeout·503이 반복돼 최신 명세의 7개 정상 응답을 확보하지 못했다.
아래 이전 성공·부분 실행은 최신 명세의 완료를 대신하지 않는다. Timeout·예산·품질 gate는 유지한다.

Context v4의 두 실행은 각각 첫 Management 호출에서 종료됐다. `representative-flow-final`은
HTTP 503 / TEMPORARY_OVERLOAD(2,312 ms), `representative-flow-final-complete`는
TIMEOUT_REMOTE_UNKNOWN(45,002 ms)이다. 두 원본의 request/schema/profile/config·전체 실패 검토·
Run continuity와 routing replay를 재계산했다. 각 Management는 0/1, Customer/Ops는 미관측이며
usage·cost는 unavailable이다. 부분 요약을 생성하되 `complete=false`와 실패 exit를 유지한다.

실패·이전 실행도 성공 기록과 구분해 보존한다.

| 기록 디렉터리 (`build/`) | 관찰 | 해석 |
| --- | --- | --- |
| `representative-flow-v1` | Customer 2회. Proposal·승인·Product HOLD는 성공했지만 answer의 잘못된 `partySize` 제어 필드를 MALFORMED로 거부 | Structured Product success 보존. 통합 완료 아님 |
| `representative-flow-v2` | 30초 Run timeout 뒤 local completion 기록 누락 | 미완료 근거.실제 remote 실행·usage·cost 확인 불가 |
| `representative-flow-v2-complete` | 1회 TIMEOUT_REMOTE_UNKNOWN, local completion 기록 확보 | Usage·cost unavailable. 실패한 Run |
| `representative-flow-v2-operators-first` | 실제 응답 7회와 Runtime 정상 반환 | 전체 내용 검토에서 Ops의 시각·수신 기록·잘림 설명 부족 확인. Ops 완료로 계산하지 않음 |
| `representative-flow-v3` | Management 2회와 보강된 Ops Owner 답변 성공, Ops Manager timeout | 부분 실행. 전체 완료 아님 |
| `representative-flow-v3-complete` | 첫 Management 호출 TIMEOUT_REMOTE_UNKNOWN | Usage·cost unavailable. 전체 완료 아님 |
| `representative-flow-final` | Context v4의 첫 Management 호출에서 실제 HTTP 503 / TEMPORARY_OVERLOAD | Terminal failure, usage·cost unavailable. 전체 완료 아님 |
| `representative-flow-final-complete` | Clean build 뒤의 새 실행에서 첫 Management 호출 TIMEOUT_REMOTE_UNKNOWN | 1개 실패 원본 재계산. 미관측 사례를 성공으로 채우지 않음 |

첫 실패 뒤 Product 인원수와 응답 제어 필드, claim/source 관계를 명확히 했다.
Ops 설명 누락 뒤에는 필요한 claim과 실제 설명을 함께 요구하도록 output contract를 보강했다.
해당 회귀 테스트는 reference만 채우고 설명을 생략한 답변도 거부한다. 이후 context v4는 답변을 400자 이내로
요청해 기존 Runtime 길이 상한 안에서 설명하도록 했다. 필수 사실·한계, oracle과 gate는 유지한다.
이 검사는 전체 자연어 정합성 인증이 아니다.
Metadata GET의 HTTP 200은 generation 성공이나 timeout 원인의 증거로 사용하지 않는다.

## Deterministic·장애 주입 검증

일반 Backend test는 deterministic provider와 실제 Auth/MCP/Product HTTPS/MySQL/Knowledge를 사용한다.
외부 key/network가 필요하지 않으며 실제 provider case는 별도 opt-in 실행에서만 수행한다.

| 검증 | 확인한 경계 |
| --- | --- |
| Run control | 모든 지원 control에서 다른 Actor/Tenant/Venue, replacement·revoked·expired delegation 거부. Staff·역할 강등, 기존 budget reset과 restart resume 거부 |
| 승인·제안 | Original Actor review/approval, 승인 없는 HOLD·wrong material/approver 거부. 새 delegation의 old intent/confirmation 재사용, malicious corpus·forbidden tool·wrong target의 dispatch 차단 |
| Provider | 주입한 unavailable/rate-limit/malformed/timeout은 terminal. 미지원 fallback과 새 tool dispatch 없음. Disclosure 철회·dispatch 직전 변경 시 미호출 |
| Product unknown | 실제 COMMIT 뒤 HTTP 응답 유실 주입. MySQL durable HOLD·unknown target 보존, explicit same-intent retry의 단일 예약과 known-target exact GET 확인 |
| MCP unknown | 실제 COMMIT 뒤 generic handler loss/timeout 주입. Invocation UNKNOWN을 structured Product outcome_unknown과 구분 |
| 취소·답변 | Continuing work의 permit을 실제 종료까지 유지. Late effect만 보완하며 새 dispatch 없음. Product 성공 뒤 답변 실패도 structured success 보존 |
| Context·관측 | Withdrawn Knowledge 재전송 방지. Evidence/insufficient/no_answer/unavailable 구분. Ops incomplete semantics와 읽기 전후 business/event 상태 불변 |

Provider 장애 주입은 실제 provider failure와 합산하지 않는다. Product 응답 유실은 실제 요청·COMMIT 뒤
test-only filter/latch/spy에서 주입한다. Pending delivery·null receipt fixture와 실제 observer projection을
구분하며 Kafka 전송·recovery 검증을 주장하지 않는다.

## 원본과 재계산

실행 산출물은 Git에서 제외한 root `build/representative-flow-*`에 둔다. Manifest는 실제 source revision·
source hash·context/schema/profile·HTTP controls와 사전 고정한 case/review policy를 기록한다.
당시 source copies도 보존하며 이후 calculator 변경은 별도 재계산 hash로 구분한다.

| 파일 | 내용 |
| --- | --- |
| `provider-starts.jsonl` | Adapter 진입과 request/classification. Remote 실행 완료의 증거는 아님 |
| `provider-completions.jsonl` | 실제 adapter attempt count·response/usage·local completion |
| `attempts.jsonl` | Request·raw response·provider result·plan·전후 Runtime snapshot·routing·duration/approval wait |
| `content-review.json` | 전체 답변/실패 검토. Case/model/repeat·request/answer digest·findings를 고정 policy와 연결 |
| `summary.json`, `oracle-marks.json`, `recalculation.json` | #152 oracle·내용 검토·flow output contract 평가, routing replay, latency/usage/cost/quality와 원본/calculator hash |

재계산은 request/schema/profile/config identity, normalized/raw response, original Run ID·deadline·
attempt 증가와 routing replay를 확인한다. Missing/partial review도 거부한다. 부분 실행에서는 미관측 사례와
unavailable 값을 보존하며 전체 case·oracle·내용 검토·usage를 확보하지 못하면 완료 판정을 거부한다.
Decimal 자릿수를 보존해 JSON tree를 거친 score도 원래 routing snapshot 그대로 재생한다.
Known usage에만 고정된 무료 가격의 estimated USD 0을 적용하며 unavailable usage/cost를 0으로 채우지 않는다.
이전 #152/#165 비교나 fixture 결과를 새 통합 실행에 합산하지 않는다.

JDK 25, `backend/`에서 실행한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.mcp.RepresentativeFlowIntegrationTests' --tests 'com.slotq.ai.*' --tests 'com.slotq.architecture.*'
.\gradlew.bat representativeFlowEvidence -PflowEvidenceOptIn=true -PflowEvidenceFreeTier=true -PflowEvidencePaidCalls=false -PflowEvidenceOutput=representative-flow-new-run
.\gradlew.bat representativeFlowRecalculate -PflowEvidenceOutput=representative-flow-new-run
.\gradlew.bat test
.\gradlew.bat clean build
```

실제 호출 전 현재 무료 tier 조건을 확인한다. Key는 ignored `.env.local`의 `GEMINI_API_KEY`에서 읽으며
argument/log/evidence에 복사하지 않는다. 기존 manifest를 덮어쓰지 않는다.
재계산은 기존 `m7-content-review-v1` 형식의 전체 `content-review.json`을 작성한 뒤 수행한다.
XML·로그·raw JSON·model output·source copies는 커밋하지 않는다.

### 실행한 자동 검증

- Targeted: 138개 중 137개 실행, 실제 provider opt-in skip 1개, 실패·오류 0(1분 27초).
- Backend 전체 `test`: 109 suite / 940개 중 929개 실행, opt-in skip 11개, 실패·오류 0(25분 25초).
- `clean build`: 동일하게 929개 실행, skip 11개, 실패·오류 0(25분 33초). 배포 jar의 실험 코드·입력 제외 확인.
- 이후 오프라인 계산 방어를 보강하고 validator 회귀 2개를 targeted 실행했다. Decimal replay,
  부분 실패의 unavailable/미완료 처리, request/oracle/deadline/검토 변조 거부를 통과했다.
  이 마지막 변경은 test 전용 calculator이며 production Runtime/flow/adapter는 clean build 이후 바뀌지 않았다.

## 남은 제한과 후속 판단 입력

Run은 process-local이며 exact delegation의 live re-entry만 지원한다. 원래 Actor의 승인 대기는 실험 client의
짧은 명시적 handoff로 측정했으므로 실제 사람의 장시간 대기나 공개 UI 검증이 아니다.
Duration/deadline·approval wait와 취소 후 continuing work, COMMIT 응답 유실, restart 후 자동 mutation 부재를
#156의 입력으로 제공한다. 여기서 durable 필요성을 판정하거나 #157 종료 감사를 선행하지 않는다.
Restart 회귀는 Runtime 종료 후 새 instance가 이전 Run을 재개하지 않는 검사다. 실제 JVM 강제 종료나
장시간 사용자 승인 대기 실험은 수행하지 않았다. Production load/SLA, non-synthetic disclosure와
restart continuity는 이번 결과의 범위 밖이다.
