# 탐색 실행 기록

이 디렉터리는 선택 근거로 사용하는 clean run이 아니다.

- `initial-report.json`: dirty `4cd88b98c9ee2b69e1bc85f3478d99ebd3c3f36a`에서 실행한
  최초 45개 관측. 후보별 seed가 달랐고 durable recovery는 parent의 event object를
  재사용했다. durable handler fault도 recovery 전에 주입하지 않았다. 따라서 공정한
  최종 후보 비교나 restart discovery 증명으로 사용하지 않는다. 원자료는 삭제하지 않는다.
- 두 번째 실행은 fresh JVM이 MySQL JSON을 다시 읽은 뒤 `IDENTITY_CORRUPTION`으로 중단됐다.
  `EventFixture.effect → EventExperimentRunner.main(RECOVER)`에서 child exit 1이었다.
  payload를 JSON 문자열로 비교하여 MySQL의 공백 정규화를 의미 변경으로 오판한 fixture
  결함이다. JSON tree의 의미 비교로 보정했다. 이 실행은 완성된 report를 만들지 못했다.
- 후속 탐색 실행에서 fresh JVM recovery, duplicate, poison, lease reclaim과 replay probe가
  실행됐다. 최종 판단은 고정 seed와 crash exhaustion을 포함한 clean run에서 다시 수행한다.

탐색 중 latency나 failure를 production 수치로 해석하지 않는다. 이 기록의 당시 revision과
dirty 값은 후속 clean revision으로 바꾸지 않는다.
