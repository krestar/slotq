# SlotQ Roadmap

이 문서는 README의 Product Charter를 실행 가능한 Milestone과 선행 관계로 구체화한다.
현재 저장소는 M0 Foundation을 완료하고 M1 Reservation Core를 진행하는 단계이며,
Reservation HOLD 생성·조회까지 main에 반영되어 있다. 존재하지 않는 Product 기능이나
검증 명령을 전제로 하지 않는다.

## 계획 원칙

- Product Domain → Consistency → Event-driven → Reliability → Observability → AI Platform
  순서를 지킨다.
- M0부터 M5까지 Product Backend와 운영 신뢰성을 먼저 완성한다. M6 이후 작업은 M5의
  완료 조건을 충족한 뒤 시작한다.
- 처음부터 MSA를 만들지 않는다. 실제 분리 근거가 생길 때까지 Modular Monolith를
  기본 경계로 사용한다.
- MySQL을 transactional source of truth로 사용한다. Redis, Kafka, Kubernetes 등의
  도입은 측정된 문제와 대안 비교가 있을 때만 검토한다.
- Frontend는 React, TypeScript, Vite 기반의 thin SPA로 시작한다. backend API contract를
  먼저 확정하고, UI는 Product 규칙을 복제하지 않고 server state를 표현한다.
- 가까운 작업인 M0 전체, M1 전체, M2의 명확한 선행 작업만 GitHub Issue로 관리한다.
  M2 후반부터 M8까지는 착수 시점 전까지 work package 수준으로 유지한다.
- Milestone 완료는 문서 작성만으로 판단하지 않는다. 실행 가능한 테스트, 재현 기록,
  의사결정 기록 중 해당 작업에 필요한 근거가 함께 있어야 한다.

## Milestone 개요

| Milestone | 목표 |
| --- | --- |
| M0 Foundation | 범위와 설계 기준선을 확정하고 backend와 thin SPA 기반을 만든다. |
| M1 Reservation Core | 예약·상태 전이 API와 Customer·Venue 핵심 UI를 구현한다. |
| M2 Concurrency & Consistency | 동시 예약, HOLD 경합, idempotency와 client retry 계약을 검증한다. |
| M3 Reliable Event Foundation | 유실·중복·재시도에 강한 event 전달 기반을 검증하고 선택한다. |
| M4 Waitlist Promotion | 대기 등록·승급 흐름과 이를 사용하는 thin Waitlist UI를 구현한다. |
| M5 Reliability & Observability | 장애 복구 기반과 client 오류 상관관계의 필요성을 검토한다. |
| M6 AI Access & Knowledge | MCP Gateway와 비정형 지식 검색의 안전한 공통 기반을 만든다. |
| M7 Model Router & Agent Runtime | model 선택과 제한된 tool 실행 runtime을 공통화한다. |
| M8 Evaluation & Production Hardening | Product와 AI 경로의 평가 및 release 기준을 확립한다. |

## Project status 정책

Status의 의미는 다음과 같다.

- Backlog: 선행 작업이 끝나지 않았거나 아직 착수하지 않을 작업.
- Ready: 모든 선행 작업이 끝나 지금 바로 시작할 수 있는 작업.
- In Progress: 현재 branch에서 실제 변경이 진행 중인 작업.
- In Review: PR이 열려 review 또는 CI를 기다리는 작업.
- Done: main에 병합된 작업.

현재 main 기준 상태는 다음과 같다.

- Done: #3, #4, #5, #6, #7, #8, #9, #10, #11, #13, #18, #23, #28, #48, #49.
- M1 Ready: #14, #45, #50.
- M1 Backlog: #12는 #50, #38은 #14 + #50, #19는 #12 + #14 + #45,
  #20은 #38 + #45 완료를 기다린다. #19와 #20의 공통 #23 선행은 이미 Done이다.
- M2 선행 Ready: #15, #17.
- M2 선행 Backlog: #16은 #15 완료를 기다린다.

이후에도 한 작업이 끝났다는 이유만으로 모든 후속 Issue를 Ready로 옮기지 않는다.
dependency graph에서 모든 선행 간선이 충족된 Issue만 Ready가 될 수 있다.

## 실제 GitHub Issue

### M0

- #3 [Chore] 제품 범위와 설계 기준선 문서화
- #4 [Chore] main 브랜치 보호 규칙 구성
- #5 [Chore] 백엔드 최소 scaffold 구축
- #28 [Refactor] Product monorepo 최상위 경계 정리
- #6 [Chore] MySQL 로컬 개발 및 schema migration 기반 구성
- #7 [Chore] 백엔드 빌드·테스트 CI 추가
- #18 [Chore] Frontend React·TypeScript·Vite scaffold 구축
- #23 [Chore] Frontend 빌드·테스트 CI 추가

