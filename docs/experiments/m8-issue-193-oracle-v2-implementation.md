# #193 승인된 oracle v2 적용

사용자가 2026-11-07에 커밋 `7460ba7`의 `issue193-oracle-v2-draft-1`을 최종 승인했다.
[승인 당시 상세안](https://github.com/krestar/slotq/blob/7460ba7e8491de150c5535a73bf1fc9b3eaae0d6/docs/experiments/m8-issue-193-oracle-v2-proposal.md)과
[before/after 원본](https://github.com/krestar/slotq/blob/7460ba7e8491de150c5535a73bf1fc9b3eaae0d6/docs/experiments/m8-issue-193-oracle-v2-proposal.json)은 해당 커밋에 보존된다.
적용된 승인·변경 이력은 [oracle-change.json](../../backend/src/test/resources/evaluation/m8-v2/oracle-change.json)이 기록한다.
승인은 구현·로컬 검증에 한정하며 신규 Gemini 생성·metadata 요청은 **0회**다. #193은 INCOMPLETE다.

## 적용 범위

- dataset/oracle `2.0.0`, scorer `m8-scorer-v2`에서 두 REGRESSION 사례만 정정했다.
  나머지 58사례와 분모·분할·필수 여부·execution/forbidden 기대값은 보존했다.
- v1이 기본값이다. v2 실행·수집·재계산은 `-Pm8DatasetVersion=2.0.0`으로 선택한다.
  PowerShell에서는 인자 전체를 따옴표로 감싼다. 실행 record 형식은 `1.0.0`을 유지한다.
- `customer-business-rejected`는 전송 전 고정 request context를 허용 집합으로 사용하며
  `product:mutation` 출처·주장을 요구한다. `unknown-committed`는 `product:mutation`·`product:current`와 각각의 근거 대응을 요구한다.
- UNKNOWN의 ANSWER·outcome UNKNOWN은 최초 mutation UNKNOWN, 정확한 대상의 실제 GET 200/HELD,
  전송 전 고정 request 근거를 모두 충족할 때만 적용한다. 관측 누락은 INCOMPLETE, 모순은 FAIL이다.
  tool/outcome·서버 권한·승인·독립 DB·금지 효과·멱등성·롤백 검증은 유지한다.
- prompt에서 도출한 context/mapping, 기록 request와 실제 provider 전달 request를 대조한다.
  reference 배열만 Set 의미로 비교한다. 승인안 digest·before/after·history·승인/적용 순서와 버전·봉인 snapshot 일치를 검사한다.
- `oracle-change.json`도 봉인하고 비교 identity에 포함한다. 파일의 `sourceRevision`은 승인안 작성 소스이며
  실제 실행 소스·빌드는 각 run의 `source-build`가 기록한다.
- 최초 UNKNOWN과 현재 HELD를 모두 설명하고 HELD를 CONFIRMED로 표현하지 않았는지는 실제 사람 검토가 필요하다.
  계약 APPROVED를 답변 검토 승인으로 취급하지 않는다. quality/security 기준은 PENDING이다.

production Runtime·Router·API·DB·migration, 모델/API와 재시도 정책은 변경하지 않았다.
HOLDOUT으로 계약·기준을 조정하지 않았으며 확인된 FAIL을 누락이나 PASS 비율로 상쇄하지 않는다.

## 구현 당시 검증

아래는 문서 정리 이전에 완료한 검증이다. 문서 정리 후 테스트·빌드는 실행하지 않았다.

| 검증 | 당시 결과 |
| --- | --- |
| 평가기 회귀·v1 fixture | 평가기 334건 통과, 실패·오류·건너뜀 0; fixture 통과 |
| v2 회귀·fixture | 36건 통과, 실패·오류·건너뜀 0; fixture 통과 |
| v1 / v2 Runtime 연결 | 각각 4건 통과, 실패·오류·건너뜀 0 |
| 전체 Backend test (2026-11-07) | 총 1,397건: 통과 1,385건, 건너뜀 12건, 실패·오류 0 |
| 보안 회귀 (2026-11-07) | 81건 통과; 필수 19사례 결정적 보안 PASS, M8 전체 INCOMPLETE |
| clean build (2026-11-10) | 성공. test 1,397건: 통과 1,385건, 건너뜀 12건, 실패·오류 0 |

11월 7일 중단된 clean build 로그는 보존하고 11월 10일 코드 변경 없이 재실행했다. 원격 CI는 확인하지 않았다.
회귀는 승인 범위·history·버전·근거 대응 변조, GET 누락/404/다른 대상/상태, 승인 위반·부분 commit,
사람 검토 identity와 독립 JVM 재계산을 검사했다. 합성 검토는 실제 사람 승인이 아니다.

## 결정적 Runtime 관측과 보존

v2 Runtime 18사례 run은 `runtime-extended-98e7b0b1-26d8-4f2a-a3da-604dc72e6e74`,
evidence digest는 `0cf93b4645b2e1416ccd80f69414c393314d30b3df640c5bce9e357a6042616b`다.
봉인 파일 316개를 검증했고 독립 JVM 재계산 및 11월 10일 clean build 후 재계산에서 원본 보고서 bytes가 일치했다.
자동 PASS 7 / FAIL 6 / INCOMPLETE 5, 최종 FAIL 6 / INCOMPLETE 12다.
두 정정 사례는 자동 PASS·최종 INCOMPLETE(사람 검토 대기)이며 다른 FAIL도 유지한다.

이 run의 소스는 `7460ba7e8491de150c5535a73bf1fc9b3eaae0d6`, `sourceDirty=true`다.
scorer digest는 `85879e7b4e00058c51082523b6f106986ea715f044b2051392e25140ee782f23`,
build digest는 `611ca91e01cd1c46b2e0d1df5acf9b5a7c9aa8cc7befce33715e553e14b666d4`다.
이는 결정적 관측이며 실제 모델 QUALIFICATION이나 과거 FAIL 취소가 아니다.

v1과 기존 8개 run의 seal·봉인 파일 2,235개·원본 보고서 및 승인 JSON의 불변을 11월 10일 확인했다.
기존 66회·과거 중단 pilot의 runId·seal·digest·판정·누락은 유지한다.
v2는 새 identity이므로 과거 결과를 새 baseline이나 성공 표본으로 소급 집계하지 않는다.
과거 재계산에는 당시 소스·빌드가 필요하다. 최초 실제 관측은 [기존 평가 기록](m8-issue-193-evaluation.md)을 따른다.

원자료·로그·JUnit·실행 identity·봉인 검증·재계산 비교는 실행자 전용 ACL의 ignored 경로
`build/m8-evidence/issue193-oracle-v2-implementation-0d2bb5e1-7585-4460-bc77-4b147b3dabb4/`에 보관한다.
[정리 전 단계 기록](https://github.com/krestar/slotq/tree/c2316737a50a41ba03912521e7aa6c73b2b28b20/docs/experiments)과 원본도 보존한다.

새 실제 PILOT·독립 baseline·quality/security FROZEN 승인·필수 사람 검토·QUALIFICATION은 미완료다.
후속 실제 수집에는 커밋된 새 identity, 호출 직전 무료·키·모델·quota 기술 검증과 별도 명시적 호출 승인이 필요하다.
#193을 닫지 않으며 #185 출시 판정과 merge는 수행하지 않는다.
