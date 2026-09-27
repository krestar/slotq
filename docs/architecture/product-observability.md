# Product 요청과 event 관측

Issue [#106](https://github.com/krestar/slotq/issues/106)의 관측 경계다.
[transactional event 계약](event-delivery.md)과
[ADR-0007](../adr/0007-use-transactional-event-record-and-db-delivery.md)의 권위 상태는 계속 MySQL이다.
HTTP 응답, metric, log, trace의 존재나 부재로 business commit, rollback 또는 유실을 판정하지 않는다.
Kafka relay/consumer 구현은 이 문서의 범위가 아니다.

## Request, append와 effect의 관계

`RequestCorrelationFilter`는 Spring Security보다 먼저 server UUID를 생성하고
`X-Request-ID` 응답 header에 넣는다. 정상 응답뿐 아니라 인증 실패, 권한 거부, validation 오류와
처리되지 않은 exception 경로에도 동일한 정책을 적용한다. 외부 `X-Request-ID`, `traceparent`,
`tracestate`, `baggage`는 관측 parent나 tenant/권한의 근거로 추출하지 않는다.
request ID는 Reservation/Event/Entry/Offer ID와 별개의 identity다.

| 실행 | OTel 관계 | 결과의 의미 |
| --- | --- | --- |
| HTTP request | 새 `SERVER` root `product.request` | 응답 status에 따른 success/client_error/server_error |
| business event append | 현재 request 또는 background span의 `PRODUCER` child `product.event.append` | transaction completion callback의 committed/rolled_back/unknown |
| delivery effect | 매 시도 새 `CONSUMER` root `product.event.effect`, original append span에 link | effect transaction 결과와 ownership 검증 결과 |
| maintenance와 expiry/discovery | 별도 background root | 현재 실행의 결과; append가 발생하면 그 실행의 child |

append span의 thread scope는 append 메서드 반환 시 분리하지만 span 종료는 caller transaction의
`afterCompletion`까지 기다린다. 따라서 append 메서드가 반환한 것만으로 commit을 기록하지 않는다.
이 callback은 관측 완료에만 쓰며 event를 after-commit으로 append하거나 비동기 발행하지 않는다.

delivery는 request span의 장시간 child로 연결하지 않는다. 최초 append context를 link로 유지하고
`event_id`, fencing token, cycle/lifetime attempt로 각 실행을 구분한다. retry와 DEAD replay 뒤의
attempt는 새 trace를 가지며 original context를 덮어쓰지 않는다. replay 자체의 human authorization과
작업 audit 확장은 #110 소유다. 기존 trusted System replay와 durable audit 의미는 바뀌지 않는다.
target을 읽기 전 실패하면 original context 없이 event/token으로만 attempt를 관측한다.

`PROMOTED`와 정상 no-op(`NO_CAPACITY`, `NO_CANDIDATE`, `NOT_ELIGIBLE`, `SLOT_PAST`, `DEFERRED`)는
effect span의 제한된 outcome attribute와 DB receipt 집계로 구분한다.
정상 no-op도 기존 effect/receipt/DONE transaction을 따른다.

## Metadata와 기존 event 호환성

V15는 `event_records`에 nullable `origin_request_id`, `origin_trace_id`, `origin_span_id`만 추가한다.
새 event INSERT에 현재 append context를 함께 저장하므로 business/event rollback 시 metadata도 사라진다.
기존 event에는 null이 남을 수 있으며 backfill이나 event version 변경을 요구하지 않는다.
DB에서 읽은 metadata도 UUID/trace/span 형식을 검사하고 잘못된 값은 없는 context로 취급한다.

`EventEnvelope`, canonical payload, schema version, business identity, dedupe identity 및
receipt identity에는 관측 metadata가 들어가지 않는다. 같은 event의 재append는 기존 record를 반환하며
새 request metadata로 원 record를 갱신하지 않는다. metadata 없는 legacy event는 새 attempt trace만
생성하여 동일한 handler, fencing, receipt, DONE 및 retry 정책으로 처리한다.

Product business와 event INSERT는 기존 `MANDATORY` transaction을 공유한다. 관측 도입은 route/cutover,
claim, lease, fencing, delivery target, receipt 또는 immutable collision 판정을 변경하지 않는다.
불확실한 commit은 `unknown`이며 confirmed rollback과 구분한다. 관측 코드가 rollback이나 retry를
지시하지 않고, 기존 delivery protocol이 durable claim과 receipt를 통해 복구를 결정한다.

## Export와 Product transaction 격리

Spring Boot Actuator/Micrometer와 명시적인 OTel SDK instrumentation을 사용한다. Java agent,
자동 HTTP/JDBC instrumentation, inbound context extraction 및 환경 전체의 resource 수집은 사용하지 않는다.
resource는 고정 `service.name=slotq-product`다. `slotq.telemetry.sampling-probability`는
0–1 범위이며 기본 1(100%)이다. request/background root에 trace ID ratio sampling을 적용하고
child는 parent 결정을 따른다. 별도 root인 retry/effect는 독립적으로 sampling될 수 있다.
durable origin은 sampling 여부와 별개로 보존하며 link는 확인되지 않은 sampled flag를 주장하지 않는다.

`slotq.telemetry.otlp-endpoint`가 없으면 외부 trace 전송은 disabled다. 설정하면 OTLP/HTTP batch exporter를
사용한다. queue 2,048 spans, batch 256 spans, batch interval 1초, exporter timeout 2초이며
network 전송과 그 실패는 exporter thread에서 처리한다. Product thread는 collector 응답이나
exporter 재시도를 기다리지 않는다. instrumentation/metric 기록의 runtime exception은 business 결과에
전파하지 않는다. export 완료의 success/failure span 수는 제한된 outcome counter로 관측한다.

`slotq.telemetry` log는 queue 1,024개의 `AsyncAppender`와 `neverBlock=true`를 사용한다.
log queue 또는 trace queue가 가득 차거나 process가 종료되면 관측 기록이 유실될 수 있다.
export failure counter는 실제 제출 batch의 결과이며 queue 유실 전체를 정확히 세는 지표가 아니다.
이 선택은 관측 전송이 Product latency와 transaction 성공을 좌우하지 않도록 하기 위한 것이다.
trace/log의 누락은 business 유실 evidence가 아니다.

DB 관측은 opt-in `slotq.observability.database.enabled=true`일 때 별도 thread와 최대 1개 connection의
읽기 전용 pool을 사용한다. Product connection pool과 공유하지 않으며 scrape 시 JDBC 조회를 수행하지 않는다.
기본 sample interval 15초, stale 기준 45초, connection/validation timeout 1초, socket timeout 1.5초다.
각 SQL은 query timeout과 MySQL `MAX_EXECUTION_TIME` 1초, 최대 10,001 rows를 적용한다.
10,000개를 넘는 sample은 truncation을 표시한다. nonlocking 조회도 DB 비용은 발생하므로 이 상한을
전체 데이터의 정확한 count나 production 성능 보장으로 해석하지 않는다.
sample 실패·stale은 health/age와 NaN으로 표현하며 마지막 값을 정상 현재 값으로 계속 노출하지 않는다.

## Management와 telemetry 접근 경계

Product bearer 인증과 scrape 인증은 별도 SecurityFilterChain이다.
기계 수집 주체는 `Authorization: Bearer <scrape token>`을 사용하지만 Product credential resolver를
호출하지 않는다. `slotq.observability.scrape-token`이 없거나 32자 미만이면 fail-closed다.
충분히 긴 무작위 secret을 외부 환경에서 공급하고 Product credential과 재사용하지 않는다.

정확히 `GET /actuator/prometheus`만 constant-time credential 비교 후 허용한다.
Actuator 기본 access는 none이고 prometheus만 read-only로 expose한다.
env/configprops/heapdump/threaddump/health 등 다른 management 요청은 scrape secret을 가지고 있어도
허용하지 않는다. Actuator endpoint matcher도 별도로 적용하므로 endpoint base path 변경이
Product 인증으로의 우회가 되지 않는다. 별도 port/localhost 여부는 authorization의 대체 수단이 아니다.

로컬 검증 stack의 접근 계약은 다음과 같다.

| Surface | 인증과 허용 범위 |
| --- | --- |
| Product scrape | 별도 random bearer secret, Prometheus의 `credentials_file`로 공급 |
| Prometheus query/UI | 별도 Basic 인증; Grafana datasource는 이 credential 사용 |
| OTLP ingest | nginx Basic 인증, `POST /v1/traces`만 허용, Tempo 전달 전 Authorization 제거 |
| Grafana | 별도 login, anonymous/signup/org creation disabled |
| Tempo storage/query | host port 미공개, 신뢰하는 Compose network 내부 Grafana/ingress만 사용 |

Compose network, Docker daemon/host 및 volume 접근자는 신뢰 경계 안에 있다. Tempo 자체의 tenant별
authorization이나 내부 mutual TLS를 제공하지 않으며, 임의 container를 같은 network에 연결할 권한은
관측 데이터 접근 권한에 해당한다. host에 bind한 localhost port에도 앞의 인증을 적용한다.
로컬 stack의 HTTP/Basic 경로를 외부 network에 그대로 공개하지 않는다. 원격 배포에는 TLS와 접근 통제가
추가로 필요하며 이 stack은 production 배포 자동화가 아니다.

`infra/observability/setup.ps1`은 `.local` 아래의 ignored secret 파일을 만들고 기존 파일을 덮어쓰지 않는다.
해당 디렉터리, environment와 container inspection 권한은 실행 operator로 제한한다. secret 파일·환경값을
issue, PR, 로그나 evidence에 복사하지 않는다. trace 보존은 Tempo 24시간, metric 보존은 Prometheus 7일이다.
request/event ID를 포함하는 trace, console log 및 volume backup에도 접근 통제와 보존 정책이 필요하다.
이 기계 수집/조회 인증은 #110의 사람 operator recovery API 권한 모델을 구현하지 않는다.

## Cardinality와 redaction

HTTP metric은 framework route template 또는 `UNMATCHED`, 제한된 method/status class와 stable outcome만
사용한다. Product conflict code와 `DB_DEADLOCK`/`DB_LOCK_TIMEOUT`/`DB_CONNECTION`은 구분하지만
exception class/message, raw URL/query, tenant/principal, Reservation/Event/Entry/Offer ID, idempotency key는
metric label로 넣지 않는다. 알 수 없는 business code는 고정 `BUSINESS_CONFLICT`로 축약한다.
자동 `http.server.requests` meter는 비활성화하고 명시적인 Product timer를 사용한다.

log/trace에는 생성한 request ID, event ID, trace/span ID와 attempt 정보만 필요한 범위로 넣는다.
request body, 전체 event payload, credential/token, 연락처, SQL/SQL parameter 또는 throwable를 전달하는
관측 API는 만들지 않는다. HTTP completion log의 route도 raw path가 아닌 framework template이다.
외부 header와 body를 수집한 뒤 정규식으로 지우는 방식 대신 허용된 field만 처음부터 기록한다.

`logback-spring.xml`은 SQL/bind/JDBC/connection-pool/exporter의 raw 메시지를 억제한다.
SQL을 포함한 throwable를 출력할 수 있는 scheduler fallback 및 transaction manager logger도 억제한다.
delivery cycle 실패는 별도의 finite failure metric과 안전한 log로 관측한다.
실행 환경에서 DEBUG/TRACE, SQL 출력, request-body logging 또는
외부 auto-instrumentation을 별도로 켜면 이 명시적 allowlist 경계의 범위를 벗어난다.
운영 장애는 stable failure code, sample health, export failure 및 durable state로 조사한다.

## Client error correlation 결정

이번 변경에서는 Frontend state machine이나 화면을 수정하지 않는다. 브라우저 Network 도구에서
실패 response의 `X-Request-ID`를 복사하여 접근 통제된 운영 log/trace와 대조할 수 있고,
기존 CORS 허용 origin에는 `Location`과 함께 `X-Request-ID`만 expose한다.

UI에 표시·복사 기능을 넣으면 Customer/management의 여러 오류 상태에 request ID 수명과 표시 위치를
추가해야 한다. 현재 최소 진단 요구는 response header와 개발자 도구로 충족하며, 사용자 지원 화면에
지속적으로 필요하다는 evidence가 생길 때 좁은 UI 변경으로 재검토한다.
별도 vendor RUM, session recording, browser payload 전송은 도입하지 않는다.

응답을 받지 못한 network/unknown에서는 request ID를 관측하지 못할 수 있다. ID의 부재가 mutation 실패,
rollback 또는 재시도 가능성을 뜻하지 않는다. 기존 #97/#103의 unknown mutation, generation/stale
continuation 차단, bounded refresh, auth invalidation 및 server-authoritative state 계약은 유지한다.
request ID를 근거로 mutation을 자동 retry하거나 성공·실패로 재분류하지 않는다.

## 후속 ownership과 검증 기록

#106은 공통 correlation, DB mode signal, metric cardinality와 query/panel 계약을 소유한다.
#107은 실제 Kafka publication/relay signal, #108은 durable intake/lag/consumer signal과 통합 panel evidence를
소유한다. 아직 없는 Kafka signal을 0으로 생성하거나 query에서 `or vector(0)`으로 보충하지 않는다.
#110은 사람 recovery 권한/audit를, #111은 DB/Kafka 비교와 production threshold 보정 및 통합 drill을 소유한다.

regression 대상은 request/auth error correlation, header 위조, scrape credential 분리, synthetic ID cardinality,
log/trace redaction, transaction rollback, nullable legacy metadata, retry/replay attempt link 및 collector 장애다.
이 문서는 구현 계약이며 테스트 실행의 성공 증명은 아니다. 실제 test/build와 dashboard/alert 실행 결과는
별도 evidence와 PR에 실행한 범위대로 기록한다.