### M1

- #8 [Feature] Tenant·Venue와 예약 정책 기본 모델 구현
- #9 [Feature] tenant 데이터 격리 경계 적용
- #10 [Feature] Resource와 Slot Capacity 모델 구현
- #11 [Feature] Reservation aggregate와 상태 전이 규칙 구현
- #48 [Refactor] Reservation aggregate 영속 복원 경로 추가
- #49 [Bug] Repository port architecture test의 main port 누락 수정
- #13 [Feature] Reservation HOLD 생성·조회 구현
- #50 [Feature] Venue name 저장·변경 기반 추가
- #12 [Feature] slot 예약 가능 수량 조회 구현
- #14 [Feature] Reservation 상태 전이 명령 구현
- #38 [Feature] Venue 운영용 Backend 조회·관리 API 구현
- #45 [Chore] Frontend 디자인 기반 정립
- #19 [Feature] Customer 예약 핵심 흐름 UI 구현
- #20 [Feature] Venue 운영 핵심 흐름 UI 구현

### M2 선행 작업

- #15 [Chore] 동시 예약 실험 하네스와 baseline 구성
- #16 [Feature] 예약 동시성 제어 전략 비교 및 적용
- #17 [Feature] HOLD 생성 command idempotency 보장

#18, #19, #20과 #45의 Project Area는 Frontend이고 #23은 CI이다. 아직 실제 Issue가 없는
후속 UI work package는 착수 시점 전까지 별도 Issue나 Area 항목으로 만들지 않는다.

### Priority

| Issue | Priority | 판단 |
| --- | --- | --- |
| #3, #5, #6, #28 | P1 | 다음 구현을 여는 설계·개발 기반이다. |
| #4 | P2 | 중요한 repository 운영 강화지만 Product 개발 자체를 차단하지 않는다. |
| #7 | P2 | #28 직후 후속 Backend 변경을 보호하는 검증 자동화다. |
| #18 | P2 | thin SPA 기반이지만 backend domain 구현 순서를 차단하지 않는다. |
| #23 | P2 | #18 직후 Frontend 검증을 독립적으로 자동화한다. |
| #8, #9, #10, #11, #13, #14, #38, #48, #49, #50 | P1 | Reservation Core의 핵심 기능 또는 직접 선행·보정 작업이다. |
| #12 | P2 | 중요한 read use case지만 쓰기 흐름의 선행 기반과 구분한다. |
| #19, #20, #45 | P2 | 핵심 Product API 이후 UI와 공통 Frontend 기반을 완성하는 작업이다. |
| #15, #16 | P1 | 동시성 전략을 선택하고 invariant를 보장하기 위한 핵심 선행·구현이다. |
| #17 | P2 | 중요한 reliability 기능이지만 동시성 전략 적용을 직접 차단하지 않는다. |

현재 Issue에는 즉시 대응이 필요한 P0도, 장기 아이디어 성격의 P3도 부여하지 않는다.
우선순위는 Milestone 번호와 같지 않으며, Ready 여부는 Priority가 아니라 dependency
충족 여부로 결정한다.

