# Tenant 범위 human operator recovery (#110)

## 인증 decision gate

Endpoint 설계 전에 기존 Product의 bearer resolver, monitoring scrape credential, JDBC/MySQL와
독립 consumer/relay deployment를 확인했다. 개인별 **256-bit opaque bearer token** 하나를 선택한다.
Product principal/role, SystemPrincipal, scrape secret, caller operator ID, localhost는 신원 근거가 아니다.

| 후보 | individual mapping / audit reference | 발급·보관·rotation / 만료·철회 | 운영·테스트·유출 영향 |
| --- | --- | --- | --- |
| 개인 opaque token (선택) | hash → credential UUID → immutable operator UUID와 principal reference | CSPRNG, 외부 secret store, DB expiry/revocation, 새 credential 발급 후 이전 철회 | 기존 JDBC와 HTTPS CLI에 바로 적용; 매 요청 DB 검증; 유출 시 해당 개인의 명시 grant만 행사, 즉시 철회 가능 |
| mTLS | 개인 certificate subject/SAN과 서버 registry 매핑 필요 | CA 발급, 개인 key 보관, renewal, CRL/OCSP 또는 registry revocation 운영 필요 | repository에 개인 CA/client TLS 운영 체계 없음; proxy certificate 전달 trust와 CLI keystore 경계 추가 필요; key 유출은 인증서 만료/철회까지 해당 개인 영향 |
| 외부 OIDC | issuer/subject 안정 매핑 필요 | IdP 발급·rotation·expiry 및 서버 registry revocation | repository에 operator IdP/client 설정 없음; CLI login과 issuer/audience/JWKS 검증 추가 필요; token 유출은 만료 또는 서버 철회까지 해당 개인 영향 |

다중 auth framework, 개인 credential 발급 HTTP API, role/조직 관리 UI는 추가하지 않는다.
`operations_operators.principal_reference`는 조직의 불변 staff reference이며 email/고객 PII를 사용하지 않는다.
퇴사 시 row를 삭제하거나 ID를 재사용하지 않고 `active=false`로 유지한다. Credential은 principal reference를
바꾸지 않으며 rotation 후에도 동일 audit identity다. Reference 변경은 금지하는 운영 절차다.

## 배치와 권한

`slotq.operations.recovery.enabled=false`가 기본값이다. 켜는 배포는 private network의 TLS listener로
제한하고 ingress에서 `/internal/operations/**`를 public route에 연결하지 않는다. 같은 application 안에서도
별도 최우선 security chain이 처리한다. `request.isSecure()`를 강제하고 Origin/CORS 요청은 거부한다.
직접 TLS를 권장한다. TLS termination이 필요하면 외부 forwarded header를 제거하는 신뢰된 private proxy만
사용하고 그 proxy와 servlet connector를 배치 담당자가 명시적으로 설정한다. Client의 forwarded header만으로
이 코드는 secure 상태를 만들지 않는다. Private network/TLS는 개인 authentication과 grant의 대체가 아니다.

서버 DB의 exact `(operator,tenant,logical consumer,action)` grant를 사용한다.
`READ`, `BUSINESS_REPLAY`, `PUBLICATION_RECOVER`는 서로 독립이고 wildcard/all-tenant가 없다.
Product credential과 monitoring credential은 human chain에서 resolve하지 않는다.
매 요청 DB authentication을 수행하고 transaction 안에서 credential/operator/grant를 locking current read로
재검증한다. Lock 뒤 fresh DB UTC로 expiry를 확인한다. Revocation과 승인 transaction은 DB lock으로 직렬화된다.

## Read와 request 계약

기본 경로: `/internal/operations/tenants/{tenant}`. 응답·실패 모두 `Cache-Control: no-store`다.

| Method / suffix | 용도 |
| --- | --- |
| GET `consumers/{consumer}/deliveries?limit=50&afterEvent=...&afterRegistration=...` | tenant+consumer keyset 목록, 1–100, 마지막 item의 두 UUID로 다음 page |
| GET `consumers/{consumer}/deliveries/{event}/{registration}` | original target, authority, publication, intake, attempts/lifetime/fence, stable failure code, receipt/outcome, #108 observation |
| POST `consumers/{consumer}/deliveries/{event}/{registration}/replay` | exact 현재 DEAD target의 human replay |
| GET `publications/{event}?destination=...` | original shared destination와 영향을 받는 consumer 목록; 전체 consumer READ 필요 |
| POST `publications/{event}/recover` | shared publication 복구; 전체 영향 consumer의 PUBLICATION_RECOVER 필요 |
| GET `consumers/{consumer}/operations/{operation}` | durable operation + audit 결과 확인 |
| GET `consumers/{consumer}/audit?limit=50&after=...` | append-only scoped audit 목록; 마지막 operation UUID로 다음 page |

