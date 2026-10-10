# Architecture Decision Records

SlotQ의 중요한 기술 선택은 Architecture Decision Record(ADR)로 남깁니다. ADR은 결정 당시의 문제, 대안, 근거, 결과와 재검토 조건을 기록하며, 구현 결과나 실험 수치를 대신하지 않습니다.

## 상태 정의

| 상태 | 의미 |
| --- | --- |
| `Proposed` | 구현 전에 검토해야 하며 아직 최종 결정하지 않은 후보입니다. |
| `Accepted` | 현재 적용하기로 결정한 기준입니다. 변경하려면 새 ADR로 대체 이유를 남깁니다. |
| `Deferred` | 지금 결정할 근거가 부족해 명시된 증거나 선행 조건이 생길 때까지 보류한 후보입니다. |
| `Rejected` | 검토했지만 현재 맥락에는 적용하지 않기로 한 선택입니다. |
| `Superseded` | 더 최신 ADR이 이 결정을 대체했습니다. 기존 기록은 결정 이력을 위해 유지합니다. |

구현되었거나 다른 결정이 의존하는 Accepted 결정이 달라지면 기존 문서를 `Superseded`로
바꾸고 새 ADR에서 변경 근거를 기록합니다. 아직 구현되지 않은 같은 계획 단계에서 발견한
오류나 부수 기준은 문서를 직접 보정할 수 있으며, 변경 이유는 Issue와 PR 이력에 남깁니다.

## 결정 목록

| ADR | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-use-java.md) | Backend 언어로 Java 사용 | `Superseded` |
| [0002](0002-start-with-modular-monolith.md) | 단일 배포형 Modular Monolith로 시작 | `Superseded` (runtime 조항은 0008, module 경계 보존) |
| [0003](0003-use-react-typescript-vite.md) | Thin SPA에 React, TypeScript, Vite 사용 | `Accepted` |
| [0004](0004-use-java-25.md) | Java 25 LTS를 Backend 기준선으로 사용 | `Accepted` |
| [0005](0005-use-mysql-hold-idempotency-record.md) | MySQL reliability record로 HOLD command idempotency 보장 | `Accepted` |
| [0006](0006-use-targeted-pessimistic-locks-for-reservation-consistency.md) | Reservation 정합성 경계에 대상 row pessimistic lock 사용 | `Accepted` |
| [0007](0007-use-transactional-event-record-and-db-delivery.md) | Transactional event record와 DB 기반 전달 경계 사용 | `Superseded` (초기 transport/runtime은 0008, transactional 계약 보존) |
| [0008](0008-m5-event-transport-and-runtime-status.md) | DB direct 기본 전달과 Kafka experimental topology 유지 | `Accepted` |
| [0009](0009-m6-authenticated-access-and-knowledge.md) | 인증된 Product API와 위임 접근·지식 경계로 M6 구성 | `Accepted` (구현/활성화 evidence는 #131~#135) |
| [0010](0010-m7-bounded-model-router.md) | Synthetic 비교와 snapshot 계산으로 bounded Model Router 구성 | `Accepted` (bounded synthetic profile) |

## 후보 등록부

후보를 등록했다는 사실은 특정 해법을 선택했다는 뜻이 아닙니다. Product Backend의 구현에 선행하는 결정과, 관측된 문제나 실험 결과가 있어야 검토할 결정을 분리합니다.

### 구현 전에 결정할 항목

| 후보 | 상태 | 결정 시점과 필요한 근거 |
| --- | --- | --- |
| Reservation 상태 모델 | `Proposed` | Reservation Core 구현 전에 허용 상태, 전이, 종료 상태와 거부 규칙을 정의합니다. |
| Capacity 모델 | `Proposed` | 예약 생성 구현 전에 시간 구간, Resource, 수량 중 무엇이 수용량의 기준인지와 핵심 invariant의 트랜잭션 경계를 정합니다. |
| Multi-tenancy 격리 | `Proposed` | tenant 데이터를 저장하기 전에 tenant 식별·전파 방식, 데이터 접근 경계와 권한 검증 위치를 정합니다. |

### 증거가 생긴 뒤 결정할 항목

| 후보 | 상태 | 검토를 시작할 증거 또는 선행 조건 |
| --- | --- | --- |
| HOLD 만료 처리 방식 | `Deferred` | HOLD 요구사항과 허용 만료 오차, 복구 목표, 예상 부하가 구체화되어야 합니다. |
| Transactional Outbox | `Accepted` | #80/ADR-0007의 MySQL atomic append와 receipt/fencing 계약을 M3~M5 구현 및 ADR-0008에서 보존합니다. |
| Kafka 상시 운영 adoption | `Deferred` | #107~#109 구현과 #111 실제 비교/drill은 experimental topology로 유지합니다. Default와 repository/runtime 지위를 별도로 확정했으며 retention/장기 운영 부담과 추가 요구/evidence 없이 supported production alternative로 채택하지 않습니다. |
| Redis | `Deferred` | DB만으로 충족하지 못하는 지연·부하·분산 조정 문제가 측정되어야 하며 캐시 정합성 비용을 비교해야 합니다. |
| Observability stack | `Accepted` (local/container) | #106/#111의 Actuator/Micrometer, protected scrape/read-only inventory, Prometheus/Grafana/Tempo 및 실제 alert/trace/drill. Production SLO/HA 승인은 아닙니다. |
| MCP Gateway | `Accepted` (M6 bounded profile) | #130/ADR-0009에서 Customer 접근과 management 지식/조회가 공유할 인증·registry·admission 요구, single-instance co-location과 HTTP Product invocation을 확정합니다. SDK/runtime 활성화 검증은 #131/#132가 소유합니다. |
| RAG | `Accepted` (책임/비교 계약) | #130/ADR-0009에서 corpus authoring/visibility/version/publication과 Product authority 분리, 동일 seed/query/oracle의 lexical·actual embedding 비교를 확정합니다. Concrete retrieval default와 index/provider 선택은 #134 evidence로 결정하며 vector DB는 채택하지 않습니다. |
| Model Router | `Accepted` (bounded synthetic profile) | [ADR-0010](0010-m7-bounded-model-router.md)/#152의 두 actual 모델 비교, eligibility/selection replay와 provider policy evidence를 채택합니다. Customer는 3.5 Flash-Lite만 eligible하며 Management/Ops는 unavailable입니다. 실제 flow 활성화와 Runtime의 채택은 #153/#155의 별도 evidence가 필요합니다. |
| Agent Runtime | `Deferred` | [M7 공통 계약](../architecture/m7-model-router-agent-runtime.md)을 기준으로 #153의 bounded process-local 실행/Run authority/approval/outcome evidence와 #155의 실제 flow를 확인합니다. Restart-durable 필요성은 #156의 별도 gate이며 positive decision도 미설계 M7-D의 Accepted 구현을 뜻하지 않습니다. |
