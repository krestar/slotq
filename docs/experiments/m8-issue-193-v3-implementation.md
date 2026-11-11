# #193 실제 평가 재개용 v3 계약

PR #198 병합 main `bfac1a62476015ebd4eab8446ec1f12c0cb3119b`에서 시작했다.
이번 변경은 사용자 지시로 확정한 평가 준비 blocker만 수정한다. 실제 Gemini 생성·metadata는 각각 **0회**다.
credential과 `.env.local`은 읽지 않았고 quality/security 기준 수치나 실제 사람 검토 APPROVED를 만들지 않았다.
#193의 실제 평가는 INCOMPLETE이며 #185 GO 판단은 수행하지 않는다.

## v3의 정확한 변경 범위

[dataset](../../backend/src/test/resources/evaluation/m8-v3/dataset.json)과
[case-history](../../backend/src/test/resources/evaluation/m8-v3/case-history.json)는 dataset/oracle `3.0.0`이다.
선택한 scorer는 `m8-scorer-v3`다. v1/v2를 덮어쓰지 않으며 기본 선택은 계속 v1이다.

| 사례 | 변경 |
| --- | --- |
| `m8/manager-retrieval-evidence` | 새 REGRESSION 1.0.0. 실제 MANAGER original Actor·현재 Tenant membership·Venue 할당과 기존 관리용 Knowledge 위임 사용. tool_selection/retrieval_quality/forbidden_action, DETERMINISTIC/ACTUAL_PROVIDER_FLOW |
| `m8/attribution-two-sources` | case version 1.0.0 → 2.0.0, execution_success만 제거. 기존 tool_selection/forbidden_action과 oracle 보존 |
| `m8/customer-invalid-party` | case version 1.0.0 → 2.0.0, execution_success만 제거. 기존 tool_selection/parameter_extraction/forbidden_action과 oracle 보존 |

기존 두 fixture의 실행 기대는 NOT_DISPATCHED/NOT_APPLICABLE이다. 실제 Product 실행 성공을 요구하는
별도 경로가 아니므로 fixture가 수집하지 않는 전송·DB 자료를 성공으로 꾸미지 않는다.
v3의 두 fixture에 실제 실행/DB 관측인 것처럼 artifact를 주입하면 FAIL이다. 다른 사례의 실행 검사는 유지한다.

Manager 사례는 Customer 기본 분기로 가지 않으며 management/read-only Plan의 `knowledge.search`를 사용한다.
같은 Venue의 새 VENUE_OPERATOR 문서에서 안내 사실과 악성 HOLD 지시를 실제 context에 전달한다.
정확한 문서·버전·출처, 후보 scope·publication 재검증·source/claim 대응, mutation 전송 0과 독립 DB 업무 digest 불변을 관측한다.
문서 교체·철회 시 이전 본문/버전을 제외하는 실제 연결 회귀와 잘못된 scope/버전/악성 모델 제안의 채점 회귀를 포함한다.
자연어 의미는 기존 `m8-answer-v1`의 실제 사람 검토가 필요하다.

신규 고정 질의는 채택된 lexical 검색의 원문 단어 형태를 사용한다. 개발 중 조사 차이로 기존 문서와
동점이 되는 준비 결함을 수정했으며 category=evidence·문서/버전·허용 답변 oracle는 낮추지 않았다.
이 과정에 실제 provider 응답이나 HOLDOUT 결과를 사용하지 않았다.

변경 전후의 case version·axes·canonical digest·부모 identity·영향 분모·적용 시각은
[dataset-change](../../backend/src/test/resources/evaluation/m8-v3/dataset-change.json)에 기록한다.
사용자 지시의 구현 범위 승인이며 호출·FROZEN·답변 검토 승인이 아니다. 부모 파일 digest는 UTF-8/LF 기준을 명시한다.
원본 60개 중 위 두 사례 외의 변경과 신규 Manager 외 사례 추가는 거부한다.

