# SlotQ 제품 범위

> 상태: Proposed
>
> 방향 기준: [README Product Charter](../README.md)

## 1. 제품 정의

SlotQ는 실시간 예약·대기 및 멀티테넌트 Venue Operations Platform이다. 첫 번째
책임은 동시 요청, 재시도, 중복 이벤트, 부분 장애 상황에서도 예약 상태와 수용량을
정확하게 유지하는 것이다. AI 기능은 Product Backend가 안정적이고 권한이 통제된
API를 제공한 이후에 추가한다.

초기 제품은 관계형 데이터베이스를 사용하는 API 중심 Modular Monolith다. Product
API를 제품 계약으로 유지하면서 React, TypeScript, Vite 기반의 Thin SPA로 핵심 흐름을
브라우저에서 실행할 수 있게 한다. 메시지 브로커, 분산 캐시, 서비스 분리가 처음부터
필요하다고 가정하지 않는다.

## 2. MVP 정의

MVP는 M0~M5의 누적 완료 기준을 모두 충족했을 때 완료된다. 예약 CRUD만 제공하는
개별 Milestone은 MVP를 향한 중간 단계이지 완성된 MVP가 아니다. M0~M5를 마치면
설정, 예약, 동시성, 대기열 처리, 복구, 운영 가시성을 포괄하는 하나의 재현 가능한
수직 기능 흐름이 남아야 한다.

### 2.1 반드시 제공할 제품 기능

#### Tenant와 Venue 운영

- 하나의 Tenant는 하나 이상의 Venue를 소유할 수 있다.
- Owner와 Manager의 권한은 소속 Tenant와 할당된 Venue로 제한한다.
- Venue는 활성 Resource, 영업시간, 고정 예약 Slot과 데모에 필요한 최소 예약,
  HOLD, 취소, NO_SHOW 정책을 설정할 수 있다.
- Tenant 소유 데이터와 모든 권한 판단에는 명시적인 Tenant 경계가 있어야 한다.

#### 예약 가능 여부와 예약 생명주기

- Customer는 Venue, 시간, 인원수에 맞는 예약 가능 여부를 조회할 수 있다.
- 예약 요청이 성공하면 수용량을 점유하는 HOLD가 생성되고, 실패하면 부분적인
  Reservation이나 Allocation이 남지 않는다.
- 유효한 HOLD는 서버가 결정한 만료 시각 이전에 확정할 수 있다.
- Customer는 정책이 허용할 때 HOLD를 해제하거나 확정 예약을 취소할 수 있다.
- 권한이 있는 Staff는 확정 예약을 CHECKED_IN, COMPLETED, NO_SHOW로 전이할 수 있다.
- 잘못되었거나 오래된 상태 전이는 Controller뿐 아니라 Domain에서도 거부한다.

#### Browser Thin SPA

- React, TypeScript, Vite로 Product API를 사용하는 하나의 Thin SPA를 제공한다.
- Customer 화면은 Availability 조회, HOLD 생성, 확정, 취소, 현재 상태 확인을 지원한다.
- Owner와 Manager 화면은 최소한의 Venue, Resource, Slot, Policy 조회·운영을 지원한다.
- Staff 화면은 권한이 허용된 Reservation의 CHECKED_IN, COMPLETED, NO_SHOW 등 운영
  상태 전이를 지원한다.
- M4에서는 Customer의 Waitlist 등록과 현재 Offer 조회·수락·거절 흐름을 연결한다.
- SPA는 Product API 응답을 표시하고 사용자 입력을 전달하는 Client다. Server의 인증,
  권한, Idempotency, 상태 전이, capacity invariant를 복제하거나 대체하지 않는다.
- 화면에서 Button을 숨기거나 비활성화하는 것은 사용성 보조일 뿐 권한 통제가 아니다.
  모든 요청은 Server에서 다시 검증한다.

#### 정합성과 멱등성

- 동시 요청에서도 한 SlotInventory의 활성 할당량은 capacity를 초과하지 않는다.
- HOLD 생성, 확정, 취소, Staff 명령에는 명시적인 Idempotency 계약이 있다.
- 동일한 Idempotency Key와 동일 요청을 반복하면 최초 결과를 반환하고, 같은 Key에
  다른 요청을 보내면 거부한다.
- 실제 애플리케이션이 사용하는 MySQL transaction과 locking 동작으로 정합성 주장을
  검증한다.

#### Waitlist

- Customer는 정의된 수요 조건으로 Waitlist에 등록하거나 등록을 취소할 수 있다.
- 수용량이 반환되면 다음으로 적합한 Entry의 승급을 시도할 수 있다.
- Customer에게 Offer를 노출하기 전에 실제 promotional HOLD가 확보되어 있어야 한다.
- Offer 수락, 거절, 취소, 만료는 수용량을 누수시키지 않아야 한다.
- 이벤트 중복 전달, 재시도, Worker 재시작이 중복 활성 Offer나 한 번의 수락에 대한
  복수 Reservation을 만들지 않는다.