## Dependency graph

    M0: #3 + #4 Done ──> #5 ──> #28 Repository Layout ──> #7 Backend CI ──> #6
                                      └───────────────> #18 ──> #23 Frontend CI
        #6 + #23 ──> M0 complete

    M1 Backend core:
        #6 ──> #8 ──> #9 ──> #10 ──> #11
                    │                     ├──> #48 ──┐
                    └────────> #49 ────────────────┤
        #11 + #48 + #49 ──> #13                 │
        #13 + #50 ──> #12                       │
        #13 ──> #14                             │
        #14 + #50 ──> #38                       │

    M1 Frontend:
        #18 + #23 ──> #45
        #12 + #14 + #23 + #45 ──> #19
        #38 + #23 + #45 ──> #20
        #19 + #20 ──> M1 complete

    M2: #13 ──> #15 ──> #16
        #13 ──> #17
        #14 + #16 ──> M2-WP1 HOLD 만료·확정 경합
        #17 + #19 ──> M2-WP2 UI retry·idempotency 계약
        M2-WP1 + M2-WP2 ──> M2 complete
                              │
                              v
    M3-WP1 Event contract·원자적 전달 결정 ──> M3-WP2 Relay·Inbox·Retry
                                               │
                                               v
                                  M3-WP3 Failure recovery experiment
                                               │
    M4-WP1 Waitlist model ─────────────────────┤
                                               v
                                  M4-WP2 Promotion·Offer
                                               │
                                               v
                                  M4-WP3 Accept·Expire·Next
                                               │
                                               v
                                  M4-WP4 Thin Waitlist UI
                                               │
                                               v
                                  M5-WP1 Logs·Metrics·Trace
                                               │
                              ├──> M5-WP2 Drill·Runbook·Baseline
                              └──> M5-WP3 Client error correlation 결정
                              M5-WP2 + M5-WP3 ──> M5 complete
                                               │
                              ┌────────────────┴────────────────┐
                              v                                 v
                  M6-WP1 MCP access·Audit             M6-WP2 RAG boundary
                              └────────────────┬────────────────┘
                                               v
                                  M7-WP1 Model router
                                               │
                                               v
                                  M7-WP2 Agent runtime
                                               │
                                               v
                                  M8-WP1 Evaluation
                                               │
                                               v
                                  M8-WP2 Production hardening

M0 Foundation은 #6과 #23까지 main에 병합되어 완료됐다. M1 Backend core는 #8부터 #11,
그리고 corrective #48/#49를 거쳐 #13 HOLD 생성·조회까지 Done이다. #50은 #13과 독립적인
Venue Configuration 작업으로 현재 Ready이며, 완료되면 #12 Availability를 연다. #14도
#13 완료로 Ready이고, #14 + #50이 끝나면 #38 Venue 운영 Backend를 시작할 수 있다.

Frontend에서는 #18과 #23이 Done이라 #45 디자인 기반이 Ready다. Customer UI #19는
#12 + #14 + #45, Venue UI #20은 #38 + #45를 기다리며 #23 공통 선행은 이미 충족됐다.
따라서 M1 완료는 #19와 #20이 모두 Done일 때 판단한다.

M2 선행 작업에서는 #15와 #17이 #13 완료로 Ready이고 #16은 #15의 공통 실험 하네스를
선행으로 사용한다. #15 workload는 실제 Product와 같은 Table × Slot capacity=1,
Allocation unit=1을 사용한다. #16 correctness는 raw active Allocation row 수가 아니라
동일 `verificationNow`의 effective capacity-consuming units로 판단하며 stale concurrent
write와 `RESERVATION_STATE_CONFLICT`의 실제 발생 조건도 이 단계에서 정의한다. #17의
idempotency namespace는 tenant + authenticated customerPrincipalId + key이고, 동일 command
재시도는 같은 Reservation identity와 Location을 재사용하면서 body는 retry 시점의 current
effective representation을 반환한다. M2-WP1과 M2-WP2는 각각의 선행 조건이 충족된 뒤
구체 Issue로 전환한다.

## Release checkpoint

Milestone을 다시 나누지 않고 다음 누적 성공 지점을 둔다.

| Checkpoint | 선행 완료 | 남겨야 할 검증 가능한 결과 |
| --- | --- | --- |
| `v0.1 Consistency Baseline` | M0~M2 | 실제 MySQL을 사용하는 예약 흐름, 동시성 비교와 idempotency 근거 |
| `v0.2 Product Backend` | M0~M5 | Waitlist, event failure recovery, 관측 가능성과 운영 runbook |
| `v1.0 SlotQ Platform` | M0~M8 | Product와 AI Platform의 통합 경계, 평가와 release 기준 |

Checkpoint는 완료되지 않은 Milestone을 완료로 보이게 하거나 scope를 건너뛰는 수단이
아니다. 각 시점에 clean environment 재현 절차, test report, ADR과 측정 원자료를 묶어
실행 가능한 상태를 보존한다.

## M0 Foundation

### 목표

Product 범위, Actor 권한, source of truth, 도메인 용어와 초기 기술 결정을 확정하고,
실제로 build와 test가 가능한 최소 backend와 React·TypeScript·Vite SPA 기반을 만든다.

### 완료 기준

- #3부터 #7까지, #18, #23과 #28이 모두 Done이다.
- MVP 포함·제외 범위와 Customer, Venue Owner·Manager, Staff, AI Agent의 권한 경계가
  문서화되어 있다.
- backend 언어, Modular Monolith, 초기 Context 의존 방향의 결정과 재검토 조건이
  기록되어 있다.
- clean checkout에서 backend와 frontend 각각 문서에 적힌 실제 test·build 명령이
  성공한다.