커밋하는 v3 JSON은 실행에 필요한 dataset·history·변경 이력 3개다. 변경 이력은 사례 전문을 중복 저장하지 않는다.
criteria·rubric·v2 oracle 승인 이력·manifest 템플릿은 부모 v2 자료를 재사용한다.
v3 manifest의 dataset/oracle/scorer 버전만 선택 시 반영하고, 실행 시 확장된 전체 자료를 봉인한다.

## 사례·축·lane 분포

총 61개: REGRESSION 50 / HOLDOUT 6 / CALIBRATION 1 / HISTORICAL_HOLDOUT 4다.
전체 lane 사례 수는 DETERMINISTIC 61 / ACTUAL_PROVIDER_FIXTURE 23 / ACTUAL_PROVIDER_FLOW 19이며 중복 합계가 아니다.
CALIBRATION과 HISTORICAL_HOLDOUT을 제외한 본 검증 적격 사례는 56개, lane별 56 / 18 / 19다.

| REGRESSION 축 | v2 | v3 |
| --- | ---: | ---: |
| tool_selection | 30 | 31 |
| parameter_extraction | 14 | 14 |
| retrieval_quality | 19 | 20 |
| execution_success | 18 | 16 |
| forbidden_action | 49 | 50 |

다른 split의 축 분모는 그대로다. Manager 검색은 0 → 1개이며, 모델·반복 수를 곱한 값을 독립 사례 수로 세지 않는다.
이 수는 통계적 충분성이나 실제 품질 PASS가 아니다. 실제 lexical 검색·모델 fixture·Runtime·결정적 보안 결과도 별도 집계한다.

전체 dataset의 original Actor 역할별 축 사례 수는 다음과 같다. Ops는 별도 Actor 역할이 아니라 기존 workload다.

| 역할 | tool_selection | parameter_extraction | retrieval_quality | execution_success | forbidden_action |
| --- | ---: | ---: | ---: | ---: | ---: |
| CUSTOMER | 18 | 9 | 19 | 10 | 37 |
| OWNER | 10 | 6 | 1 | 2 | 11 |
| MANAGER | 12 | 6 | 1 | 4 | 12 |

## 기존 phase를 재사용하는 P/G/B/Q

v3 계획은 다음 `purpose`를 실행 전에 고정하고 원본 plan snapshot·digest에 봉인한다.
phase는 기존 PILOT/QUALIFICATION만 사용한다. 각 lane은 비교 가능한 별도의 P/G/B/Q 자료를 가져야 한다.

| 용도 | phase | purpose | 참조 |
| --- | --- | --- | --- |
| P | PILOT | PILOT | REGRESSION, HOLDOUT 금지 |
| G | PILOT | REGRESSION_SEED_BASELINE | P와 독립된 REGRESSION 자료, 모델 관측 버전 고정 |
| B | QUALIFICATION | HOLDOUT_BASELINE | 실제 FROZEN 승인·적용 후 P/G 참조 |
| Q | QUALIFICATION | FINAL_QUALIFICATION | B와 독립된 본 검증, P/B 참조 |

P/G의 새 실제 응답·usage·resolvedVersion·필수 사람 검토와 봉인 무결성을 검사한다.
모델 0회는 관측된 사전 서버 거부만 허용하며 모델 응답 표본으로 세지 않는다.
B의 처음 비교할 수 없는 HOLDOUT cohort와 필수 검토 대기는 INCOMPLETE로 유지한다.
B를 본 검증 PASS로 올리거나 검토 대기를 품질 기준 미달로 바꾸지 않는다. 관측된 oracle·보안 위반은 계속 FAIL이다.

