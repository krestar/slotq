# Management·Ops 모델 후보 재검증

새로운 실제 모델 비교에서 Management와 Ops 모두 `gemini-3.5-flash-lite`가 기존 적격 기준을 충족했다.
두 업무에서 각각 기본 사례 12회와 추가 검증 사례 6회를 모두 통과했고, 안전성 위반은 없었다.
Router의 선택 결과를 저장한 뒤 같은 입력으로 다시 계산했을 때도 결과가 일치했다.

이 문서는 [#165](https://github.com/krestar/slotq/issues/165)의 후보 재검증 결과다.
기준 main은 `6650ee91ed4a4af1b2625d0a5f95146e0a4593f3`이다.
[기존 72회 비교](m7-model-router.md)의 원본과 당시 판정은 보존했으며,
[ADR-0010](../adr/0010-m7-bounded-model-router.md)의 품질 0.85 이상·안전성 위반 0건 기준도 유지했다.
데이터 공개 조건, 모델 기능, 측정값과 실행 예산을 확인하는 기존 기준 역시 그대로 적용했다.

이번 결과는 합성 데이터에 대한 모델 적격성 근거다. 실제 MCP/Product/Knowledge를 연결한 세 업무 흐름은
#155에서 별도로 검증해야 한다. #156의 durable 작업은 이번 변경에 포함하지 않았다.

## 기존 모델이 탈락한 이유

먼저 기존 응답과 사전에 정한 정답 기준(oracle), 자연어 내용 검토 기록을 대조했다.
보존한 복사본에서 72개 기록을 다시 계산하자, Customer만 적격이고 Management/Ops는 후보가 없다는
원래 결과가 재현됐다. 기존 오류는 실제 모델 응답에서 확인됐으며, 정답 기준이나 응답 해석 코드의 결함으로
판정할 근거는 없었다. 이 과정에서는 외부 모델을 새로 호출하지 않았다.

Management에서는 **예약 조회 실패를 예약 변경 결과의 불확실성과 혼동**했다.
조회가 불가능한 사례에서 3.5는 3회 모두, 3.1은 1회 `UNKNOWN`을 반환했다.
실제로 예약을 변경한 요청이 없으므로 이 사례의 `outcome`은 `NOT_APPLICABLE`이어야 한다.
3.1은 안내문의 “21시 마감”을 “매일 21시 마감”으로 확대하거나, 조회 실패의 원인을
“시스템 오류”, “일시적 제한”으로 설명하기도 했다. 제공된 자료에는 이러한 근거가 없었다.

Ops에서는 **일부 관측이 있다는 사실과 조회 자체가 불가능한 상태를 구분하지 못했다**.
지연·전달 대기·수신 기록 부재·잘린 결과를 설명하는 정상 사례에서도 두 모델의 성공 응답은 모두
`INSUFFICIENT`였다. 3.1의 두 응답은 `UNKNOWN`까지 사용했다.
이 사례는 관측된 사실과 그 한계를 `ANSWER`로 설명할 수 있어야 한다.
조회 기간이 빠진 사례에서는 3.5가 예약 slot·인원수를 요구했고, 3.1은 필요한 조건을 묻는 대신
`INSUFFICIENT`를 반환하거나 결과가 비어 있다고 주장했다.

모델의 설명 오류와 제공자 장애도 구분했다. 기존 Ops 정상 사례의 3.1 세 번째 호출은 실제 503 오류였다.
응답을 받지 못해 token 사용량과 비용을 알 수 없었으므로 해당 측정은 `unavailable`로 남겼다.
이를 품질 평가의 성공이나 비용 0으로 처리하지 않았다.

## 무엇을 보정했는가

[새 비교 설정](../../backend/src/test/resources/model-router/comparison-v3.json)은 Management와 Ops의
지시문을 업무별로 분리한다. 예약 조회가 실패했을 때는 확인할 수 없는 사실을 설명하고,
예약 변경이 없었다면 `NOT_APPLICABLE`을 유지하도록 명확히 했다.
이미 제공된 관측은 한계와 함께 설명하고, 조회 기간이 없으면 `from`/`to`만 요청하도록 정리했다.
자료에 없는 장애 원인·반복 일정·권한을 추론하지 않는다는 제약도 유지했다.

기존 12개 사례의 입력·합성 응답 자료·정답 기준은 바꾸지 않았다. Customer에 보내는 요청도 기존과 같다.
모델에는 정답, 사례 ID, 평가용 필수·허용 목록을 보내지 않는다.
응답 JSON 규격과 Router의 품질·안전성 기준, 점수 가중치도 그대로 사용한다.

특정 사례에만 맞춘 보정인지 확인하기 위해 독립적인 추가 사례(holdout) 4개를 실행 전에 고정했다.
Management에는 다른 예약 건수·마감 시간과 문서에 섞인 취소 명령, 다른 매장 접근 권한을 위조하는 요청을 넣었다.
Ops에는 `DONE` 상태라도 수신 기록이 null이고 결과가 잘린 사례, 예약 조건을 이벤트 조회 조건으로 쓰라고
유도하는 사례를 넣었다.

기본 사례의 통과율은 따로 계산해 0.85 이상을 요구한다. Management/Ops의 추가 사례는 각각
6회 모두 정답 기준과 전체 내용 검토를 통과해야 한다. 추가 사례가 기본 사례의 실패를 평균으로 희석하지 않도록 했다.
자연어 검토에는 실행 전에 고정한 [내용 검토 기준](../../backend/src/test/resources/model-router/content-review-policy-v2.json)을 적용했다.
JSON 필드가 맞더라도 근거 없는 주장이나 결과 왜곡이 있으면 후보를 제외한다.

## 실제 실행 결과

2026-10-13 03:50 KST에 시작해 24분 12초 동안 **실제 생성 요청 96회**를 수행했다.
환경은 Windows 11 amd64, OpenJDK 25.0.4.1, Gradle 9.7.1이었다.
기존 두 모델에 기본 사례 12개와 추가 사례 4개를 각각 3회씩 적용했다.
92회에서 구조화 응답과 사용량을 받았고, 3.1의 4회 호출에서는 실제 503 오류가 발생했다.
시간 초과, 제공자의 정책 거절, 응답 형식 오류는 이번 실행에서 관측하지 않았다.

| 업무 | 모델 | 기본 사례 통과 | 추가 사례 통과 | 안전성 위반 | Router 판정 |
| --- | --- | ---: | ---: | ---: | --- |
| Customer | 3.5 Flash-Lite | 11/12 | 해당 없음 | 0 | 선택 |
| Customer | 3.1 Flash-Lite | 11/12 | 해당 없음 | 1 | 사용량·비용 미확보로 제외 |
| Management | 3.5 Flash-Lite | 12/12 | 6/6 | 0 | 선택 |
| Management | 3.1 Flash-Lite | 11/12 | 4/6 | 1 | 사용량·비용 미확보 및 추가 사례 실패로 제외 |
| Ops | 3.5 Flash-Lite | 12/12 | 6/6 | 0 | 선택 |
| Ops | 3.1 Flash-Lite | 12/12 | 6/6 | 1 | 안전성 위반으로 제외 |

추가 사례의 통과 수에는 정답 기준과 자연어 내용 검토를 모두 반영했다.
3.1 Management의 전체 정답 통과 수는 15/18이며, 3.5 Management/Ops와 3.1 Ops는 각각 18/18이다.
3.5의 Router 점수는 Customer 0.938110, Management 0.979770, Ops 0.984700이었다.
세 업무 모두 3.5가 유일한 적격 후보로 선택됐고, 저장한 선택 기록의 재계산도 통과했다.

96개 기록의 응답 전체 또는 실패를 검토했다. 3.1에서는 근거 없는 주장이 3건 남았다.
Customer는 응답 유실의 원인을 “시스템 오류”로, Management는 조회 불가를 “일시적 제한”으로 설명했다.
Ops는 조회할 수 없는 상태를 관측 데이터가 존재하지 않는다고 단정했다.
이 위반은 구조화 정답 통과 여부와 별개로 기록했으며, 평가 규칙을 바꿔 통과시키지 않았다.

3.5 Customer의 근거 부족 사례 한 응답은 `INSUFFICIENT` 대신 `ANSWER`를 반환해 원래 정답 기준에서 실패했다.
예약 결과의 `UNKNOWN`은 보존했고 안전성 위반은 없었다.
새 통과율 0.916667은 기존 품질 기준을 충족한다. 과거 Customer 12/12 결과는 그대로 보존한다.

## 지연·사용량·비용

| 업무 | 모델 | p95 지연(ms) | 확인된 total token | 사용량 미확보 호출 |
| --- | --- | ---: | ---: | ---: |
| Customer | 3.5 Flash-Lite | 1,189 | 6,399 | 0 |
| Customer | 3.1 Flash-Lite | 23,301 | 6,009 | 1 |
| Management | 3.5 Flash-Lite | 2,023 | 10,968 | 0 |
| Management | 3.1 Flash-Lite | 27,331 | 9,416 | 3 |
| Ops | 3.5 Flash-Lite | 1,530 | 12,320 | 0 |
| Ops | 3.1 Flash-Lite | 27,589 | 12,625 | 0 |

503은 Customer 조회 조건 확인 사례의 두 번째 호출, Management 조회 조건 확인 사례의 두 번째 호출,
Management 추가 금지 사례의 두 번째·세 번째 호출에서 발생했다.
각 호출은 한 번의 시도 뒤 실패로 종료했다. 사용량을 알 수 없는 호출의 token 예약량은 유지했고,
뒤에 예정된 독립 비교를 재시도나 fallback으로 계산하지 않았다.

확인된 57,737 token에 무료 가격을 적용한 비용 소계는 **estimated USD 0**이다.
503 네 건을 포함한 전체 사용량·비용은 `unavailable`이다.
선택된 3.5의 업무별 비용은 각각 estimated USD 0이며, 실제 청구서를 측정한 값은 아니다.
응답에 없는 thinking token 수도 0으로 채우지 않았다.

사용자는 실행 전에 현재 무료 tier이고 billing이 비활성화되어 있음을 확인했다.
2026-10-13에 [공식 가격](https://ai.google.dev/gemini-api/docs/pricing)과
[데이터 이용 약관](https://ai.google.dev/gemini-api/terms)도 확인했다.
입력과 응답이 제품 개선·사람의 검토에 사용될 수 있으므로 민감정보 없는 합성 데이터만 보냈다.
실제 적용 지역과 보관 기간은 확인되지 않아 `unavailable`로 유지했다.

두 모델의 정보 조회도 각각 한 번 수행해 `generateContent` 지원과 입력 1,048,576·출력 65,536 token 한도를 확인했다.
모델 정보의 version은 각각 `3.5-flash-lite-07-2026`, `3.1-flash-lite-05-2026`이었다.
생성 응답의 `modelVersion`은 각 model ID였다. 이 값을 내부 모델 가중치의 고정 버전으로 해석하지 않는다.
두 정보 조회는 생성 요청 96회와 별도로 기록했고, 계정 ID·응답 식별자·인증정보는 저장하지 않았다.

## 재현 방법과 기록 보존

새 설정의 식별자는 `m7-comparison-v3`, 지시문은 `m7-workload-prompt-v3`,
응답 규격은 `m7-proposal-v2`, HTTP 제어는 `m7-http-v2`다. 실행 전에 다음 한도를 고정했다.

| 항목 | 한도 또는 설정 |
| --- | --- |
| 생성 | Temperature 1, 응답 후보 1개, 출력 1,024 token, 요청 16,384 byte |
| 개별 호출 | 1회 시도, 45초, 60,000 token |
| 전체 비교 | 96회, 45분, USD 0, 호출 간 최소 7초 |
| 자동 실행 | 도구 실행·재시도·다른 모델로 전환·fallback 없음 |

JDK 25를 사용하고 `backend/`에서 Gradle Wrapper로 실행한다.
외부 모델을 새로 호출하기 전에는 무료 tier 상태를 다시 확인해야 한다.
인증 키는 Git에서 제외된 기존 `.env.local`에서만 읽는다.

```powershell
.\gradlew.bat test --tests 'com.slotq.ai.router.*' --tests 'com.slotq.ai.runtime.*' --tests 'com.slotq.architecture.ModelRouterArchitectureTests' --tests 'com.slotq.architecture.AgentRuntimeArchitectureTests'
.\gradlew.bat modelComparison -PmodelComparisonProfile=corrective-v3 -PmodelComparisonOptIn=true -PmodelComparisonFreeTier=true -PmodelComparisonPaidCalls=false
.\gradlew.bat modelComparison -PmodelComparisonProfile=corrective-v3 -PmodelComparisonRecalculate=true
.\gradlew.bat test
.\gradlew.bat clean build
```

결과는 Git에서 제외된 `build/model-router-corrective-v3/`에 저장한다.
기존 실행 명세나 응답 기록이 있으면 덮어쓰기를 거부한다.
다음 비교에서는 `-PmodelComparisonOutput=model-router-new-comparison`처럼 새로운 디렉터리 이름을 지정한다.
지정할 수 있는 경로는 root `build/` 아래의 `model-router-` 접두사 단일 디렉터리로 제한한다.

재계산 전에 모든 응답과 실패를 검토해 `content-review.json`을 작성한다.
각 검토 항목은 기존 `ComparisonReview` 형식과 `reasonCode`를 사용한다.
검토 파일에는 `specificationSha256`, `reviewPolicySha256`도 기록해 입력·정답 기준과 검토 규칙을 연결한다.
사례·모델·반복 번호, 요청과 전체 응답의 해시가 맞아야 하며, 미검토나 일부 검토만으로 후보를 채택할 수 없다.

| 파일 | 용도 |
| --- | --- |
| `manifest.json` | 실행 시 소스·입력·지시문·JSON 규격·설정·모델/API/version·가격·데이터 이용 조건·HTTP 제어 기록 |
| `attempts.jsonl`, `content-review.json` | 실제 응답·실패·사용량과 전체 내용 검토 기록 |
| `case-analysis.json` | 정답 기준별 결과, 제공자 실패, 사용량 확보 여부, 내용 위반과 이유 |
| `summary.json`, `routing.json` | 업무별 측정 요약과 후보 제외·점수·선택 기록 |
| `recalculation.json` | 실제 호출 소스와 최종 계산 소스의 구분 |

실행 명세에는 시작 당시 main `6650ee9`와 커밋되지 않은 작업 내용의 소스 해시를 기록했다.
그 해시 7개와 일치하는 소스 사본도 `build/issue-165/actual-source/`에 보존했다.
실험 시작 후 추가한 설정·사용량·검토 사유 검증은 오프라인 계산의 방어다.
모델에 보내는 요청, 응답 규격, adapter, HTTP 제어와 사전 평가 기준은 그대로였다.
내용 검토 전의 중간 요약은 적격성 근거로 사용하지 않았다.

새 입력과 검토 규칙의 해시는 UTF-8/LF로 고정해 checkout의 줄바꿈 차이를 제거했다.
기존 비교를 재계산할 때는 Windows CRLF와 당시 LF의 차이로 처음 검증이 거부됐다.
당시 LF 입력을 별도 복사본에 적용한 뒤 원래 결과를 재현했으며, 기존 파일의 해시 기준은 변경하지 않았다.
선택 기록의 잔여 예산은 새로 만든 제한된 선택 검증용 값이다. 실제 Run이나 전체 비교의 잔여 예산을 뜻하지 않는다.
원본 응답·소스 사본·로그·XML은 Git에 커밋하지 않는다.

## Backend 검증

관련 Router/provider/Runtime/architecture 테스트는 **84개 실행, 실패·오류·skip 0**으로 통과했다.
새 회귀 8개는 기존 입력·정답·Customer 요청 보존, 모델에 정답 미노출, 기존 오류 판정 유지,
업무별 선택·JSON 재계산, 기본 사례 실패의 희석 방지, 내용 위반·미검토 제외와 검토 기록 변조 거부를 확인한다.
주입한 가짜 측정값은 이러한 계산·경계 테스트에만 사용하며 실제 모델 비교나 자연어 검토 근거로 세지 않는다.

전체 `test`는 22분 20초에 통과했다.
107 suite / 918개 중 **908개 실행, 실패·오류 0, 기존 opt-in skip 10건**이다.
Skip은 Booking 진단 2건, Kafka opt-in 6건, M6 실제 검색 비교 1건, Waitlist 진단 1건이다.
미실행 항목은 성공으로 세지 않는다.
이후 `clean build`도 26분 40초에 통과했으며, 동일하게 908개 실행·실패와 오류 0·기존 skip 10건이었다.
배포 jar에 비교 실행 코드·내용 검토 코드·실험 입력이 포함되지 않는 것도 확인했다.

전체 내용 검토 후 원본에서 요약·선택 결과를 다시 계산하는 검증도 통과했다. 외부 모델 호출은 없었다.
기존 72개 기록의 원본 해시, 변경 문서의 링크, 실제 인증 키가 변경 파일이나 응답 기록에 포함되지 않았는지도 확인했다.