- thin SPA가 browser에서 기동되고 최소 render smoke test가 성공한다.
- 빈 MySQL database에 schema migration을 적용하고 통합 smoke test를 실행할 수 있다.
- #28 직후 backend 실제 test/build 명령을 실행하는 #7 CI가 성공하며, #6의 통합 테스트도
  같은 Gradle verification lifecycle에서 실행된다.
- #18 직후 frontend 실제 typecheck/test/build 명령을 실행하는 #23 CI가 독립적으로
  성공한다.
- main branch 보호 규칙이 적용되어 직접 push와 검증되지 않은 병합을 제한한다.

### 선행 Milestone

없음.

### 핵심 기술 과제

- 구현되지 않은 미래 Context의 빈 module을 만들지 않고 필요한 최소 구조만 추가한다.
- local과 CI가 같은 JDK, build wrapper, MySQL 계열 database를 사용하도록 한다.
- frontend package manager와 lockfile을 하나로 고정하고 local과 CI가 같은 명령을
  사용하도록 한다.
- SPA scaffold에는 Product 규칙이나 임시 mock business logic을 넣지 않는다.
- credential과 환경별 설정을 repository 밖으로 분리한다.
- CI는 실제 명령이 생긴 뒤 추가하고 최소 권한, timeout, concurrency 취소, credential
  비영속화, 가능한 action full SHA pinning을 적용한다.

### 검증 결과물

- Product scope와 Actor 권한표.
- domain glossary와 Context dependency diagram.
- backend 언어 및 Modular Monolith ADR.
- backend와 frontend local setup, test/build 실행 기록.
- SPA render smoke test.
- 성공한 migration·통합 smoke test와 CI run.

### 이번 Milestone에서 하지 않는 것

- Reservation Product API와 domain table.
- Customer 예약 흐름과 Venue 운영 흐름 UI.
- design system, SSR, mobile application.
- Redis, Kafka, Kubernetes.
- AI 기능과 외부 model 연동.
- deployment와 release 자동화.

## M1 Reservation Core

### 목표

Tenant 경계 안에서 Venue, Policy, Resource, Slot Capacity, Reservation HOLD와 lifecycle을
동기식 Product Backend로 구현하고, 확정된 API contract를 사용하는 thin Customer·Venue
SPA 흐름을 추가한다.

### 완료 기준

- #8부터 #14까지, #19, #20, #38, #45, #48, #49와 #50이 모두 Done이다.
- Tenant, Venue, Policy, Resource와 Slot Capacity가 저장되고 tenant-scoped로만
  조회·변경된다.
- 다른 tenant 식별자를 사용한 read/write가 데이터 노출이나 변경으로 이어지지 않는다.
- 유효한 Reservation 상태 전이는 성공하고, 금지된 전이는 상태를 바꾸지 않은 채
  명시적인 오류를 반환한다.
- HOLD 생성·조회·확정·취소·만료와 운영 상태 전이가 통합 테스트로 검증된다.
- 순차 요청에서 capacity를 초과하는 Reservation은 생성되지 않는다.
- 시간 의존 로직은 주입된 Clock으로 sleep 없이 재현된다.
- #12와 #14의 API contract가 확정된 뒤 Customer UI에서 availability 조회, HOLD 생성,
  확정·취소와 현재 상태 확인이 가능하다.
- #38과 #14의 API contract가 확정된 뒤 Venue UI에서 Venue 설정, 예약 목록과 허용된 운영
  상태 전이를 실행할 수 있다.
- #19와 #20은 #45의 공통 Frontend token·접근성 baseline을 재사용한다.
- 두 UI는 loading, empty, business conflict와 server error 상태를 구분해 표현한다.
- capacity와 상태 전이 판단은 UI가 복제하지 않고 Product API 결과를 따른다.

M1 Restaurant Product의 capacity invariant는 현재 서버 시각의 effective Allocation을
기준으로 정의한다.

    각 Table × Slot과 now에 대해:
        sum(effective capacity-consuming CapacityAllocation.units) <= 1

Allocation unit은 1이며 CONFIRMED, CHECKED_IN과 `expiresAt > now`인 HELD만 capacity를
소비한다. stored HELD + active Allocation이라도 `expiresAt <= now`이면 effective state는
EXPIRED이고 capacity를 소비하지 않는다. REQUESTED는 비즈니스 수명이 필요한 것으로
확인되기 전까지 영속 상태가 아니라 command 처리 단계로 본다.

### 선행 Milestone

