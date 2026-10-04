# ADR-0009: 인증된 Product API와 위임 접근·지식 경계로 M6 구성

- 상태: `Accepted`
- 결정일: 2026-10-04
- 관련 Issue: [#130](https://github.com/krestar/slotq/issues/130)
- 구현 계약과 근거: [M6 Access & Knowledge](../architecture/m6-access-knowledge.md)
- 확인한 main: `688c1a58cd9b8a95b41ba1fc7f2d763bb13b73b4`

## 맥락

M5가 완료되어 Customer 예약과 management 조회, tenant 권한, Product 정합성·관측 경계를
재사용할 수 있다. M6의 두 소비 기능은 Customer 예약 접근과 management 지식/예약 조회다.
공통 protocol, 위임 인증, admission, rate, audit가 필요하지만 Agent Runtime과 Router는
M7의 책임이다. Venue 안내·menu·allergy·사람이 읽는 policy에는 비정형 corpus가 필요하다.
Product의 실제 capacity·예약·Waitlist·structured policy를 검색 index로 복제하지 않는다.

[Product Scope](../product-scope.md), [Domain Model](../architecture/domain-model.md)의
`AI Platform → 인증된 Product API`를 유지한다. Public application port는 내부 module
협력의 계약이며 external credential 인증이나 HTTP wire validation의 대체가 아니다.
현재 Product에는 local/test resolver만 있고 production credential mapping은 없다.
이 gap의 구현 owner는 #131이다. Principal 객체를 생성하는 것만으로 gap을 닫지 않는다.

## 결정

### Runtime과 Product invocation

M6 supported profile은 **한 live Product JVM에 opt-in으로 co-located된 MCP runtime**이다.
같은 Backend artifact/build를 사용하며 별도 service, DB, deployment role을 만들지 않는다.
기본 Product runtime에는 MCP를 자동 노출하지 않는다. MCP는 별도 인증된 HTTP 경로를
사용하고 Product transactional read/write는 고정 origin의 기존 HTTP Product API를 호출한다.
동일 JVM loopback 호출도 Product security filter, controller와 DTO validation을 통과한다.

MCP의 executor/concurrency를 제한하고 Product 처리 thread 여유를 보존한다. Process·DB
장애와 배포 주기는 공유한다. 독립 scaling/failure domain을 보장하지 않는다. M5 worker,
human recovery, scrape의 activation과 authority는 기존 계약 그대로다.

| 후보 | 판단 |
| --- | --- |
| Co-located runtime + HTTP Product API | 선택. 기존 인증·wire·error·transaction 경계를 직접 재사용. 추가 network outcome unknown과 shared process 비용을 명시적으로 수용 |
| Same-process application port | 기각. 현재 port는 credential 검증/HTTP validation 전체를 제공하지 않으며 동등성 입증 없음 |
| Same-codebase 별도 runtime role | 보류. 현 요구에 필요한 failure/scaling 근거 없음. 별도 role은 inactive writer/worker/operations 검증 비용 추가 |
| 독립 deployment/microservice | 보류. `Gateway`라는 이름이나 protocol revision 차이는 service 분리 근거가 아님 |

### Protocol과 credential profile

M6 target은 **MCP `2025-11-25`, Streamable HTTP, initialize 기반 lifecycle, tools only**다.
작업일의 최신 stable spec은 `2026-07-28`이지만 stable Java SDK `2.0.1`은 `2025-11-25`를
지원한다. 최신 spec의 stateless/per-request negotiation을 구 revision 구현에 광고하지 않는다.
Tasks, sampling, roots, resources/prompts, elicitation 기반 approval은 M6 요구가 아니다.

초기 auth profile은 **통제된 client가 사전 발급받은 opaque bearer credential**이다.
HTTPS, 고정 origin, 서버 소유 Actor/credential/delegation record를 사용한다. 범용 공개 MCP
client의 OAuth discovery/login을 지원한다고 주장하지 않는다. HTTP authorization spec의
OAuth profile 권고를 따르지 않는 제한 profile을 선택하는 이유는 M6가 통제된 두 Actor
profile의 tool 호출을 요구하며 external IdP/임의 client onboarding을 요구하지 않기 때문이다.
OAuth authorization-server/resource-server flow는 채택하지 않는다.

MCP credential과 downstream Product credential은 audience와 값이 다르다. Product용
credential도 같은 original Actor와 더 좁은 delegation을 검증한다. Broad service identity와
raw token passthrough는 허용하지 않는다. Product의 Auth 경계가 이를 소유하며 MCP를
역참조하지 않는다. Original Actor credential은 AI caller에게 전달하지 않는다.

Java SDK와 Spring integration은 auth를 대신하지 않는다. #131은 stable artifact 조합을
고정하고 SlotQ Java 25/Boot 4.1.1에서 dependency resolution, start, 실제 wire/client
검증을 통과해야 활성화할 수 있다. Spring AI 2.0.x의 Boot 4.1 지원 문구만으로 이 조합의
실행 성공을 주장하지 않는다. 이 implementation gate는 protocol/auth 정책 재결정이 아니다.

### Authority와 replay

Delegated scope는 매 admission 시 original Actor의 현재 권한과 교집합이다. Customer
ownership과 operator membership을 별도로 유지한다. Tool allowlist는 추가 제한이다.
Write confirmation은 인증된 original approver의 exact intent 승인 record다. #132가 durable
binding과 retry 상태를 소유하며 Product reliability row에 직접 접근하지 않는다.

ADR-0005의 successful `completedAt + 24h` retention을 변경하지 않는다. M6의 immutable
intent는 최초 dispatch부터 최대 15분만 명시적으로 retry할 수 있다. Confirmation은 최대
5분, delegation은 최대 15분이며 renewal이 최초 intent의 retry 종료를 이동시키지 않는다.
COMMIT 뒤 response loss는 outcome unknown이다. 알려진 Reservation ID는 exact Product
GET으로 확인하고, target을 모르면 unknown을 유지한다. 자동 새 key/자동 mutation retry는 없다.

Supported topology에는 durable queue, task redelivery, 재시작 후 dispatch가 없다. MCP admission
→ Product command admission은 최대 60초인 configuration/execution gate를 #131/#132가
입증한다. 현재 Product의 timeout annotation/config만으로 이 bound가 이미 구현됐다고
주장하지 않는다. Gate 실패 시 write topology를 활성화하지 않는다. 별도 Product command
deadline protocol이나 idempotency 일반화는 도입하지 않는다.

### Quota, audit와 knowledge

Rate/concurrency quota는 **단일 live MCP instance의 local 보호**다. Principal/delegation/tenant/
tool과 retrieval/provider 비용을 제한한다. Shared/global quota 또는 multi-instance guarantee를
약속하지 않으며 Redis를 추가하지 않는다. Timeout 뒤 계속 실행되는 작업도 permit을 유지한다.

MCP audit는 redacted metadata의 best-effort 비차단 관측이며 완전한 durable business ledger가
아니다. Exporter/sink 실패는 Product 결과를 바꾸지 않는다. Confirmation/intent의 durable
승인 증거와 M5 human recovery의 atomic audit를 이 best-effort 지위로 낮추지 않는다.

Corpus는 별도 knowledge ownership의 document/version/publication metadata가 authority다.
Owner/Manager의 scoped authoring과 `venue_public`/`venue_operator` visibility를 사용한다.
Index/chunk는 derived artifact다. #134는 provider disclosure 전에 scope를 제한하고 응답 직전
current metadata를 재검증한다. Withdrawn/superseded content, 자연어 지시, tool output은
Product rule, authority, confirmation을 변경할 수 없다.

Lexical/full-text와 실제 embedding candidate를 같은 synthetic corpus/query/oracle로
비교한다. Vector DB나 embedding default를 미리 채택하지 않는다. Safety violation은 품질
점수로 상쇄할 수 없으며 raw evidence로 latency/resource/cost와 source/version 결과를
재계산한다. 이 bounded comparison은 M8의 generic evaluation이나 production SLA가 아니다.

## 결과와 후속 ownership

- #131: protocol, credential mapping/delegation, registry, 공통 admission/timeout/rate/audit.
- #132: 세 Product HTTP tool, confirmation, immutable intent/retry/unknown/reconciliation.
- #133: corpus authoring, version/publication, stale work. 선택한 shared credential mapping을
  사용하므로 #130과 #131 이후 구현한다. #131은 corpus나 authoring command를 소유하지 않는다.
- #134: #131 + #133 이후 retrieval/index, knowledge tool, 실제 비교와 default 선택.
- #135: #131~#134 이후 통합 검증과 closure. 새로운 정책을 결정하지 않는다.

Product module/entity/table ownership, cross-module persistence 공유 금지와 dependency cycle
금지를 유지한다. Product는 AI가 없을 때도 작동해야 한다. #130은 문서 결정만 추가하며
위 runtime/security의 production 구현 또는 M6 완료를 주장하지 않는다.

## 재검토 조건

임의 external MCP client/OAuth 요구, shared quota/multiple live MCP instance, 측정된 failure
isolation/scaling 요구, 60초 admission bound 실패 또는 queued execution 요구, audit의 법적
durability 요구, provider disclosure 범위 변경이 생기면 해당 decision을 별도 근거로 재검토한다.
그 전에는 unsafe topology를 지원 목록에 추가하거나 새로운 Product protocol을 만들지 않는다.

## 참고

- [공식 latest spec](https://modelcontextprotocol.io/specification/latest),
  [선택 revision](https://modelcontextprotocol.io/specification/2025-11-25),
  [HTTP authorization profile](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [Java SDK stable release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1),
  [Spring AI compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [ADR-0005](0005-use-mysql-hold-idempotency-record.md),
  [ADR-0008](0008-m5-event-transport-and-runtime-status.md)
