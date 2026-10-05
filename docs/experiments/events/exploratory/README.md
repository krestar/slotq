# 탐색 실행 기록

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

이 디렉터리는 선택 근거로 사용하는 clean run이 아니다.

- `initial-report.json`: dirty `4cd88b98c9ee2b69e1bc85f3478d99ebd3c3f36a`에서 실행한
  최초 45개 관측. 후보별 seed가 달랐고 durable recovery는 parent의 event object를
  재사용했다. durable handler fault도 recovery 전에 주입하지 않았다. 따라서 공정한
  최종 후보 비교나 restart discovery 증명으로 사용하지 않는다. 이 실패/한계를 summary에 남기고 raw는 현재 tree에서 제거했다.
- 두 번째 실행은 fresh JVM이 MySQL JSON을 다시 읽은 뒤 `IDENTITY_CORRUPTION`으로 중단됐다.
  `EventFixture.effect → EventExperimentRunner.main(RECOVER)`에서 child exit 1이었다.
  payload를 JSON 문자열로 비교하여 MySQL의 공백 정규화를 의미 변경으로 오판한 fixture
  결함이다. JSON tree의 의미 비교로 보정했다. 이 실행은 완성된 report를 만들지 못했다.
- 후속 탐색 실행에서 fresh JVM recovery, duplicate, poison, lease reclaim과 replay probe가
  실행됐다. 최종 판단은 고정 seed와 crash exhaustion을 포함한 clean run에서 다시 수행한다.

탐색 중 latency나 failure를 production 수치로 해석하지 않는다. 이 기록의 당시 revision과
dirty 값은 후속 clean revision으로 바꾸지 않는다.