M0 Foundation.

### 핵심 기술 과제

- Restaurant MVP의 Table × Slot capacity와 Reservation Allocation unit은 각각 1로
  고정하고 partySize는 Resource seatingCapacity 적합성으로 검사한다.
- Venue timezone의 입력과 UTC 저장 경계를 명확히 한다.
- tenant context가 application과 persistence 경계를 모두 통과하도록 한다.
- aggregate가 상태 전이와 invariant를 책임지고 controller나 JPA callback에 규칙을
  흩뜨리지 않는다.
- availability는 cache나 검색 index가 아닌 Product DB의 effective occupancy에서 계산한다.
- Customer UI는 #12와 #14, Venue UI는 #38과 #14의 API contract를 선행으로 사용하고
  contract 변경을 명시적으로 검증한다.
- Customer와 Venue UI는 #45에서 정한 공통 Frontend token과 접근성 기준을 사용한다.
- server state와 화면 상태를 분리하되, 현재 흐름에 필요하지 않은 복잡한 client state
  framework는 추가하지 않는다.
- tenant와 Actor context가 Customer·Venue 화면과 API 요청에서 혼동되지 않게 한다.

### 검증 결과물

- Reservation 상태 전이표와 parameterized domain test.
- tenant 격리와 권한 경계 통합 test.
- schema migration과 API contract.
- 정상, 부족 수량, 만료, 금지 전이의 test report.
- Customer 예약 흐름과 Venue 운영 흐름의 component·browser flow test.
- loading, empty, conflict, server error 상태의 검증 기록.

### 이번 Milestone에서 하지 않는 것

- 동시 요청에서의 최종 correctness 보장.
- 자동 HOLD 만료 worker.
- Waitlist, event delivery, 외부 notification.
- 외부 identity provider와 업종별 자원 배정 최적화.
- 다업종별 UI, 대규모 design system, SSR, native mobile, AI UI.

## M2 Concurrency & Consistency

### 목표

동시 HOLD 요청에서도 effective capacity invariant를 지키고, HOLD 만료·확정 경합과
Customer-bound command idempotency를 해결하며 Customer UI의 안전한 retry 계약을
정의한다.

### 완료 기준

- #15부터 #17까지 모두 Done이다.
- M2-WP1의 HOLD 만료·확정 경합 검증이 완료되어 있다.
- #15의 동시성 workload는 Product와 동일한 Table × Slot capacity=1, Allocation unit=1을
  사용한다.
- 같은 Resource와 Slot에 동시 요청을 반복해도 동일 `verificationNow`에서 effective
  capacity-consuming Allocation units가 1을 넘지 않는다.
- Optimistic Lock과 Pessimistic Lock을 동일 workload로 비교하고 선택 근거를 ADR에
  기록한다.
- 선택한 전략이 stale concurrent write를 감지하는 실제 조건과
  `RESERVATION_STATE_CONFLICT` business conflict mapping을 정의·검증한다.
- 동일 tenant, authenticated customerPrincipalId, idempotency key와 fingerprint의 재전송은
  하나의 HOLD command identity만 만들고 다른 fingerprint는 conflict로 처리한다.
- 다른 Customer와 다른 tenant는 같은 key 문자열을 독립적으로 사용할 수 있다.
- commit 후 response 유실 뒤 retry도 같은 Reservation identity와 Location을 재사용하고
  새 Reservation/Allocation을 만들지 않는다.
- retry response body는 최초 serialized body가 아니라 retry 시점의 current effective
  representation을 반환한다.
- confirm과 expiry가 경합해도 하나의 합법적인 최종 상태만 남는다.
- 같은 사용자 의도의 HOLD 재시도는 Customer UI가 같은 idempotency key를 재사용하고,
  새 의도는 새 key를 사용한다.
- double click, timeout 뒤 retry와 response 유실 시나리오가 하나의 HOLD만 만든다.

### 선행 Milestone

M1 Reservation Core.

### 핵심 기술 과제

- transaction과 lock의 최소 범위.
- bounded retry, backoff와 retry exhaustion 계약.
- business conflict와 timeout·5xx의 분리.
- correctness 검증에서 raw active Allocation row 수와 동일 `verificationNow`의 effective
  occupancy를 구분한다.
- idempotency namespace `tenantId + authenticated customerPrincipalId + idempotencyKey`,
  request fingerprint, 보존 기간과 cleanup.