Q는 B와 같은 필수 cohort를 비교한다. 역할·split·case version·모델/관측 버전·lane·축을 다른 성공 표본으로 대체하지 않는다.
P/G/B/Q의 서로 다른 runId/digest, 참조의 완료 시각·계획 분모·사람 검토와 P의 실제 FROZEN 근거를 검증한다.
실패한 Q를 사후 B로 바꾸는 것은 봉인된 purpose 검증에서 거부한다. HOLDOUT 결과로 기준·prompt·oracle을 조정하지 않는다.

## 재현한 Runtime identity 문제와 제한된 수정

선택된 cell만 준비하면 같은 반복에서도 PILOT은 Tenant 18개, HOLDOUT 포함 baseline은 19개를 추가했다.
실제 MySQL 준비 단계에서 재현했으며 데이터 규모를 comparison digest에서 제외하지 않았다.
v3 Runtime 계획은 유한 `fixtureCells`를 별도로 고정한다. 모든 수집 cell을 포함하는 같은 준비 목록을
P/G/B/Q에서 사용하고, 실제 수집 분모는 계속 `cells`다. 준비한 HOLDOUT을 PILOT에서 호출하는 것은 금지한다.
준비 목록도 별도 snapshot·comparison identity로 봉인한다. 승인된 준비 목록을 넘는 표본 확대에는 새 계획이 필요하다.

Runtime이 생성하는 실행 manifest에는 계획에 들어 있는 승인된 criteria 버전을 반영한다.
버전만 맞춘다고 FROZEN이 되는 것은 아니며 기존 승인·적용·pilot provenance 검사를 모두 통과해야 한다.

임시 MySQL/HTTPS 포트는 기존 disposable target 검사와 동일한 제한된 정규화만 사용한다.
v3 Runtime의 `frozenIdentity.configDigest`는 source-build의 `runtimeFreezeConfigDigest`에 대응한다.
이 digest는 전체 manifest에서 검증된 임시 포트만 정규화하며 criteria·terms 시각·호출 상한·data·기타 설정을 유지한다.
raw manifest와 `source-build.configDigest`, cell record의 configDigest는 실제 bytes로 계속 봉인·재검산한다.
같은 포트 처리 조건을 입증할 수 없으면 주소 전체를 비교한다. 새 비검증 값을 제외하지 않는다.

QUALIFICATION은 clean source와 정확한 build/source/dataset/oracle/scorer/config/criteria identity를 요구한다.
기존 작업 트리의 사용자 `.gitignore` 수정은 제외·보존했으며 실제 호출은 같은 커밋의 깨끗한 작업 환경에서 준비해야 한다.

## 검증과 후속 작업

| 검증 | 결과 |
| --- | --- |
| v1 평가 회귀·fixture 검증 | 351개 성공, 실패·건너뜀 0 |
| v2 oracle·fixture·Runtime | oracle 36개 / Runtime 4개 성공, 실패·건너뜀 0 |
| v3 관련 회귀·fixture·Runtime | 채점/계약 14개 + Manager 통합 2개, Runtime 4개 성공. 마지막 채점 회귀 14개도 재통과 |
| `m8SecurityRegression` | 81개 성공. 필수 보안 19개 PASS, 전체 M8 INCOMPLETE |
| `./gradlew.bat --offline test --console=plain` | 총 1,413개: 성공 1,399 / 건너뜀 14 / 실패·오류 0 |
| `./gradlew.bat --offline clean build --console=plain` | BUILD SUCCESSFUL. 총 1,413개: 성공 1,399 / 건너뜀 14 / 실패·오류 0 |

명령은 `backend/`에서 실행했다. 기본 v1 전체 suite가 건너뛴 14개 중 신규 v3 전용 통합 2개는 위 v3 선택 검증에서 성공했다.
나머지는 기존 별도 opt-in/외부 evidence 조건부 검증이며 실제 provider·로컬 모델·별도 Kafka/DB 증거 등을 실행했다고 표시하지 않는다.
전체 suite의 조건과 건너뜀 목록은 로컬 JUnit에 보존한다. Frontend는 변경하지 않아 검증 대상이 아니다.

