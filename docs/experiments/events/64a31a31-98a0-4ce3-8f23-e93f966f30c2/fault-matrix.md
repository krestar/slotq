# Event delivery process fault matrix

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

| Case | Fault point | Termination | Result |
| --- | --- | --- | --- |
| MATERIALIZATION_BEFORE_PROCESS_EXIT | committed event before materialize() | Runtime.halt | PASS |
| CLAIM_COMMIT_THEN_PROCESS_EXIT | claim() commit before handler | Runtime.halt | PASS |
| EFFECT_TRANSACTION_PROCESS_EXIT | after synthetic effect/receipt writes, before effect transaction commit | Runtime.halt | PASS |
| EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN | after physical effect + DONE commit, before transaction completion returns | afterCommit Runtime.halt | PASS |
| STALE_OWNER_AFTER_NEW_OWNER_DONE | JVM A claim -> lease expiry -> JVM B reclaim/DONE -> JVM A process | coordinated two-JVM interleaving | PASS |
| CLAIM_CRASH_EXHAUSTION | claim() commit followed by repeated Runtime.halt | Runtime.halt x3 | PASS |
| TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION | CRASH_EXHAUSTED replay and new retry cycle | new child JVM | PASS |
| DATABASE_UNAVAILABLE_AND_RECOVERY | ready child enters production runCycle() while MySQL container is paused | observed DB access failure then Runtime.halt | PASS |
| BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN | one production runCycle() completes a partial batch before process exit | Runtime.halt after runCycle | PASS |
