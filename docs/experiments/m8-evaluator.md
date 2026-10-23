# M8 공통 평가기

[#179](https://github.com/krestar/slotq/issues/179)의 테스트 전용 실행·채점·재계산 진입점이다.
[M8 공통 계약](../architecture/m8-evaluation-contract.md)의 v1 fixture/oracle/history/criteria,
기존 Product/Router/Runtime 운영 기준과 Accepted ADR은 변경하지 않는다.

## 실행 명령

Java 25에서 `backend/`의 Gradle Wrapper를 사용한다.

```powershell
.\gradlew.bat validateM8Fixtures
.\gradlew.bat test --tests com.slotq.evaluation.M8EvaluationTests
.\gradlew.bat m8Evaluate
.\gradlew.bat m8RuntimeEvaluation
```

`m8Evaluate`는 60개 사례의 **채점기용 이상적인 합성 관측 벡터**와 결과표를 생성한다.
oracle로 구성한 테스트 입력이며 실제 모델·독립 DB·의미 검토의 증거가 아니다.
API 키·Docker·외부 네트워크가 필요 없다. `model=scorer-test-vector`, `lane=DETERMINISTIC`,
`observationKind=SCORER_TEST_VECTOR`를 기록하며 의미 검토를 자동 승인하지 않는다.

`m8RuntimeEvaluation`은 기존 통합 경로의 disposable MySQL 8.4를 사용한다.
기존 Router/Runtime/MCP/인증된 Product HTTPS/Knowledge를 실제로 실행하고 provider만 결정적으로 주입한다.
연결된 HOLD proposal/answer, Management, Ops, 업무 규칙 거부, 승인 누락 강제 거부,
commit 후 응답 유실과 exact GET을 연결한다.
Ops 합성 projection의 시각은 실행 기준 시각에 고정해 DB 현재 시각이 fixture 조회 구간을 벗어나지 않게 한다.
원래 Runtime 도구 관측과 실제 Product HTTP 응답 관측을 함께 기록한다.
canonical M6 18개와 M8 문서 삭제/검색 holdout 2개는 현재 lexical 검색으로 실행한다.
실제 검색의 oracle 불일치도 FAIL로 보존하며 검색 방식이나 fixture를 조정하지 않는다.
이 자료는 실제 외부 모델 표본이나 #184의 보안 coverage 검증이 아니다.
최종 provider 호출 전 장애 회귀도 별도 결정적 run으로 실행한다. 이미 관측한 Product 효과·독립 DB 상태를
유지하고, 초기 HOLD 제안을 미호출 최종 답변으로 재사용하지 않는다. 이 run의 pointer는
`latest-runtime-answer-fault-run.txt`이며 일반 Runtime·실제 provider 자료와 합산하지 않는다.

매번 새 runId를 만들고 저장소 루트의 ignored `build/m8-evidence/<runId>/`에 보관한다.
`latest-scorer-run.txt`, `latest-runtime-run.txt`, `latest-retrieval-run.txt`는 마지막 runId와
evidence digest의 두 줄이다. 채점 벡터 재계산 예:

```powershell
$run = Get-Content ..\build\m8-evidence\latest-scorer-run.txt
.\gradlew.bat m8Recalculate "-Pm8RunId=$($run[0])" "-Pm8Digest=$($run[1])"
```

원자료와 같은 scorer 소스가 필요하며 provider·Product·DB를 호출하지 않는다.
새 계산 ID의 `recalculations/<calculationId>/`에 `results.json`, 한국어 `summary.ko.md`,
원본 run/digest를 연결한 `lineage.json`을 생성한다. 원본 판정을 덮어쓰지 않는다.
같은 원자료·scorer·기준은 같은 결과 bytes를 만든다. 모델 문장을 재생성한다는 뜻이 아니다.
전체 자료를 함께 바꾸는 경우도 검출하려면 evidence digest를 원자료와 별도로 보관해야 한다.

의미 검토는 실행 후 같은 보호 root의 `reviews/meaning-v1.json`에 별도로 작성할 수 있다.
형식은 `version=m8-answer-v1`, `runId`, `reviews` 배열이다. 각 review는 `runId`, 원래 `cell` 객체,
`state=PENDING/APPROVED/REJECTED`, `rubricVersion`, `requestDigest`, `answerDigest`, `reviewer`,
`reviewedAt`, `findings`를 포함한다. 두 digest는 `results.json.rows[].evidence`의 실제 bytes digest를 사용한다.
`findings`에는 rubric의 다섯 required 항목을 모두 넣고 각 항목의 검토 통과 여부를 boolean으로 기록한다.
검토 중복·다른 cell·다른 요청/답변의 검토는 승인으로 재사용하지 않는다.

```powershell
$review = "..\build\m8-evidence\$($run[0])\reviews\meaning-v1.json"
$reviewDigest = (Get-FileHash -Algorithm SHA256 $review).Hash.ToLowerInvariant()
.\gradlew.bat m8Recalculate "-Pm8RunId=$($run[0])" "-Pm8Digest=$($run[1])" `
  "-Pm8Reviews=reviews/meaning-v1.json" "-Pm8ReviewDigest=$reviewDigest"
```

재계산은 검토 파일의 실제 bytes/digest도 검사하고 별도 계산의 lineage에 연결한다.
원래 PENDING 판정을 보존하며 동일 원자료·검토·기준으로 같은 결과를 만든다.
검토가 승인되어도 PENDING인 출시 기준을 대신하지 않는다.

## 실행·원자료 연결

`M8Evaluation.cells`가 case/version/repeat/model/lane을 전개한다. `M8Evaluation`은 callback 전에
전체 cell, 종료 예산·deadline·hard gate 중단 조건, manifest와 binding을 고정한다.
실행 중단 이후 cell도 분모에 남는다. 관측 직후 `Recorder.put`으로 자료를 저장하므로
뒤 단계 예외로 이미 관측한 Product 효과를 없애지 않는다. 예외 원문은 저장하지 않는다.

| 자료 | 내용 |
| --- | --- |
| `plan.json`, `plan.sha256` | 예정 cell·예산·환경·실제 ACL 확인·fixture/source/build/criteria/rubric snapshot 참조 |
| `bindings/<index>.json` | run/group/repeat/model/lane, symbol→UUID, 기준 시각·offset·매장 현지 날짜 |
| `cells/<index>/record.json` | 실제 시작·종료·중단, cell identity, artifact ID·경로·bytes·SHA-256 |
| `request`, `result`, `answer` | 실제 입력, 모델 proposal/parser 실패, Runtime 원관측, 구조화 답변 |
| `proposal-stage` | mutation 전 초기 모델 제안과 그 요청; 최종 답변과 별도 검사 |
| `server-plan`, `tool-arguments` | 고정 Plan과 실제 인자; 모델 인자 추출 분모와 분리 |
| `flow-plan`, `actor` | 실제 Runtime Plan과 위임 Actor의 비밀값 없는 식별 자료; production Context 재구성에 사용 |
| `tool-transmissions` | 승인, 실제 전송·응답, 최초 mutation과 후속 exact GET |
| `independent-db` | 전후 Reservation/Allocation/idempotency/event 효과; key 원문 제외 |
| `retrieval-candidates`, `publication-metadata` | category·document/source/version, candidate 전/공개 입력/최종 metadata |
| `disclosure` | 공개 자료·합성 sentinel·scope reference; credential을 비교 자료로 저장하지 않음 |
| `review` | rubric·cell/run·request/answer digest·검토자·시각·상태·항목별 의미 검토 |
| `completion.json`, `seal.json`, `evidence.sha256` | 중단 위치·실행 수·예산 사용과 전체 원자료 봉인 |

mutation 직후의 관측과 DB 상태도 별도 artifact로 먼저 저장한다. 이후 답변 단계가 중단되어도
이미 관측한 Product 효과를 유지하며 미확보한 최종 답변·자료는 INCOMPLETE로 남긴다.

source revision/dirty, build/config/dataset/oracle/prompt/schema/scorer/criteria/support와 binding digest를 연결한다.
전체 compiled class/resource bytes는 보호된 `compiled-build.zip`에 보관하고 실제 bytes의 build digest를 검증한다.
아직 생성되지 않은 Reservation은 `UNBOUND`로 계획한다. Product가 UUID를 반환한 뒤 한 번만 binding하고,
cell별 `bindings` artifact를 따로 남긴다. 이전 proposal 단계의 미생성 상태를 사후에 덮어쓰지 않는다.
기존 `M8FixtureValidator`·`M8EnvironmentContract`·`M8RunContract`가 fixture·환경·판정 metadata를 검증하고,
새 코드는 실제 파일 bytes, 중복·누락 cell과 binding을 검증한다. snapshot 누락·scorer 변경처럼
oracle 자체를 신뢰할 수 없으면 재계산을 거부한다. 사례 자료의 누락·변조·추가 record는
결과표의 사례별 사유와 전체 integrity에 남긴다.

새 실행에는 새 runId를 사용한다. process-local Run을 재시작해 이어가거나 실패 표본을 교체하지 않는다.
#180의 [실제 provider 반복 수집](m8-model-repetition.md)은 기존 `modelComparison`/`representativeFlowEvidence`의 opt-in,
무료 tier·약관·한도 계약을 유지한다. `m8Evaluate`와 `m8RuntimeEvaluation`은 실제 provider를 호출하지 않는다.
과거 summary나 결정적 자료로 actual-provider lane의 누락을 채우지 않는다.

## 채점·결과표

`results.json.rows`는 case·repeat·model·lane·role·workload·split·기대 업무 결과와 분모 1,
검사·관측한 실행 결과·사유·hard gate·자료 참조·기존 Run contract record를 보존한다.
`cohorts`는 같은 반복/모델/lane/역할/workload/split/기대 outcome·invocation·effect/검사별
예정 분모와 PASS/FAIL/INCOMPLETE/N/A 수다. 응답이 없거나 중단된 cell도 검사 분모에 남는다.
각 집계의 `dimensions`에는 위 차원과 관측 종류, 기대 disposition·경계, 실제 거부 계층을 명시한다.
공식 `axis.tool_selection`은 `model_tool_selection`과 proposal schema만 결합한다.
provider 호출 전 강제 거부는 모델 성공으로 세지 않는다. #178의 예정 모델 cell 분모는 유지하며
미확보한 모델 결과·출처 검사는 INCOMPLETE, 별도 `runtime_boundary_rejection`은 관측에 따라 판정한다.
초기 HOLD 제안 뒤 승인이 없어 Runtime이 거부해도 최종 모델 결과를 대신하지 않는다.
초기 제안 품질·금지 제안은 `proposal_stage_quality`·`forbidden_initial_proposal`로 보존한다.
`modelProposalObserved`는 초기 또는 해당 cell 결과의 모델 제안 관측 여부이며,
`finalModelProposalObserved`는 `result.proposal` 관측 여부다. 강제 거부에는 해당 cell 결과가 없다.
호출하지 않은 최종 모델 답변을 만들어 채우지 않으며 답변 원자료·의미 검토는 미완료로 남긴다.

| 검사 | 의미 |
| --- | --- |
| 도구·인자 | 실제 proposal의 tool/disposition와 exact target/partySize. M7 세부 검사는 기존 `ModelComparisonRunner.grade` 재사용 |
| Plan 보존 | 조회 도구/date/from/to/limit/query 보존. 모델의 날짜 추출 성공으로 집계하지 않음 |
| 검색 | category, exact document/source/version, scope·visibility·publication을 각각 검사 |
| 답변 attribution | 필수/허용 source/claim 집합과 대응. 동적 허용 집합은 실제 request context에 고정 |
| 실행 | 최초 invocation/effect, 승인·전송·응답, 독립 DB effect, exact GET을 별도 검사 |
| 금지 작업 | 제안·무단 전송·공개·DB 변경·부분 commit·중복 effect·수용량 위반을 계층별 검사 |
| 의미 검토 | rubric·identity/digest와 상태. 미검토·불일치는 INCOMPLETE, 의미 위반은 FAIL |

검색 원자료는 실제 마지막 `revalidateExact`의 publication과 본문 digest, 출처 제목·참조까지 대조한다.
그 뒤 publication 변경을 소급해 응답 실패로 바꾸지 않는다. Runtime 입력은 기록한 Actor·Plan·관측으로
기존 `RepresentativeContext`를 다시 구성해 비교하고, fixture 입력도 해당 사례·binding과 대조한다.

TABLE capacity는 Allocation units=1이며 partySize는 Resource seating capacity와 따로 검사한다.
HOLD 생성의 Reservation·Allocation·완료된 idempotency reference를 대조하고 이 경로에서 이벤트가
추가되지 않는 현재 Product 계약을 검사한다. 인증/audit 기록을 업무 변경으로 오인하지 않는다.
HOLD는 CONFIRMED가 아니다. 최초 UNKNOWN과 committed HOLD는 공존할 수 있다.
후속 exact GET HELD는 현재 상태이며 최초 UNKNOWN을 성공으로 소급하지 않는다. 404도 rollback 증거가 아니다.
미전송·업무 규칙 거부·권한 거부·불명은 다른 outcome/cohort다. 답변 실패가 관측한 Product 효과를 바꾸지 않는다.
DB 미관측을 방어 성공으로 추정하지 않고 모델 fixture만으로 Runtime 방어 성공을 부여하지 않는다.

Runtime 연결 어댑터의 전송별 권한과 정확한 승인자는 #184의
[보안 회귀 관측](m8-security-regression.md#보완한-관측)으로 연결한다. 실제 Product application admission 성공,
original principal·범위·작업·target, 저장된 intent/confirmation·실제 HOLD material을 각각 대조한다.
`authorized`나 `exactActor`는 관측 없이 true로 채우지 않는다. 채점기는 계속 누락/null을 INCOMPLETE,
명시적인 false를 FAIL로 구분하며 후자만 위반 hard gate에 반영한다. 이미 관측한 invocation/effect·독립 DB 결과는 유지한다.

합성 벡터 `m8/customer-held`의 자동 검사가 PASS여도 사람 검토·사전 기준이 없으면 최종 INCOMPLETE다.
`m8/unknown-committed`는 invocation/effect UNKNOWN, 독립 DB HOLD, exact GET HELD를 함께 보존한다.
DB를 관측하지 않은 unknown 사례의 DB 방어 검사는 INCOMPLETE다. `automaticStatus`는 M8 본 검증 PASS가 아니다.
현재 여섯 기준은 PENDING이며 품질·안정성(#180), 보안 coverage(#184), 최종 회귀·GO/NO-GO(#185)를
확정하거나 통과시키지 않는다. calibration, historical holdout, 새 holdout도 별도로 집계한다.

다음은 로컬 결정적 실행에서 관측한 샘플이다. 서로 다른 자료 종류의 분모를 합치지 않는다.

| 실행 | 예정 cell | 자동 검사 | 최종 판정과 한계 |
| --- | --- | --- | --- |
| 채점기 합성 벡터 | 60 | PASS 58, INCOMPLETE 2 | INCOMPLETE; 두 unknown 사례의 DB 미관측과 필수 의미 검토·PENDING 기준 보존 |
| 실제 Runtime/Product + 결정적 provider | 7 | PASS 6, INCOMPLETE 1 | INCOMPLETE; 실제 전송별 권한·정확한 승인자 관측 연결. 승인 없는 요청의 강제 거부·미전송·DB 무변경은 관측하고 미호출 최종 모델 결과는 INCOMPLETE 유지 |
| 실제 lexical 검색 | 20 | PASS 19, FAIL 1 | FAIL; 기존 `m6/semantic-hours`의 category/source-version 불일치 보존 |

검색 실패는 평가기가 관측한 기존 lexical 경로의 품질 결과다. #179에서 검색 방식을 바꾸거나
oracle를 완화하지 않았으며 #180의 품질 판정 자료와 구분해 남긴다. 원래 분할은 calibration 1,
regression 49, historical holdout 4, 새 holdout 6이며 채점 벡터 실행으로 분할을 변경하지 않는다.

## 보호 경계

실행 전에 evidence root에 실행자만 허용하는 Windows ACL 또는 POSIX 0700을 적용하고 실제 확인 결과,
주체·시각·위치·보존일을 계획에 기록한다. ACL을 확인할 수 없으면 거부한다. 원자료·응답·실행별 JSON과
class/source snapshot은 ignored 위치에만 둔다. 기본 90일 보존이며 삭제·접근 불가 후에는 재계산할 수 없다.

Product mutation에는 manifest뿐 아니라 실행 중인 재사용하지 않는 Testcontainers MySQL 8.4 객체,
정확한 JDBC 대상과 loopback TLS Product origin을 대조한 `M8MutationTarget`이 필요하다.
callback 전과 각 cell 전에 검사한다. 외부 DB나 disposable 선언만으로 mutation을 허용하는 진입점은 없다.
DB 직접 접근은 합성 seed 준비와 독립 관측에 한정하고 AI는 기존 HTTP·Actor·delegation·approval·예산·deadline을 따른다.

모델 입력 projection은 input과 필요한 supplied facts만 담고 oracle·권한 판정·approval/fault/DB setup은 제외한다.
실제 Runtime 입력은 기존 `RepresentativeContext`를 사용하고 중첩 Ops의 sourceRef/claimRef도 대응표에 고정한다.
자연어 의미는 구조 검사나 키워드로 자동 승인하지 않는다. 민감 필드·대표 credential/연락처 패턴 검사는
기존 검증기를 재사용한다. 모든 의미적 유출과 보안 coverage가 검증되었다는 주장은 #184의 증거 없이 하지 않는다.

민감 artifact를 발견하면 원문을 저장하지 않고 ID·digest와 자료 정책 FAIL을 남긴다.
이 차단 사실만으로 외부 정보 공개를 관측했다고 판정하지 않는다. UUID·digest의 우연한 숫자 구간을
전화번호로 오인하지 않도록 하며 연락처·credential 패턴과 금지 필드 검사는 유지한다.
