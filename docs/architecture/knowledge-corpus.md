# Tenant 문서 소유권·버전·수명주기

Issue [#133](https://github.com/krestar/slotq/issues/133)의 구현 계약이다.
상위 정책은 [ADR-0009](../adr/0009-m6-authenticated-access-and-knowledge.md)와
[M6 §8](m6-access-knowledge.md#8-corpus--retrieval-authority)을 따른다.

## Authority와 module 경계

`knowledge`는 `knowledge_documents`/`knowledge_versions`와 해당 Flyway migration만 소유한다.
Document identity는 `(Tenant, Venue, document UUID)`, immutable version identity는 여기에
`version UUID`를 붙인다. UTF-8 원문 SHA-256, visibility, source UUID/reference/title은 version에
고정되며 동일 version의 다른 입력은 거부한다. Source는 provenance이며 URL fetch나 authority가
아니다. 사람이 읽는 policy document와 Product `BookingPolicy` version은 별개다.

원문은 bounded text로 로컬 MySQL에 저장한다. Metadata와 current pointer가 authority이며
index/chunk/provider copy는 이를 대체하지 못한다. Product Reservation/Availability/Waitlist와
structured policy를 저장·검색하지 않는다. Product module은 knowledge를 의존하지 않는다.
Knowledge는 Auth `ActorAccess`, active Venue `PublicVenueQuery`의 공개 port를 사용하며
Product/Auth JPA entity/repository 또는 table query를 사용하지 않는다. Scope FK만 Venue를 참조한다.

## Trusted authoring

`CorpusAuthoring`은 서버 관리 adapter에서 호출하는 application contract다. 새로운 HTTP/MCP
upload endpoint, editor, 자동 startup seed는 없다. 모든 command는 Auth-owned
`ActorAccess.requireOriginalConfigurationAccess`로 original credential과 현재 configuration
권한을 검사한다. Owner와 해당 Venue에 assigned된 Manager만 허용한다. Customer, Staff,
unassigned Manager, MCP/PRODUCT audience, M5 recovery/scrape credential은 거부한다.

Venue ID는 target selector다. Active Venue 공개 lookup에서 Tenant를 derive하고 exact scope를
metadata에 결합한다. Manifest의 Tenant는 일치 검사에만 사용하며 principal/role/allowlist는
입력하지 않는다. Row lock 획득 후에도 original credential/current access를 다시 확인하여
lock wait 전 관찰로 권한 변경을 우회하지 못하게 한다. 이는 현재 Auth의 admission 경계를
재사용하며, 그 관찰 이후의 grant revocation을 이미 완료한 command에 소급 적용하지 않는다.

## Publication와 arbitration

각 command는 독립 `REQUIRES_NEW`, `READ_COMMITTED` MySQL transaction이며 document row를
`FOR UPDATE`로 직렬화한다. Caller는 `inspect`에서 얻은 revision으로 stage/withdraw를 요청한다.
충돌 시 `STALE_REVISION`으로 거부하며 자동 retry하거나 새 version을 발급하지 않는다.

| Command / 상태 | Persistence와 결과 |
| --- | --- |
| `stage` / `STAGED` | Immutable input과 actual/expected digest를 저장하고 document revision 증가. 기존 publication 유지. 동일 입력 replay는 revision을 바꾸지 않음 |
| `validate` / `VALIDATED`, `FAILED` | Stored content의 digest/identity 검사. 실패 상태를 commit하며 current pointer를 바꾸지 않음 |
| `publish` / `PUBLISHED` | 검증된 version의 staged revision이 현재 document revision과 같아야 함. 새 version publication, old `SUPERSEDED`, current pointer 교체와 revision 증가가 동일 transaction |
| 실패한 publish | Pointer, supersession, 새 publication 모두 rollback. 기존 current 유지 |
| `SUPERSEDED` | 이전 publication의 metadata/content를 보존하지만 retrieval-ineligible. 다시 publish 불가 |
| `withdraw` / `WITHDRAWN` | Current pointer 제거, staged/validated/published version withdrawal와 revision 증가가 동일 transaction. Superseded/failed 이력은 상태 보존하되 document withdrawal로 ineligible |
| `cleanup` | Withdrawn document의 로컬 content만 NULL 처리. Metadata/digest/source와 document tombstone 보존 |
| `requestReindex` | Exact Tenant/Venue/Document/Version/digest/visibility와 document revision의 `DerivedWork` 반환. 현재 published version만 허용 |
| `completeReindex` | 같은 original authority와 현재 revision/eligibility를 검사해 boolean 반환. Publication 또는 index write 없음 |

새 staging은 이전 staging의 늦은 publish와 기존 derived ticket을 무효화한다. 실패한 update 뒤에도
마지막 정상 publication의 retrieval은 계속 가능하며 re-index가 필요하면 새 ticket을 요청한다.
Validated였지만 publication 경쟁에서 진 version은 이력으로 남아도 current/eligible이 되지 않는다.
Withdrawn document는 terminal이며 같은 document ID로 재활성화하지 않는다. 재발행은 새 document
identity를 명시적으로 사용한다. 이 tombstone은 cleanup 이후 늦은 작업에도 유지된다.

`DerivedWork`는 eligibility observation이다. Durable job/retry backlog나 인증 credential이 아니며
외부 index I/O와 metadata DB를 하나의 atomic transaction으로 표현하지 않는다. #134의 completion
전에 이 검사를 통과했어도 이후 update/withdraw가 일어날 수 있으므로 retrieval에서 exact metadata를
다시 검사해야 한다. Old completion 또는 artifact residue가 current pointer를 변경할 application
경로는 없다. Concrete index/chunk/embedding와 provider I/O는 #134 소유다.

## Withdrawal와 physical cleanup의 한계

Withdrawal은 corpus authority에서 즉시 ineligible로 만든다. 로컬 payload는 cleanup 전까지
남을 수 있다. Cleanup 결과는 삭제한 로컬 payload 개수와 `externalDeletionVerified=false`다.
External provider/index copy 삭제, backup/disk의 secure erase 또는 hard deletion을 보장하지 않는다.
Metadata와 tombstone retention의 자동 purge도 없다. Inactive Venue는 현재 Auth/active-scope 계약상
command가 거부되며, cleanup에도 유효한 original Actor와 active scope가 필요하다.

## #134 공개 metadata contract

#134의 scoped publication enumeration과 concrete retrieval/최종 observation 구현은
[검색 integration 계약](knowledge-retrieval.md)을 따른다. `publications`는 live delegation에서
얻은 Tenant/Venue/visibility를 joined SQL의 WHERE에 적용한 뒤 최대 64개 current payload를
반환하며 overflow는 실패한다. Authority module의 MCP/embedding runtime 의존은 없다.

`CorpusCatalog`는 내부의 Auth-검증된 delegation ID를 받아 매 호출 `ActorAccess.revalidate`와
active Venue/Tenant를 확인한다. Caller가 만든 `DelegatedActor`나 Tenant 값을 authority로 받지 않는다.
`knowledge.search` allowlist와 `KNOWLEDGE_PUBLIC`/`KNOWLEDGE_OPERATOR` action을 확인한다.
Public visibility는 public action, operator visibility는 management profile의 operator action을 요구한다.
Assigned Staff의 operator read는 허용하지만 authoring은 허용하지 않는다.

- `current`: 해당 scoped Document의 current published immutable content/source/version 반환.
- `revalidateExact`: current가 candidate의 Tenant/Venue/Document/Version/digest/visibility와 exact 일치할 때만 반환.
- `observeExact`: 같은 live scope/visibility의 exact version metadata와 document/current 상태, eligibility 반환.
  Superseded/withdrawn 상태를 확인할 수 있지만 historical payload는 반환하지 않음.

Catalog는 caller의 outer RR transaction을 suspend하고 새 read transaction을 연다. Live scope를
확인한 뒤 단일 joined SQL이 committed document pointer와 version을 함께 관찰한다. 오래된
RR snapshot/ORM cache를 current로 사용하지 않는다. Metadata observation 이후의 withdrawal을
이미 전송한 결과에서 소급 제거하지 않는다. #134는 provider disclosure 전에 scope를 확인하고,
응답 materialization 직전에 exact contract를 호출하여 그 immutable payload에서 excerpt를 구성한다.
Ranking/search/knowledge MCP handler나 provider를 이 Issue에서 제공하지 않는다.

## Bounded seed와 재현

Canonical fixture는
[`seed-manifest.json`](../../backend/src/test/resources/knowledge/seed-manifest.json)이다. Synthetic
Venue 안내/menu/operator 안내 3개이며 실제 Customer PII가 없다. Tenant/Venue/Document/Version/
Source UUID와 content digest가 고정된다. Ingestion 전에 실제 Tenant/Venue와 original Actor/current
access를 server-owned 경로에서 준비해야 하며 fixture가 Product scope나 권한을 생성하지 않는다.

`CorpusIngestion.readManifest(InputStream)`은 최대 128 KiB, 1~8개 document, unknown/missing field와
비정규 UUID를 거부한다. Version input은 최대 16,384 Java character / 65,535 UTF-8 byte다.
`ingest(originalCredential, manifest)`는 모든 scope를 먼저 검증하고 document별 stage → validate →
publish를 호출한다. Batch 전체를 atomic이라고 주장하지 않는다. 후속 document가 실패해도 앞서
성공한 document publication은 유지된다. Durable queue, automatic retry, crawler/fan-out과 provider
failure recovery를 요구하는 실행 단계가 없어 bounded staged mechanism을 선택했다.

## Migration와 검증

V23은 knowledge table 2개와 scope/current-version FK를 추가한다. 기존 Product/Auth/MCP table과
#132 confirmation/idempotency/admission 계약은 변경하지 않는다. V22 이전 데이터에도 additive이며
자동 backfill/index 활성화는 없다. Undo/drop 대신 문제는 forward-fix로 처리한다.

Java 25 / MySQL 8.4에서 다음 repository 명령으로 재실행한다.

```powershell
Set-Location backend
.\gradlew.bat test --tests 'com.slotq.knowledge.*' --tests 'com.slotq.architecture.*' --tests com.slotq.SlotqApplicationTests --tests com.slotq.HoldIdempotencyScopeMigrationTests --tests 'com.slotq.mcp.*' --offline --no-daemon --console=plain
.\gradlew.bat test --offline --no-daemon --console=plain
.\gradlew.bat clean build --offline --no-daemon --console=plain
```

`CorpusIntegrationTests`는 실제 MySQL transaction/rollback, 두 독립 transaction의
`performance_schema.data_lock_waits` 관찰, lock-wait 중 current access 변경과 outer RR snapshot을
검증한다. Architecture tests는 module dependency/owned persistence를 검사한다. 실행별 XML/log와
raw output은 gitignored `build/`에만 생성하고 실제 결과는 PR에 요약한다. Frontend 변경은 없다.