- fingerprint는 최소 path venueId, slotInventoryId, partySize를 포함한다.
- #14의 순차 lifecycle과 #16의 동시성 전략을 결합해 confirm/expiry race를 검증한다.
- client에서 같은 사용자 의도와 새 사용자 의도를 구분하고 retriable failure에서 key를
  안정적으로 재사용하는 계약.

### 검증 결과물

- 재현 가능한 concurrency runner와 원자료.
- 전략별 correctness·latency·throughput·lock wait 비교 보고서.
- 선택 전략 ADR과 CI용 invariant 통합 test.
- due stored HELD를 포함한 effective occupancy와 raw active row diagnostic 대조 기록.
- duplicate request 및 confirm-expire race test.
- Customer UI retry와 double-click contract test.

### 이번 Milestone에서 하지 않는 것

- 근거 없는 Redis Distributed Lock.
- messaging과 Waitlist.
- 서로 다른 환경에서 얻은 수치의 직접 비교.
- benchmark 수치를 PR merge의 자동 gate로 사용.
- 범용 offline command queue와 모든 UI command의 일괄 idempotency 적용.

## M3 Reliable Event Foundation

M3부터는 현재 GitHub Issue를 생성하지 않고 다음 work package만 유지한다.

### 목표

Waitlist 비즈니스와 분리된, duplicate와 crash에 견디는 event 전달 기반을 만들고 가장
단순한 원자적 전달 방식을 근거로 선택한다.

### 완료 기준

- commit된 업무 변화에 대응하는 event가 정의한 복구 절차 뒤에도 유실되지 않는다.
- relay restart, duplicate publish, consumer redelivery에서도 business side effect가
  중복되지 않는다.
- retryable failure와 non-retryable failure가 구분된다.
- retry exhaustion 상태, operator replay와 recovery 절차가 검증된다.
- 문서화된 crash point별 failure test가 통과한다.
- 동기 처리, commit 이후 publish, Transactional Outbox 등 현실적인 대안과 선택 근거가
  ADR에 기록된다.

### 선행 Milestone

M2 Concurrency & Consistency.

### 핵심 기술 과제

- event identity, schema version과 ordering 요구사항.
- DB commit과 event publish 사이의 원자성 문제와 대안 비교.
- Transactional Outbox를 선택할 경우 outbox schema와 relay ownership.
- consumer inbox 또는 business unique constraint.
- retry, backoff, dead-letter와 replay audit.
- DB polling으로 충분한지, broker가 필요한지를 측정으로 판단.

### 검증 결과물

- event contract와 선택한 delivery state schema.
- 대안 비교 및 선택 ADR.
- delivery state diagram과 failure matrix.
- duplicate, redelivery, process crash, partial failure test.
- recovery run과 replay audit 기록.

### 이번 Milestone에서 하지 않는 것

- Waitlist promotion business flow.
- Kafka 선도입.
- 외부 notification provider 완성.

### M3 종료 후 일정·범위 재평가

M3 결과를 기준으로 남은 일정, 실제 구현 완성도와 M4·M5 작업량을 재평가한다.

- 시간이 충분하면 계획된 M4와 M5 범위를 그대로 진행한다.
- 일정 제약이 커지면 실제 notification provider, 추가 dashboard, 선택적 Adapter 같은
  비핵심 범위를 줄인다.
- M4의 Waitlist 등록·Offer 핵심 흐름과 M5의 재시작 복구·최소 log/metric Gate는
  축소 대상이 아니다.
- 위 최소 Gate가 완료되지 않은 상태에서 M6 AI 구현으로 이동하지 않는다.

## M4 Waitlist Promotion

### 목표

대기 등록부터 취소 후 offer 승급, 수락·거절·만료와 다음 후보 이동을 구현하고, server
state를 표현하는 thin Waitlist UI를 제공한다.

### 완료 기준

- tenant, Resource, Slot별 deterministic ordering이 적용된다.
- 동일 후보와 Slot에는 활성 offer가 하나만 존재한다.
- duplicate cancellation 또는 event가 중복 offer를 만들지 않는다.
- offer 수락과 만료가 경합해도 하나의 결과만 남는다.
- 거절 또는 만료 후 다음 후보가 승급된다.
- offer 수락 시 M2에서 확정한 capacity invariant를 그대로 지킨다.
- Customer가 Waitlist 등록 상태와 offer 상태를 확인하고 수락·거절할 수 있다.
- Venue 사용자가 현재 대기 순서와 offer 처리 상태를 조회할 수 있다.
- duplicate 또는 늦게 도착한 response에도 UI가 server의 최종 상태를 따른다.

