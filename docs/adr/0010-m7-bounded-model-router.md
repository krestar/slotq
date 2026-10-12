# ADR-0010: Synthetic 비교와 snapshot 계산으로 bounded Model Router 구성

- 상태: `Accepted` (bounded synthetic Router/provider profile)
- 결정일: 2026-10-10
- 관련 Issue: [#152](https://github.com/krestar/slotq/issues/152), [#165](https://github.com/krestar/slotq/issues/165)
- 공통 계약: [M7 Architecture](../architecture/m7-model-router-agent-runtime.md)
- 실제 비교와 재현: [실험 기록](../experiments/m7-model-router.md)
- 구현 기준 main: `03a88be6c67140c6cace981fbbdcc8a88806500a`

## 맥락

M6 retrieval 비교는 생성 모델의 structured proposal과 설명 품질을 증명하지 않는다.
#151의 문서 계약 이후 실제 복수 모델, disclosure 조건, 실패와 비용 관측을 연결한
Router 선택 근거가 필요하다. 제공된 계정은 Gemini Developer API 무료 tier이며 유료
호출은 허용되지 않는다. 다른 provider credential과 local hardware 접근은 확인되지 않았다.

## 결정

현재 profile은 두 exact Flash-Lite 생성 모델의 standard `generateContent`와 민감정보 없는
synthetic fixture 비교다. 동일한 12개 한국어 case를 모델별 3회 실행하며, prompt/schema,
oracle, token/attempt/deadline과 USD 0 상한을 manifest에 고정한다. 실제 Product/MCP 결과,
production 품질/SLA, 다른 billing tier에 대한 비용 우위를 주장하지 않는다.

Router는 immutable snapshot의 eligibility와 score만 계산한다. Security/disclosure/capability,
quality 0.85와 safety violation 0은 hard gate다. Eligible 후보는 quality 0.60, latency 0.30,
cost 0.10의 deterministic score와 ID 오름차순 tie-break를 사용한다. Raw measurement,
price, classification, remaining budget/deadline과 policy를 저장하여 exclusion과 선택을
재계산한다. 자연어 답변의 동일 재생성을 재현성으로 정의하지 않는다.

Provider endpoint/model/API mode는 서버가 고정한다. JDK HTTP/1.1과 전체 시도 상한 1,
connection/method retry 금지, redirect NEVER를 JVM 시작 인자로 고정한
별도 JVM profile에서 한 HTTP 요청을 한 attempt로 집계하며 hosted execution은 사용하지
않는다. 모든 provider failure는 terminal이다. Same-provider retry, alternate-model 및
cross-provider fallback은 현재 지원하지 않는다. Unknown usage의 reservation을 유지하고
cost를 unavailable로 남긴다. Provider-only 반복이 Product mutation authority를 만들지 않는다.

Free-tier 약관의 training/human review를 허용하는 synthetic classification만 eligible하다.
Effective region/retention과 확인되지 않은 account control은 unavailable이다. No-training,
region 또는 retention 조건이 필요한 입력을 score로 통과시키지 않는다. Key와 account
identifier는 prompt/evidence에 저장하지 않는다.

## 대안과 근거

| 대안 | 판단 |
| --- | --- |
| 두 exact Gemini Flash-Lite 모델의 fixed REST adapter | 확인된 무료 API 접근, native JSON과 usage를 사용하며 추가 SDK/서비스 없이 attempt를 관측 |
| 최신 Flash 계열 | 실제 response를 얻었으나 timeout과 무료 daily quota로 완전한 비교를 못한 후보는 보류 |
| Cross-provider fallback | Alternate account/disclosure와 actual failure-to-alternate-call 증거 부재로 미지원 |
| Local inference | 가용 hardware, resource 및 workload capability의 실행 근거 부재로 보류 |
| 범용 gateway, AI framework, generic evaluation/version service | #152에 필요하지 않아 도입하지 않음 |

## 영향과 재검토

일반 Backend test/build는 fake transport와 고정 fixture를 사용하며 provider key/network를
사용하지 않는다. Actual harness는 명시적 opt-in이다. Raw model text, usage와 transient
evidence는 gitignored 경로에 두고 실행 요약과 fixture/policy/계산 코드를 추적한다.
자연어 내용 검토와 구조화 oracle를 구분하며, 근거 없는 주장과 outcome 왜곡을 발견한
후보는 안전성 gate에서 제외한다. 작은 dataset을 전체 의미 품질의 증명으로 확대하지 않는다.

Router/provider는 Actor/Tenant/Venue authorization, Run lifecycle/admission, approval,
MCP/Product execution, idempotency와 effect reconciliation을 소유하지 않는다. #153이
current authority와 cumulative budget, answer/outcome enforcement를 구현하고 #155가 실제
세 flow를 검증한다. Agent Runtime은 Deferred이며 #156의 durable gate를 선도입하지 않는다.

실제 data class/region/retention 요구, billing tier, exact model capability, account quota,
실제 flow quality 또는 fallback 필요성이 달라지면 새 evidence로 candidate와 policy를
재검토한다. 자동 model alias 변경이나 유료 quota 전환으로 기존 결정을 확장하지 않는다.

최종 72회 비교와 전체 내용 검토에서 Customer는 `gemini-3.5-flash-lite`만 eligible했다.
Owner·Manager와 Ops는 quality/safety 또는 unavailable measurement 때문에 no-candidate다.
이 결정은 해당 bounded Router 정책과 unavailable 동작의 채택이며 세 실제 flow의 활성화
승인이 아니다. 상세 수치와 실행 source/추가 방어 regression의 구분은 실험 기록을 따른다.

## 2026-10-13 corrective profile

위 72회와 당시 disposition은 #152의 historical baseline으로 보존한다.
[#165 비교](../experiments/m7-router-corrective.md)는 별도 `m7-comparison-v3` revision에서
Management/Ops의 조회 불가·부분 관측·누락된 조회 조건을 구분하도록 업무별 지시문을 보정했다.
기존 12개 primary input/fixture/oracle와 Customer request, proposal schema, Router hard gate와 weight,
두 exact provider/model/API mode 및 failure disposition은 유지했다. 새 holdout은 primary quality를 희석하지 않는다.

무료 tier를 다시 확인한 새 96회 actual-provider/synthetic-fixture 비교와 전체 내용 검토에서
`gemini-3.5-flash-lite`는 Management/Ops 각각 primary 12/12, holdout 6/6, safety 0으로 적격이었다.
Router는 해당 workload별 새 measurement와 immutable snapshot에서 3.5를 선택하고 replay했다.
3.1은 Management의 실제 503·usage 미확보/holdout 실패 또는 Ops의 내용 안전성 실패로 제외했다.
Customer는 동일 request로 새 quality 11/12·safety 0인 3.5 eligible 경로를 유지하며 과거 12/12를 수정하지 않는다.

v3의 제한된 합성 데이터 비교에서 검증한 후보·지시문·측정 근거를 추가로 채택한다.
실제 적용 지역과 보관 기간은 확인되지 않았으므로 unavailable이다.
입력·응답의 제품 개선 사용과 사람의 검토를 허용하는 합성 데이터 조건, USD 0과 유한한 호출·token·시간 예산을 유지한다.
새 measurement를 다른 workload에 재사용하거나 기존 Run의 예산을 초기화할 근거가 아니다.
실제 세 MCP/Product/Knowledge flow와 current authority/disclosure·최종 outcome 검증은 #155에 남긴다.
Durable 구현이나 #156 판정을 선도입하지 않는다. Provider/API/model/disclosure 조건이 바뀌면 다시 비교한다.
