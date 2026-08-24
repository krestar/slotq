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
| [0001](0001-use-java.md) | Backend 언어로 Java 사용 | `Accepted` |
| [0002](0002-start-with-modular-monolith.md) | 단일 배포형 Modular Monolith로 시작 | `Accepted` |
| [0003](0003-use-react-typescript-vite.md) | Thin SPA에 React, TypeScript, Vite 사용 | `Accepted` |

## 후보 등록부

후보를 등록했다는 사실은 특정 해법을 선택했다는 뜻이 아닙니다. Product Backend의 구현에 선행하는 결정과, 관측된 문제나 실험 결과가 있어야 검토할 결정을 분리합니다.

### 구현 전에 결정할 항목

| 후보 | 상태 | 결정 시점과 필요한 근거 |
| --- | --- | --- |
| Reservation 상태 모델 | `Proposed` | Reservation Core 구현 전에 허용 상태, 전이, 종료 상태와 거부 규칙을 정의합니다. |
| Capacity 모델 | `Proposed` | 예약 생성 구현 전에 시간 구간, Resource, 수량 중 무엇이 수용량의 기준인지와 핵심 invariant의 트랜잭션 경계를 정합니다. |
| 요청·명령 Idempotency | `Proposed` | 변경 API 구현 전에 idempotency key의 범위, 저장 기간, 동일 키·다른 payload 처리와 응답 재현 규칙을 정합니다. |
| Multi-tenancy 격리 | `Proposed` | tenant 데이터를 저장하기 전에 tenant 식별·전파 방식, 데이터 접근 경계와 권한 검증 위치를 정합니다. |

### 증거가 생긴 뒤 결정할 항목

| 후보 | 상태 | 검토를 시작할 증거 또는 선행 조건 |
| --- | --- | --- |
| Optimistic/Pessimistic/Distributed Lock | `Deferred` | 재현 가능한 동시 예약 실험과 충돌률, 지연, DB 부하 측정 결과가 필요합니다. |
| HOLD 만료 처리 방식 | `Deferred` | HOLD 요구사항과 허용 만료 오차, 복구 목표, 예상 부하가 구체화되어야 합니다. |
| Transactional Outbox | `Deferred` | DB commit과 외부 event publish 사이의 원자성 문제가 실제 흐름에 등장해야 합니다. |
| Kafka | `Deferred` | 단순한 DB 기반 처리나 애플리케이션 내부 이벤트로 충족할 수 없는 전달량, 소비자 분리 또는 보존 요구가 측정되어야 합니다. |
| Redis | `Deferred` | DB만으로 충족하지 못하는 지연·부하·분산 조정 문제가 측정되어야 하며 캐시 정합성 비용을 비교해야 합니다. |
| Observability stack | `Deferred` | 서비스 수준 목표와 추적할 실패·지연 신호가 정의된 뒤 필요한 metrics, logs, traces 범위를 결정합니다. |
| MCP Gateway | `Deferred` | Product API와 권한 경계가 안정되고 둘 이상의 AI 기능이 공통 tool 실행 기반을 요구해야 합니다. |
| RAG | `Deferred` | 비정형 지식 corpus, 갱신 주기, 검색 품질 기준과 transactional state와의 책임 분리가 정의되어야 합니다. |
| Model Router | `Deferred` | 복수 모델을 비교할 실제 traffic과 cost, latency, quality, security 평가 결과가 있어야 합니다. |
| Agent Runtime | `Deferred` | 단일 요청·tool 호출로 해결되지 않는 장기 실행, 상태 유지, 재시도 또는 승인 흐름이 확인되어야 합니다. |