### 선행 Milestone

M3 Reliable Event Foundation.

### 핵심 기술 과제

- waitlist ordering과 tie-breaker.
- active offer의 business unique constraint.
- offer lease와 expiry.
- promotion과 capacity 확보의 원자성.
- notification 요청과 실제 전달 결과의 책임 분리.
- Waitlist와 offer 상태를 UI 전용 상태 기계로 재구현하지 않고 API contract에 매핑한다.

### 검증 결과물

- promotion sequence diagram.
- ordering, duplicate, redelivery test.
- accept-expire race와 다음 후보 이동 test.
- notification request contract.
- Customer·Venue Waitlist thin flow의 browser test.

### 이번 Milestone에서 하지 않는 것

- 복잡한 우선순위·추천 정책.
- 다채널 notification provider 완성.
- 여러 업종에 특화된 대기 규칙.
- WebSocket이나 push UX를 필요성 검증 없이 도입하는 것.

## M5 Reliability & Observability

### 목표

Product Backend와 event flow의 장애를 탐지하고 원인을 설명하며 안전하게 복구할 수 있게
한다. Browser 오류를 server request와 연결할 필요성은 이 단계에서 평가하되 client
관측 도구 도입을 미리 확정하지 않는다.

### 완료 기준

- request와 event correlation, tenant-safe structured log가 제공된다.
- reservation conflict, lock wait, outbox lag, retry, dead-letter 상태를 관측할 수 있다.
- 핵심 flow의 latency, error, saturation 지표와 trace가 연결된다.
- 대표 장애 시나리오의 탐지·복구 runbook이 실제 drill로 검증된다.
- 고정 환경의 성능 baseline과 회귀 판단 기준이 기록된다.
- Customer·Venue·Waitlist UI 오류를 server correlation ID와 연결할 필요성, 개인정보
  영향, 비용과 대안이 검토되어 도입 또는 보류 결정이 기록된다.

### 선행 Milestone

M4 Waitlist Promotion.

### 핵심 기술 과제

- 낮은 metric cardinality와 tenant·개인정보 노출 방지.
- request에서 outbox와 consumer까지 correlation 전파.
- 재처리와 수동 개입의 audit.
- alert가 실제 사용자 영향과 연결되도록 threshold를 정하는 일.
- client error correlation을 적용할 경우 correlation ID 노출 범위, source map 보안,
  telemetry sampling과 개인정보 경계를 정하는 일.

### 검증 결과물

- dashboard와 alert 조건.
- 정상·실패 trace 예시.
- failure drill, recovery time과 runbook.
- 고정 환경 performance baseline report.
- client error correlation 도입 또는 보류 결정과 판단 근거.

### 이번 Milestone에서 하지 않는 것

- Kubernetes 도입 자체를 목표로 한 작업.
- multi-region과 대규모 HA.
- 사용 목적이 없는 관측 도구 추가.
- 필요성 검증 전 full-session recording이나 vendor RUM 도입.

## M6 AI Access & Knowledge

### 목표

Product API를 안전하게 호출하는 MCP Gateway 최소 기능과 비정형 지식 검색의 책임 경계를
구축한다.

### 완료 기준

- Tool Registry, authentication context, authorization, timeout, rate limit, audit가 적용된다.
- transactional 조회와 변경은 Product API만 사용한다.
- 정책과 안내 지식은 tenant 경계 안에서 출처와 함께 검색된다.
- AI Agent는 독립 superuser가 아니라 Actor에게 위임된 principal로 실행된다.
- tenant escape, 금지 tool 실행, parameter 조작 test가 통과한다.

### 선행 Milestone

M5 Reliability & Observability.

### 핵심 기술 과제

- delegated principal과 write confirmation 경계.
- tool input validation, timeout, rate limit과 audit redaction.
- Product API와 RAG source of truth 분리.
- retrieval tenant isolation과 문서 versioning.

### 검증 결과물

- Product API와 RAG 책임 분리 ADR.
- threat model과 tool permission matrix.
- redacted audit sample과 보안 test.
- 출처 기반 retrieval seed evaluation.

### 이번 Milestone에서 하지 않는 것

- Model Router와 범용 Agent Runtime.
- 고위험 write의 무확인 실행.
- model provider 수를 늘리기 위한 연동.

## M7 Model Router & Agent Runtime

### 목표

여러 AI 기능이 공유할 model 선택 기준과 제한된 tool execution runtime을 만든다.

### 완료 기준