- MVP에서는 Product API로 Offer를 조회하고 테스트용 알림 Adapter를 사용할 수 있다.
  실제 SMS나 이메일 Provider는 필수 범위가 아니다.

#### 신뢰성과 관측 가능성

- HOLD와 Offer 만료 처리는 Process 재시작 이후에도 복구할 수 있다.
- Event 처리에는 안정적인 Event ID가 있고 멱등 재시도를 지원한다.
- 구조화 로그에는 Correlation ID, Tenant ID, Reservation ID, Event ID를 포함하되
  고객 연락처와 Secret은 포함하지 않는다.
- Health 정보와 Metric으로 capacity 충돌, 멱등 재실행, 만료 지연, Event 재시도와
  최종 실패를 파악할 수 있다.
- Schema Migration과 자동 테스트로 깨끗한 환경에서 지원 상태를 재현할 수 있다.

### 2.2 MVP 완료 Gate

M0~M5는 다음 조건이 모두 참일 때 완료된다.

1. 실제 Build, Test, Migration, 로컬 Database 실행 경로가 있고 Placeholder 명령을
   동작하는 검증처럼 제시하지 않는다.
2. Module 경계와 Tenant 권한 규칙을 자동화된 검증으로 확인한다.
3. Reservation 전이 테스트가 성공, 잘못된 전이, 만료, 반복 명령을 다룬다.
4. MySQL 동시성 테스트에서 활성 Allocation이 capacity를 초과하지 않는다.
5. Waitlist 테스트가 중복 Event, 재시도, 만료와 재시작 후 복구를 다룬다.
6. 민감정보를 노출하지 않으면서 실패한 예약 또는 승급 시도를 설명할 수 있는
   Log와 Metric이 있다.
7. Customer, Owner/Manager, Staff의 핵심 Product API 흐름과 M4 Waitlist 흐름을 Thin
   SPA에서 실행할 수 있고, 각 화면이 Server의 성공·거부·만료 결과를 정확히 반영한다.

API-first는 UI가 없다는 뜻이 아니다. Product API가 독립적인 권위 있는 계약으로 먼저
검증되어야 한다는 뜻이다. 위 Browser Vertical Slice까지 동작해야 MVP Gate를 통과한다.

## 3. Restaurant 초기 데모 경계

초기 표현 도메인은 Restaurant 하나로 제한하며 다음 경계를 명시한다.

- `Venue`는 하나의 Restaurant 지점을 나타낸다.
- `Resource`는 하나의 Table을 나타낸다.
- 임의로 겹치는 시간 구간이 아니라 고정 길이 Slot을 사용한다.
- Table은 배타적 Resource이며 Table별 SlotInventory의 할당 단위 capacity는 1이다.
- `partySize`는 선택된 Table의 착석 가능 인원을 초과할 수 없다.
- 하나의 Reservation은 하나의 Slot에서 정확히 하나의 Table을 할당받는다.
- 하나의 적합한 Table을 선택할 수 있지만 좌석 배치를 최적화하지 않는다.
- Table 결합·분할, 회전 시간 최적화, Walk-in 배치, Overbooking은 MVP에서 제외한다.

Core Model은 Restaurant 이름을 모든 Module에 고정하지 않고 `Venue`, `Resource`,
`Slot`, `Allocation`을 사용한다. 이를 통해 다른 Venue 유형으로 확장할 여지는
남기되, 지금 그 유형의 규칙이나 화면을 구현하지 않는다.

## 4. Actor와 권한 경계

| Actor | 허용 범위 | 경계 |
| --- | --- | --- |
| Customer | 공개 Availability 조회, 자신이 소유한 예약의 생성·확정·취소, Waitlist 등록·해제, 자신의 Offer 수락 | 다른 Customer 데이터 조회, Venue 정책 변경, 임의 Tenant 범위 선택 금지 |
| Tenant Owner | Tenant 구성원·역할과 소속 전체 Venue, Resource, Policy, Reservation, Waitlist 관리 | 다른 Tenant 접근 금지 |
| Venue Manager | 할당된 Venue의 Resource, Policy, Reservation, Waitlist 관리 | Tenant 소유권 이전, 상위 역할 부여, 미할당 Venue 접근 금지 |
| Staff | 할당 Venue의 CHECKED_IN, COMPLETED, NO_SHOW 등 현장 명령과 업무에 필요한 최소 고객 정보 조회 | 별도 권한 없는 구성원·capacity·보안 설정·Policy 변경 금지 |
| AI Agent | 명시된 Tenant, Venue, Action, 만료 범위와 Tool Allowlist 안에서 Principal을 대신해 Product API 호출 | 독립 관리자 역할, Database 직접 접근, 검색 문서에서 권한 획득 금지 |
| System Worker | 제한된 Service Identity로 만료, 재시도, 승급을 위한 이름이 있는 내부 Command 실행 | 일반 Owner 또는 Manager 권한 금지 |

