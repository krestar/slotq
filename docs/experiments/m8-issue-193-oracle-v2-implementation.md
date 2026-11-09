# #193 승인된 oracle v2 적용

사용자가 2026-11-07에 PR #198 커밋 `7460ba7`의 `issue193-oracle-v2-draft-1`을 최종 승인했다.
[상세안](m8-issue-193-oracle-v2-proposal.md)과 [당시 before/after JSON](m8-issue-193-oracle-v2-proposal.json)을
보존하고 최소 구현을 적용했다. 이번 승인은 구현·로컬 검증에 한정하며 신규 Gemini 생성·metadata 요청은 **0회**다.
#193은 계속 INCOMPLETE이며 PR은 `Refs #193`으로 연결한다. merge와 #185 출시 판정은 수행하지 않는다.

## 적용 내용

- `evaluation/m8-v2/`에 dataset/oracle `2.0.0`과 승인 이력을 추가했다. 두 REGRESSION 사례만 version `2.0.0`으로
  정정하고, 나머지 58사례와 분모·분할·필수 여부·execution/forbidden 기대값은 보존했다.
- v1이 기본값이며 v2는 `-Pm8DatasetVersion=2.0.0`으로 명시적으로 선택한다. v2의 scorer는 `m8-scorer-v2`다.
  같은 run의 선택 버전·manifest·case version·봉인 snapshot 불일치를 거부한다. 실행 record 형식은 `1.0.0`이다.
- `customer-business-rejected`는 실제 고정 request context를 허용 집합으로 사용하고 `product:mutation` 출처·주장을 요구한다.
  `unknown-committed`에는 `product:mutation`, `product:current`와 각각의 근거 대응이 필수다.
- UNKNOWN의 ANSWER 조건은 최초 UNKNOWN·정확한 target의 실제 GET 200/HELD·전송 전 고정 request 근거가 모두
  있을 때만 적용한다. 누락은 INCOMPLETE, 관측된 모순은 FAIL이다. tool/outcome·권한·승인·독립 DB·금지 효과 검사는
  계속 수행하며, 확인된 FAIL을 누락으로 덮거나 실제 답변에 맞춘 disposition fallback을 하지 않는다.
- prompt에서 도출한 context/mapping과 기록 request, 실제 provider에 전달된 최종 request를 대조한다.
  reference 집합의 배열 순서만 Set 의미로 비교하며 prompt·context·근거 대응표·그 밖의 값은 그대로 대조한다.
- 승인된 변경안의 canonical digest, before/after와 history, 승인·적용 순서를 검증한다.
  `oracle-change.json`도 봉인하고 비교 identity에 포함한다. 승인 시각은 사용자 메시지 수신 후 기록한 시각이다.
  이 파일의 `sourceRevision`은 승인안 작성 당시 소스이며, 실제 실행 소스·빌드는 각 run의 `source-build`가 기록한다.
- 기준은 여전히 PENDING이다. 기존 rubric의 실제 사람 검토가 필요하며, 최초 UNKNOWN 생략·성공 소급·확정 표현을
  구조화된 claim 검사 통과만으로 승인하지 않는다. 계약 파일의 APPROVED와 답변 검토 APPROVED를 구분한다.

production Runtime·Router·API·DB·migration과 실제 모델/API·재시도 정책은 변경하지 않았다.
`M8RunContract`의 기존 caseVersion 대조가 새 사례 version을 직접 검사하므로 해당 형식은 확장하지 않았다.

## 로컬 검증

PowerShell에서 소수점이 들어간 Gradle property는 인자 전체를 따옴표로 감싼다.

```powershell
# backend/; 아래 명령은 외부 Gemini 호출을 사용하지 않는다.
.\gradlew.bat --offline test --tests 'com.slotq.evaluation.*' validateM8Fixtures m8RuntimeEvaluation
.\gradlew.bat --offline test --tests 'com.slotq.evaluation.M8OracleV2Tests' validateM8Fixtures m8RuntimeEvaluation '-Pm8DatasetVersion=2.0.0'
.\gradlew.bat --offline test m8SecurityRegression
.\gradlew.bat --offline clean build
```

| 검증 | 결과 |
| --- | --- |
| 개발 중 평가기 회귀·v1 fixture | 평가기 334건 통과, 실패·오류·skip 0; fixture 통과 |
| 최종 v2 회귀·fixture | 36건 통과, 실패·오류·skip 0; fixture 통과 |
| v1 / v2 Runtime 연결 | 각각 4건 통과, 실패·오류·skip 0 |
| 전체 Backend test (2026-11-07) | 총 1,397건: 통과 1,385건, 건너뜀 12건, 실패·오류 0 |
| 보안 회귀 (2026-11-07) | 81건 통과, 실패·오류·건너뜀 0. 필수 19사례 결정적 보안 PASS, M8 전체 INCOMPLETE |
| clean build (2026-11-10) | 성공. 전체 test 재실행에서도 총 1,397건: 통과 1,385건, 건너뜀 12건, 실패·오류 0 |