- cost, latency, quality, security 기준으로 routing 결정을 재현할 수 있다.
- fallback과 provider failure가 명시적인 정책으로 처리된다.
- tool budget, timeout, retry, cancellation, confirmation이 runtime에서 강제된다.
- Customer, Owner·Manager, Ops의 제한된 대표 flow가 같은 runtime을 사용한다.

### 선행 Milestone

M6 AI Access & Knowledge.

### 핵심 기술 과제

- model capability metadata와 routing score.
- fallback이 결과 품질과 안전성에 미치는 영향.
- tool execution state, cancellation과 retry.
- durable workflow가 실제로 필요한지 판단하는 gate.

### 검증 결과물

- routing 비교표와 선택 기록.
- runtime trace와 failure/fallback 기록.
- flow별 권한·confirmation test.
- model별 latency, cost, quality 원자료.

### 이번 Milestone에서 하지 않는 것

- 무제한 autonomous execution.
- 모든 provider 지원.
- 필요성이 확인되지 않은 durable workflow framework.

## M8 Evaluation & Production Hardening

### 목표

Product와 AI flow의 회귀를 자동으로 평가하고 release 가능 여부를 판단할 기준을 만든다.

### 완료 기준

- tool selection, parameter extraction, retrieval quality, execution success, forbidden action
  evaluation이 자동화된다.
- versioned dataset과 threshold 변경 이력이 남는다.
- load, failure, security, backup-restore 검증 결과가 정한 기준을 충족한다.
- release checklist와 rollback·recovery 절차가 검증된다.

### 선행 Milestone

M7 Model Router & Agent Runtime.

### 핵심 기술 과제

- 대표성 있고 tenant-safe한 evaluation dataset.
- 비결정적 model 출력의 반복 측정과 score 안정성.
- destructive tool의 격리된 test.
- backup consistency와 restore verification.

### 검증 결과물

- versioned evaluation dataset과 scorecard.
- security, load, failure report.
- backup-restore drill과 rollback 기록.
- release checklist.

### 이번 Milestone에서 하지 않는 것

- 측정하지 않은 품질·성능 수치 주장.
- 필요성이 없는 multi-region 또는 orchestration 확대.
- 운영 검증 없이 release 완료로 간주하는 것.

## 계획 자체 검토

| 검토 질문 | 결론 |
| --- | --- |
| Product Backend와 AI Platform의 비중이 Charter 방향을 유지하는가? | M0~M5를 Product와 운영 신뢰성에 배정하고 M6 이후에만 AI 기반을 시작한다. |
| AI가 너무 일찍 등장하는가? | AI 구현은 M5 완료가 선행 조건이며 M0~M2 실제 Issue에는 AI 작업이 없다. |
| 기술을 사용하기 위한 요구사항이 있는가? | Redis, Kafka, Kubernetes, Distributed Lock, Spring Modulith는 도입 gate가 충족될 때까지 제외한다. Outbox도 M3 대안 비교 전에는 확정하지 않는다. |
| 한 명이 완주 가능한가? | Modular Monolith, 한 가지 Restaurant demo와 thin SPA를 사용하고 결제·다업종 UI·복수 Resource 최적화를 제외한다. |
| Issue 크기와 개수가 적절한가? | 실제 Issue는 M0 8개, M1 core 10개에 corrective/supporting #45·#48·#49·#50을 더하고 M2 선행 3개로 관리한다. 먼 단계는 work package로 남긴다. |
| Priority가 부풀려졌는가? | P0는 없고, 흐름을 막지 않는 #4·#7·#12·#17·#18·#19·#20·#23·#45는 P2로 구분한다. |
| Ready와 Backlog가 dependency를 반영하는가? | 현재 #14·#45·#50과 M2 선행 #15·#17이 Ready이며, #12·#16·#19·#20·#38은 각 dependency가 끝날 때까지 Backlog다. |
| 기술 선택을 설명할 근거가 있는가? | Java와 Modular Monolith는 Accepted ADR로 기록한다. Gradle Wrapper는 local과 CI의 build 진입점을 통일하며 별도의 Architecture 우위를 주장하지 않는다. 동시성·messaging 선택은 실험 전 확정하지 않는다. |
| README Product Charter와 충돌하는가? | Product First, effective capacity invariant, transactional source of truth, 단계적 AI 도입을 유지한다. |
| 구현 전 필요한 설계가 충분한가? | Product 범위, Actor 권한, Context, 상태 전이, Milestone, 의존 관계, 실험 gate를 문서화했다. 세부 schema와 API는 각 Ready Issue에서 확정한다. |