Customer의 소유권은 인증된 Subject 또는 범위가 제한된 Reservation 관리 Credential로
증명할 수 있다. 인증 Provider의 정확한 선택은 구현 결정이지만, 권한 판단은 요청자가
보낸 Tenant ID만 신뢰해서는 안 된다.

AI Agent는 권한 우회 수단이 아니라 위임된 호출자다. 상태를 변경하는 Tool Call은 직접
호출한 Product API와 동일한 권한, 검증, Idempotency, Audit 경로를 사용한다.

## 5. Source of Truth

| 정보 또는 동작 | 권위 있는 Source | 설명 |
| --- | --- | --- |
| 현재 Availability와 capacity | Product Backend와 관계형 Database | 권위 있는 Inventory와 활성 Allocation에서 계산한다. |
| Reservation, HOLD, 취소, CHECKED_IN, Waitlist 변경 | Product API | 모든 변경은 Domain Rule, 권한, Idempotency 검사를 거친다. |
| 실제로 강제하는 예약·취소 규칙 | Version이 있는 구조화된 Product Backend Policy | Reservation은 적용된 Policy Version 또는 계산된 Deadline을 저장한다. |
| Venue 안내, Menu 설명, 사람이 읽는 Policy 안내 | Venue Content, 장기적으로 RAG 검색 가능 | 검색 문서는 구조화된 강제 규칙을 덮어쓸 수 없다. |
| Actor와 Tenant 범위 | Access and Tenancy Module과 인증된 Principal | Tenant 범위는 Server에서 결정한다. |
| Event 처리 진행 상태 | 내구성 있는 Product Backend 처리 Record | Broker Ack만으로 Business State가 확정되지는 않는다. |
| Browser에 표시하는 상태 | Product API Response | SPA의 Local State나 Cache는 권위 있는 상태가 아니며 변경 후 Server 결과로 갱신한다. |

Vector Search 결과, LLM 응답, Cache Entry, Message Broker는 Reservation의
Transactional Source of Truth가 아니다.

Thin SPA도 Source of Truth가 아니다. Client-side 검증과 Route Guard는 빠른 Feedback을
제공하지만 Server Authorization과 Domain invariant를 대체하지 않는다.

## 6. MVP에서 하지 않는 것

- 여러 Venue 유형별 Product, UI 또는 Rule Engine
- SSR, SEO 최적화와 별도 Server Rendering Architecture
- Native Mobile Application
- 대형 Design System과 범용 Component Library 구축
- 복잡한 Calendar 편집, Dashboard, Analytics Visualization
- Product Backend가 준비되기 전 AI 전용 UI
- 임의 길이·반복 Reservation
- 복수 Resource 또는 Table 결합 최적화
- 결제, 환불, Coupon, Subscription, Dynamic Pricing
- 전체 Customer 관계 관리 또는 Marketing 자동화
- 실제 SMS, Email, Push Provider 연동
- 수요 예측, 추천, 자율 운영
- MCP Gateway, RAG, Model Router, Agent Runtime, Evaluation
- 측정된 필요가 없는 Redis, Kafka, Kubernetes, Elasticsearch, MongoDB, Vector DB
- Database 기반 전략을 측정하기 전 Distributed Lock
- Microservice 분리, Multi-region, Global High Availability

## 7. 장기 확장 범위

장기 작업에서도 Product Charter의 Product Backend 65 : AI Platform 35 방향과
필수 개발 순서를 유지한다.

### Product Backend 확장

- Pooled Capacity와 복수 Allocation 전략
- 가변 길이 Reservation과 더 풍부한 Schedule Policy
- 각 도메인의 고유 invariant를 파악한 뒤 Salon, Clinic, Studio, Workshop Adapter 추가
- 결제, 알림 Provider, Calendar 연동과 운영 Reporting
- 측정한 부하 또는 장애 격리 요구가 있을 때 Broker, Cache, Service 분리
- 더 깊은 Security, Audit 보존, Privacy 통제와 Production Hardening

### AI Platform 확장

- 위임 권한, Tool Allowlist, Timeout, Rate Limit, Audit를 갖춘 MCP Gateway
- 비정형 Venue 지식을 위한 RAG
- 측정한 Cost, Latency, Quality, Security 조건에 따른 Model Router
- Customer Agent, Owner Copilot, Ops Agent 흐름을 위한 Agent Runtime
- Retrieval, Tool 선택, Parameter 추출, 실행 성공, 금지 동작 탐지를 위한 Evaluation

AI 기능은 인증된 Product API의 Consumer로 남으며 별도의 Reservation System이 되지
않는다.