2026-11-07의 첫 clean build는 사용자 요청으로 중단했다. 중단 로그는 보존하고,
2026-11-10에 코드 변경 없이 `clean build`를 재실행해 성공했다. 건너뛴 12건을 통과로 집계하지 않았다.
현재 종료 코드·JUnit 및 이전 전체 test의 건수를 대조했다. 원격 CI 결과는 확인하지 않았다.

새 회귀는 승인 범위·history·버전·근거 대응 변조, 정확한 GET 누락/404/다른 대상/다른 상태,
누락 중에도 잘못된 tool/outcome·승인 위반·부분 commit을 FAIL로 유지하는 경계를 검사한다.
독립 v2 JVM 간 identity와 보고서 재계산 일치를 확인하고, v1 모드에서 v2 run을 재계산하는 요청을 거부한다.
답변 검토 대기는 INCOMPLETE, 합성 검토 거부는 FAIL, 잘못된 검토 digest는 INCOMPLETE로 유지한다.
이 합성 검토 회귀는 실제 사람 검토 승인이 아니다.

첫 독립 JVM 회귀에서 reference Set의 직렬화 순서를 요청 차이로 오인한 결함을 확인하고 수정했다.
v1 fixture 검사에서는 중복 버전을 `Set.of`로 구성한 오류를 수정했다. 관련 회귀를 다시 실행했다.
Runtime 재계산 명령에 잘못된 digest 인자가 한 차례 전달돼 거부됐고, 실제 seal digest로 재실행했다.
원본을 수정하거나 해당 거부를 성공으로 처리하지 않았다.

## 새 결정적 Runtime 관측

v2 Runtime 18사례 run은 `runtime-extended-98e7b0b1-26d8-4f2a-a3da-604dc72e6e74`,
evidence digest는 `0cf93b4645b2e1416ccd80f69414c393314d30b3df640c5bce9e357a6042616b`다.
봉인 파일 316개를 검증했고 별도 JVM의 동일 identity 재계산에서 보고서 2개의 SHA-256이 원본과 같았다.
2026-11-10의 clean build 후에도 v2 모드로 재계산해 같은 보고서 bytes를 확인했다.

자동 판정은 PASS 7 / FAIL 6 / INCOMPLETE 5, 최종 판정은 FAIL 6 / INCOMPLETE 12다.
두 정정 사례는 각각 자동 PASS·최종 INCOMPLETE(사람 검토 대기)다.
이는 새 결정적 실행의 결과이며, 과거 FAIL의 취소나 새 실제 모델 품질 PASS가 아니다.
다른 사례의 FAIL과 실제 lexical 결과는 그대로 유지하고 HOLDOUT으로 계약을 조정하지 않았다.

이 run은 `sourceRevision=7460ba7e8491de150c5535a73bf1fc9b3eaae0d6`, `sourceDirty=true`인 구현 작업 공간에서
실행했다. 사용한 소스·compiled build를 봉인했으며 clean source의 실제 QUALIFICATION으로 주장하지 않는다.
scorer digest는 `85879e7b4e00058c51082523b6f106986ea715f044b2051392e25140ee782f23`,
build digest는 `611ca91e01cd1c46b2e0d1df5acf9b5a7c9aa8cc7befce33715e553e14b666d4`다.

## 보존과 후속 평가

v1 원본과 기존 8개 run의 원본 보고서 digest를 대조해 불변을 확인했다.
2026-11-10 최종 확인에서 기존 8개 seal·봉인 파일 2,235개와 원본 보고서, 승인 당시 JSON의 불변을 다시 확인했다.
기존 66회·과거 중단 pilot의 runId·seal·digest·FAIL/INCOMPLETE와 누락은 수정하지 않았다.
v2는 새로운 dataset/oracle/history/scorer identity이므로 과거 결과를 비교 가능한 새 baseline이나
새 scorer의 성공 표본으로 소급 집계하지 않는다. 과거 재계산에는 당시 소스·빌드가 필요하다.

보호된 자료 위치는
`build/m8-evidence/issue193-oracle-v2-implementation-0d2bb5e1-7585-4460-bc77-4b147b3dabb4/`다.
JUnit·전체 검증 로그·실행 identity·봉인 검증·재계산 비교를 보관하며 키·요청/응답 원문은 Git/PR에 포함하지 않는다.
재개한 clean build의 로그는 `clean-build-resume-20261110.log`, 결과는 `clean-build-verification.json`과
`final-preservation.json`에 기록했다. 이전 중단 로그를 덮어쓰지 않았다.

새 실제 PILOT·독립 baseline·quality/security FROZEN 승인·필수 사람 검토·QUALIFICATION은 미완료다.
후속 실제 수집은 커밋된 새 identity와 명시적 호출 승인으로 별도로 수행해야 한다.
이번 구현·검증 통과만으로 #193을 닫거나 #185 GO를 선언하지 않는다.