존재하지 않는 target과 권한 밖 target은 동일 `404 TARGET_NOT_FOUND`다.
Original registration membership부터 조회하므로 publication 장애로 아직 intake/delivery가 없는 event도
목록·exact 조회가 가능하다. 이때 delivery state/attempt/fence는 null이며 PENDING/0으로 추측하지 않는다.
Payload, SQL exception, failure detail 원문, Customer PII, credential/hash는 반환하지 않는다. Reason은 1–500자의 nonblank
문자열이며 control character를 거부한다. 운영자가 reason에 PII/secret을 넣어서는 안 된다.
읽기와 복구는 기존 bounded `DeliveryTransactions`의 Spring/JDBC/network/lock timeout을 사용한다.
동기 callback 안에 외부 I/O는 없다. HTTP/CLI timeout은 commit 결과의 증명이 아니다.

Business JSON: `operationId`, `reason`, `expectedState="DEAD"`, `expectedFence`,
`expectedTransport`, `expectedAuthorityEpoch`. Publication JSON: `operationId`, `destination`,
`affectedConsumers` (중복 없는 정렬된 전체 logical consumer), `reason`, `expectedState`, `expectedFence`, `cause`.
`DEAD/PUBLICATION_DEAD` 또는 `PUBLISHED/RETENTION_GAP`만 허용한다. Business DEAD만을 이유로
publication recovery를 호출하지 않는다. Retention incident 확인 책임은 runbook의 사람에게 있다.
Publication의 registration membership는 original boundary interval로 계산하며 deactivation으로 지우지 않는다.

## Transaction / recovery / unknown outcome

Human service는 `EventReplayService`를 호출하거나 그 transaction을 감싸지 않는다.
기존 `DeliveryTransactions`와 Product `JpaTransactionManager`/DataSource에 가입한 JDBC를 사용한다.
한 transaction에서 current identity/grant 확인 → operation ID reservation → exact fingerprint 확인 →
original target/authority locking read → state/fence 확인 → PENDING 전이 → operation result → human audit 순으로 처리한다.
Operation/audit 실패는 상태 전이까지 rollback한다. 기존 TRUSTED_INTERNAL history는 수정하지 않는다.
Human audit는 별도 table에 reason/correlation, stable principal, exact target, expected/prior/post state,
cycle/lifetime/fence와 DB UTC를 저장하며 DB trigger가 UPDATE/DELETE를 금지한다.

Business는 cycle attempts=0, fence+1, immediately due PENDING으로 바꾸고 lifetime, original/registration/receipt를
보존한다. Scope lock은 consumer + transport + durable authority epoch를 함께 확인한다.
Publication도 cycle attempts=0, lifetime 보존, fence+1로 기존 relay에 책임을 넘긴다.
Recovery transaction은 Kafka I/O, intake/target 생성, receipt 변경, business budget 변경을 하지 않는다.
기존 relay가 transaction 밖에서 publish하고 Kafka physical duplicate는 기존 original intake dedup 계약을 따른다.

Operation UUID는 전역 unique이고 authenticated operator에 묶인다. Typed request의 exact JSON과 SHA-256을
둘 다 저장/대조한다. 새 HTTP correlation은 fingerprint에서 제외한다. 같은 operation+같은 request는 최초
audit/result를 반환하며 이후 DEAD, authority cutover 또는 credential rotation이 있어도 새 cycle을 열지 않는다.
다른 actor/request는 `409 RECOVERY_OPERATION_REUSED`; 권한은 retry에도 다시 확인한다.
Stale state/fence는 `409 RECOVERY_STATE_CONFLICT`; wrong scope/authority는 존재 정보 없는 404다.
Commit outcome unknown이면 다른 transaction으로 operation와 audit를 재조회한다. DB가 결과를 증명하면
최초 result를 반환하고, 확인할 수 없으면 `503 RECOVERY_OUTCOME_UNKNOWN`이다. rollback으로 추정하거나
자동으로 새 operation ID를 만들지 않는다. 확인된 rollback은 `500 RECOVERY_FAILED`다.
API 성공은 cycle admission이고 business DONE/receipt 또는 Kafka PUBLISHED 성공을 뜻하지 않는다.

발급/철회와 실제 복구 절차는 [runbook](../runbooks/operator-recovery.md)을 따른다.
#109 malformed/quarantine의 tenant 불명 incident는 이 API로 replay하지 않는다. #111 drill,
bulk replay, arbitrary topic/offset, payload edit, public API, delegated AI principal은 범위 밖이다.
