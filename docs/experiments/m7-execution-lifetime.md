# Agent 실행 수명과 복구 요구 판정

[#156](https://github.com/krestar/slotq/issues/156)의 판정은 **현재 controlled synthetic 대표 flow에는
restart-durable workflow가 필요하지 않다**이다. Bounded process-local Run을 유지한다.
추가 persistence, executor queue, workflow framework는 도입하지 않는다.

기준은 #155가 반영된 `main`의 `fcab6540d583bce327355e9049b45e07cf1bfb59`다.
[M7 SSOT §10](../architecture/m7-model-router-agent-runtime.md#10-http--process-local--restart-durable-requirement와-gate)과
[ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)의 지원 범위를 아래 근거로 재검토했다.
#157의 통합 종료 감사와 production SLA·HA·backup qualification은 별도 작업이다.

## 세 실행 수명과 지원 범위

| 수명 | 현재 지원과 종료 의미 |
| --- | --- |
| Request-bounded execution | 한 provider/tool step은 유한 wait 뒤 success, rejection, unavailable 또는 unknown으로 반환한다. Provider timeout은 원격 실패·종료 확정이 아니며 usage/cost도 unknown일 수 있다. 새 public Run/approval API는 없다. |
| Process-local continuation | 같은 프로세스·exact delegation에서만 명시적으로 재진입한다. 기본 Run 10분·attempt 30초, controlled 최대 Run 15분·attempt 45초다. 승인 대기는 worker를 쓰지 않는다. 이미 시작한 작업은 응답 반환·취소 후에도 실제 종료까지 permit을 유지하고 late observation만 보완한다. |
| Restart-durable continuation | Run·예산·결과·취소 요청의 재시작 연속성은 지원하지 않는다. 이전 Run ID는 새 JVM에서 거부한다. 자동 dispatch·delayed retry·persistent backlog·독립 executor·재시작 후 승인 continuation은 제공하지 않는다. |

현재 Customer는 원 고객의 승인 후 HOLD, Management와 Ops는 읽기다. #151/#153/#155에는
재시작 뒤 같은 Run을 반드시 완료하거나 취소 acknowledgement를 영속 보장해야 한다는 계약이 없다.
불확실한 외부 실행을 terminal unknown으로 알리고 사용자가 현재 상태를 다시 확인하는 종료가 허용된다.
긴 응답을 끝까지 성공시킬 새 요구를 이 gate에서 추가하지 않았다.

## 실제 모델과 flow 지연

이번 작업은 기존 원본을 재계산했다. 외부 provider를 새로 호출하지 않았으며 API key도 필요하지 않다.
#152의 72회, #165의 96회는 실제 모델과 synthetic fixture의 비교다.
#155는 실제 Runtime/MCP/Product를 연결한 별도 실행이다. 서로 다른 context·revision의 수치를 합쳐
현재 품질이나 latency 개선율로 해석하지 않는다. Quantile은 nearest-rank다.

| Workload / model | #152 표본 / p95 | #165 표본 / min–max (p95), ms |
| --- | --- | --- |
| Customer / 3.1 Flash-Lite | 12 / 10,309 ms | 12 / 1,276–23,301 (23,301) |
| Customer / 3.5 Flash-Lite | 12 / 1,422 ms | 12 / 890–1,189 (1,189) |
| Management / 3.1 Flash-Lite | 12 / 18,343 ms | 18 / 819–27,331 (27,331) |
| Management / 3.5 Flash-Lite | 12 / 1,273 ms | 18 / 818–2,023 (2,023) |
| Ops / 3.1 Flash-Lite | 12 / 35,996 ms | 18 / 6,809–27,589 (27,589) |
| Ops / 3.5 Flash-Lite | 12 / 1,288 ms | 18 / 869–1,530 (1,530) |

#152 Ops 3.1의 3/12가 30초를 넘었다. #165의 96회는 30초 초과와 timeout이 0이며,
3.1의 overload 4회와 usage unavailable은 그대로 남긴다. 이 표는 적격성 판정을 바꾸지 않는다.
현재 후보와 quality/security/disclosure gate는 [#165 기록](m7-router-corrective.md)을 따른다.

최신 #155 세트 `representative-flow-independent-20261014`의 실제 결과는 다음과 같다.

| Flow | Provider latency, ms | 마지막 Run duration, ms | Approval wait, ms |
| --- | --- | --- | --- |
| Customer proposal → 승인 → HOLD → answer | 1,488 / 1,809 | 12,973 | 154 |
| Owner Management | 1,435 | 3,687 | 0 |
| Manager Ops | 2,070 | 3,596 | 0 |

네 호출의 provider timeout은 0/4이고 세 Run은 10분 deadline 안에서 종료했다.
이 작은 성공 표본만으로 앞선 실패를 제외하거나 사용자 대기 시간·production SLA를 추정하지 않는다.

### 이전 실패와 deadline의 구분

아래는 #155의 과거 cohort별 관측이다. 분모는 각 cohort에 실제 기록된 provider 결과이며,
미실행 case나 local completion 미관측을 성공으로 채우지 않는다.

| `build/` 아래 cohort | 기록 수 | Provider timeout / 기록 수 | 30초 초과 | 관찰 |
| --- | --- | --- | --- | --- |
| `representative-flow-v1` | 2 | 0/2 | 0 | answer MALFORMED 1회, Product success 보존 |
| `representative-flow-v2` | 0 | 산정 불가 | 산정 불가 | 30초 반환 뒤 local completion 미관측. 신규 telemetry가 없으므로 분모에 넣지 않음 |
| `representative-flow-v2-complete` | 1 | 1/1 | 1 | 45,003 ms, remote completion/usage/cost unknown |
| `representative-flow-v2-operators-first` | 7 | 0/7 | 0 | 전체 내용 검토에서 Ops 미완료 |
| `representative-flow-v3` | 4 | 1/4 | 2 | Ops 40,480 ms 성공과 45,000 ms timeout |
| `representative-flow-v3-complete` | 1 | 1/1 | 1 | Management 45,003 ms timeout |
| `representative-flow-final` | 1 | 0/1 | 0 | HTTP 503, 2,312 ms |
| `representative-flow-final-complete` | 1 | 1/1 | 1 | Management 45,002 ms timeout |
| `representative-flow-recheck-20261014` | 5 | 1/5 | 3 | 추가 known-GET answer timeout, 전체 FAIL 유지 |
| `representative-flow-required-20261014` | 3 | 2/3 | 2 | Management·Customer proposal timeout |
| `representative-flow-final-20261014` | 3 | 1/3 | 1 | Customer timeout, Ops 503, Management 성공 |

Timeout은 `TIMEOUT_REMOTE_UNKNOWN`으로 집계했다. 44,999–45,003 ms의 반올림 차이를 실제
deadline 초과 판정으로 바꾸지 않는다. Adapter wait 한계와 10분 Run lifetime도 구분한다.
과거 25개 Run의 마지막 관측은 최대 75,574 ms이며 기록된 Run deadline 초과는 0/25다.
미관측 완료나 원격 작업이 10분 안에 끝났다는 뜻은 아니다.

반복되는 45초 timeout은 실제 실패 근거다. 현재 계약은 bounded unavailable/unknown 종료를
허용하므로 이것만으로 restart continuation이 필요해지지는 않는다. 독립 flow 실행 후 성공했지만
provider 부하·입력 복잡도·네트워크 중 원인을 분리한 실험은 아니며 해결 원인을 확정하지 않는다.

## 승인·포화·취소·프로세스 종료 실험

`RepresentativeFlowIntegrationTests`의 `lifetime*`와 기존 unknown/cancellation 회귀를 사용한다.
Provider는 deterministic fixture이며 Auth·MCP·Product HTTPS·MySQL은 실제 구현이다.
아래 주입 실험을 actual-provider 실패나 운영 부하 측정으로 합산하지 않는다.

| 실험 | 검증 경계 |
| --- | --- |
| 승인 후 240초 재진입 | 단일 worker가 다른 Run을 처리할 수 있고 original deadline 안에서 HOLD 성공 |
| 승인 후 301초 / 601초 재진입 | 5분 confirmation 만료 / 10분 Run deadline을 보존하고 HOLD 미생성. 시간은 주입 Clock으로 전진시킴 |
| Provider-only 포화 | 반환 후에도 provider worker/permit 유지, 다음 Run의 provider admission 거부, 숨은 backlog 없음. 실제 종료 뒤 명시적 호출만 실행 |
| Tool 포화 | 실제 Product 읽기 뒤 handler를 gate, timeout·취소 뒤에도 worker 유지. 종료 후 permit 반환, 거부된 호출의 자동 재실행 없음 |
| 취소 전·후 dispatch | 승인 뒤 전송 전 취소는 HOLD 차단. 기존 post-COMMIT/caller interruption 회귀는 late Product success와 취소 상태를 별도로 보존 |
| 전송 전 JVM 강제 종료 | 승인 record는 남지만 HOLD·idempotency record는 0. 새 JVM에서 old Run 거부 |
| Product dispatch 후 COMMIT 전 강제 종료 | 부모가 exact Slot row를 잠그고 MySQL lock wait를 관측한 뒤 JVM 종료. 미완료 transaction rollback, HOLD·idempotency record 0 |
| COMMIT 뒤 응답 전 강제 종료 | 실제 controller transaction 반환 뒤 response를 gate. MySQL HOLD·allocation·idempotency record 각 1개 보존. Run 결과는 복구하지 않음 |
| COMMIT + 응답 유실 뒤 강제 종료 | partial HTTPS response를 주입해 structured Product `outcome_unknown`과 known target을 관측한 뒤 종료. 새 JVM의 명시적 exact GET만 현재 상태 확인 |
| 재시작 | 각 경우 다른 PID의 새 JVM으로 부팅. old Run 접근 거부와 자동 HOLD 부재 확인. unknown target은 조회를 만들지 않고 unknown 유지 |

강제 종료는 현재 ADR-0009 topology에 맞게 **Agent/MCP/Product가 함께 있는 자식 JVM**에 적용한다.
MySQL은 부모 Testcontainers에서 유지한다. Product credential의 live exchange는 co-located Auth가
관리하므로 Agent만 별도 JVM으로 옮겨 동일 계약을 제공한다고 가정하지 않는다.
Production HA, DB crash/restore, 원격 provider 강제 중단은 이 실험의 보장 밖이다.

## 필수 질문과 결론

| 질문 | 판정 근거와 답 |
| --- | --- |
| 모델 지연이 Run lifetime을 반복 초과하는가? | 반복 attempt timeout은 있으나 기록된 Run lifetime 초과는 없다. 원격 완료는 unknown이며 bounded 실패 반환이 허용된다. |
| Confirmation을 동기 자원 없이 기다릴 수 있는가? | WAITING_CONFIRMATION은 worker 0이며 same-process explicit re-entry와 기존 expiry/budget으로 처리한다. 긴 대기는 Clock 실험이며 실제 사용자 대기 관측은 아니다. |
| 재시작 후 같은 Run continuation이 요구되는가? | 현재 세 flow 계약에 없다. Run 유실과 명시적 재인증·현재 상태 조회를 지원 한계로 둔다. |
| 재시작 뒤 자동 dispatch가 필요한가? | 필요하지 않다. 자동 mutation은 없고 unknown을 새 HOLD로 대체하지 않는다. |
| Durable cancellation acknowledgement가 필요한가? | 현재 계약에 없다. 취소는 새 dispatch를 막는 local 요청이며 remote stop·rollback·재시작 후 취소 지속을 보장하지 않는다. |
| HTTP 밖 독립 executor가 필요한가? | 응답 이후 남은 작업은 현재 프로세스에서 종료까지 accounting한다. Request와 독립된 지속 실행·재시작 completion 요구는 없다. |
| Saturation에서 persistent queue가 필요한가? | Finite admission rejection을 검증했다. 실제 backlog 수요는 관측하지 않았으며 synthetic overload로 새 backlog requirement를 만들지 않는다. |

기각한 대안은 timeout을 피하려고 deadline을 무제한 연장하는 방법, confirmation 동안 worker를
점유하는 방법, timeout·취소 즉시 permit을 반환하는 방법, durable approval로 Run을 재구성하거나
mutation을 자동 retry하는 방법이다. 각각 유한 실행, resource accounting 또는 M6 authority를 훼손한다.
DB queue/workflow engine·Kafka offset 재활용·provider/배포 분리도 충족할 현재 요구가 없어 도입하지 않는다.
작은 traffic, 기존 Kafka, future notification, M8 evaluation은 판정 근거로 사용하지 않았다.

## 사용자·운영자 복구 경로와 재검토 조건

같은 live Run에는 current exact delegation으로 재진입한다. 승인·same-intent retry는 current original
Actor도 확인하고 M6의 5분 confirmation과 최초 dispatch 기준 15분 retry horizon을 유지한다.
새 request·승인·delegation은 원래 budget/deadline/key를 reset하지 못한다.

Timeout·취소·재시작은 Product rollback의 증거가 아니다. Known Reservation이 있으면 현재 권한으로
새 read Run의 exact GET을 명시적으로 실행한다. GET의 현재 상태와 원래 mutation observation을 구분한다.
Unknown target은 unknown으로 남기고 운영자가 기존 Product 조회·운영 절차에서 확인한다.
대상이 확인되지 않은 결과를 새 HOLD·새 key로 자동 재실행하지 않는다.
재시작 뒤 old Run의 `retryHold`는 지원하지 않으며, 남은 M6 approval만으로 old Run을 복원하지 않는다.

Product business state·idempotency ledger는 복제하지 않는다. Kafka offset과 M5 delivery/recovery는
Agent 실행 authority가 아니다. 이번 결론은 ADR-0009/0011의 replacement를 요구하지 않는다.

향후 같은 Run의 restart resume, durable cancel, 승인 continuation, independent executor,
persistent backlog 또는 delayed retry가 실제 supported requirement가 되면 #156 owner가 gate를
다시 검토한다. Positive decision은 실제 requirement/evidence, process-local 대안의 한계, #153/#155
영향과 current auth/confirmation/retry 재검증 제약을 남긴다. 그 뒤 별도 Issue 설계와 conditional M7-D를
완료해야 #157 closure로 진행할 수 있다. 미설계 durable 구현을 Accepted라고 표시하지 않는다.

## 재현과 산출물

기존 실제 모델 원본이 있는 checkout에서 root 재계산을 실행한다. 16개 cohort를 고정해 읽으며
입력 SHA-256·revision/context·실패·미관측과 cohort별 latency를 ignored report에 남긴다.
이는 latency 재계산이며 원래 quality/content review를 대체하지 않는다.

```powershell
python infra/experiments/agent_lifetime_summary.py
```

JDK 25와 Docker를 준비하고 `backend/`에서 실행한다. 외부 provider opt-in은 필요하지 않다.

```powershell
.\gradlew.bat test --tests 'com.slotq.mcp.RepresentativeFlowIntegrationTests' --tests 'com.slotq.ai.*' --tests 'com.slotq.architecture.*'
.\gradlew.bat clean build
```

자식 JVM checkpoint·로그·결과는 `backend/build/agent-lifetime-*/`, latency report는
root `build/issue-156/latency-summary.json`에 생성한다. Credential은 자식 stdin으로만 전달하며
checkpoint·명령 인자에 쓰지 않는다. 원본과 실행 로그는 Git에 추적하지 않는다.
테스트·빌드의 최종 실행 결과는 PR에 기록한다.