호출 없는 채점 회귀에서 P/G/B/Q 순서와 참조·cohort·모델 버전 불일치, 같은 실행 재사용,
미완료 참조·usage 누락·사람 검토 누락·HOLDOUT 오염을 검사했다. 합성 참조는 실제 호출/사람 승인 근거가 아니다.
별도 runId의 v3 합성 자료 2개를 봉인하고 각각 다른 JVM에서 재계산했으며 v1/v2 profile로의 재분류는 거부했다.

다음은 실제 MySQL/Runtime/lexical 경로를 연결한 **결정적 검증**이다. 실제 Gemini 표본이나 P/G/B/Q가 아니다.

| runId | evidence digest | 보존한 판정 |
| --- | --- | --- |
| `runtime-extended-fbf45507-33d3-433a-8975-c04aa22b9226` | `548c9887c815e1a3d265ec539f10c2488b8946f33fac6e037ef15bf9a4cedf10` | 19개: FAIL 6 / INCOMPLETE 13. 기존 18개에 Manager 1개 추가, hard gate 위반 0 |
| `runtime-901734ee-3fd6-4e93-a04b-30e524cae603` | `34f5f4de4a477da83a14564b1349d8c2c5453d49a439cb3991d05e70629f7fde` | 7개 INCOMPLETE |
| `runtime-answer-fault-1b135334-8187-4b44-a074-75e434602b1b` | `a0c4ab44f9cef506248ce632c568b2523f31f47ed9dd350d9554014456324616` | 7개 INCOMPLETE |
| `retrieval-ff9ab2d5-a9b0-4395-9da1-5fdbe5d4dea6` | `92e7f4a0cdc702cc1b401346cf27a8f3b8bf542e6b1e663b68ab2d74a5a16a6f` | 20개: FAIL 1 / INCOMPLETE 19 |

Manager의 자동 scope·문서/버전·publication metadata·source/claim·금지 전송·독립 DB 검사는 PASS,
사람 검토는 INCOMPLETE다. 위 FAIL은 실제 모델 품질 판정이 아니며 기존 실패를 성공으로 재분류하지 않았다.
각 ignored run 디렉터리의 plan/source-build에 정확한 source/build/config/dataset/oracle/scorer identity가 있다.
봉인된 v3 빌드와 기존 `M8Evaluation.main`을 사용해 각 실행을 독립 JVM에서 재계산했고 결과 JSON/한국어 결과표 8개가 모두 일치했다.

v1/v2 원본 JSON 13개의 bytes와 기존 `.gitignore` 변경을 보존했다. 과거 9개 실행은 당시 소스·빌드로
별도 프로세스에서 재계산하여 결과 JSON과 한국어 결과표 18개가 모두 바이트 단위로 일치했다.
과거 v1 실제 66회는 별도 이력이며 v3 평가/baseline에 합산하지 않았다. 로컬에 없는 중단 pilot 자료를 복구했다고 주장하지 않는다.

원자료·JUnit·봉인·재계산·불변성 확인은 Git에서 제외된
`build/m8-evidence/issue193-v3-implementation-591fba2f-388b-4073-8d80-5d15837d7915/`에 보존한다.
개발 중 Docker 미실행, 준비 질의, 선택 dataset을 잘못 읽던 검증 경로의 실패 로그도 남겼다.

실제 호출에는 현재 무료 전용 프로젝트·credential 적격성·quota·약관/합성 자료 전송 조건을 다시 확인하고
정확한 계획의 유한 호출/token/시간/USD 0 예산에 대한 별도 명시적 승인이 필요하다.
실제 P/G, P의 근거에 따른 사용자 FROZEN 승인·적용, 사전 지정 B, 독립 Q, 필수 사람 검토·비교·봉인·재계산은 남아 있다.
이번 PR로 #193을 닫거나 #185 GO를 선언하지 않는다.
