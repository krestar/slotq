# Human recovery runbook

## 개인 credential 준비와 철회

Windows PowerShell 7, HTTPS CLI와 기존 MySQL 운영 연결을 사용한다. 먼저 private TLS listener와
ingress allowlist를 설정하고 operations deployment에서만 `slotq.operations.recovery.enabled=true`를 사용한다.
발급 담당자는 operator 개인을 검증하고 불변 staff reference와 UUID를 서버에 등록한다.
공유 계정을 만들지 않는다. 아래 SQL은 승인된 DB provisioning role로만 수행하며 recovery API에 SQL을 보내지 않는다.
DB application role에는 operator/credential/grant INSERT·UPDATE·DELETE 권한을 부여하지 않는다.
Audit는 SELECT·INSERT만 허용하며 migration trigger는 UPDATE·DELETE도 거부한다.
V20의 append-only trigger 생성은 binary logging이 켜진 MySQL에서 권한 있는 migration 계정으로
수행한다. Runtime 계정에 SUPER를 부여하지 않는다. Disposable Testcontainers만 기존 trigger-test
패턴의 `--log-bin-trust-function-creators=1`을 사용한다. Production 서버 설정을 자동으로 변경하지 않는다.

```powershell
# 외부 protected volume의 새 파일. 콘솔에는 secret 대신 hash와 credential UUID만 출력된다.
./tools/new-operator-credential.ps1 -SecretPath C:/private/slotq/operator-token
```

생성된 UUID/hash를 다음 parameter에 bind해 provisioning한다. 문자열 UUID는 MySQL
`UUID_TO_BIN(uuid)`의 기본 encoding으로 저장하고 swap flag는 사용하지 않는다. 실제 token을 SQL에 넣지 않는다.
Expiry는 발급 정책에 따라 짧게 정하고 자동 renewal은 하지 않는다 (예: 24시간).

```sql
INSERT INTO operations_operators(operator_id,principal_reference)
VALUES (UUID_TO_BIN(:operator_uuid), :immutable_staff_reference);
INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at)
VALUES (UUID_TO_BIN(:credential_uuid),UUID_TO_BIN(:operator_uuid),UNHEX(:token_hash_hex),
        TIMESTAMPADD(HOUR,24,UTC_TIMESTAMP(6)));
INSERT INTO operations_grants(operator_id,tenant_id,consumer_id,action)
VALUES (UUID_TO_BIN(:operator_uuid),UUID_TO_BIN(:tenant_uuid),:exact_logical_consumer,:action);
```

`READ`, `BUSINESS_REPLAY`, `PUBLICATION_RECOVER`는 필요한 차원만 별도로 발급한다. Tenant UUID가 없는
grant는 없다. Publication operator는 original event에 영향을 받는 **전체 logical consumer**의
PUBLICATION_RECOVER와 조회용 READ를 받아야 한다. 개인 secret 파일은 별도 보안 채널로 해당 개인만
받아 secret store에 보관한다. 최소 파일 ACL 외에 disk encryption/secret manager를 사용한다.
Git ignored 파일도 secret 보관소로 사용하지 않는다. Script가 만든 파일에는 현재 Windows 사용자만 ACL이 있다.
Operator에게 전달하려면 승인된 secret store로 transfer하고 발급 담당자의 임시 파일을 안전하게 제거한다.

Rotation은 같은 operator UUID에 새 credential을 발급·전달·검증한 뒤 이전 credential을 철회한다.
Compromise/퇴사 시 즉시 아래 변경을 수행한다. 과거 audit/principal/operation/credential row는 삭제하지 않는다.

```sql
UPDATE operations_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE credential_id=UUID_TO_BIN(:credential_uuid);
UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6)
WHERE operator_id=UUID_TO_BIN(:operator_uuid) AND tenant_id=UUID_TO_BIN(:tenant_uuid)
  AND consumer_id=:consumer AND action=:action;
UPDATE operations_operators SET active=FALSE WHERE operator_id=UUID_TO_BIN(:operator_uuid);
```

매 요청 authentication과 transaction current-read가 만료/철회/inactive를 검사한다. 이미 승인 lock을
획득한 진행 중 transaction 뒤에 revocation이 직렬화될 수 있다. 철회 SQL commit 이후 새 복구는 허용되지 않는다.
Token 유출 영향은 그 개인의 현재 exact grant 범위다. Token/private key/고객 정보는 로그·Issue·reason에 넣지 않는다.

## 탐지 → 원인 수정 → 복구 → durable outcome

1. #106 alert/관측에서 tenant, original event, logical consumer, original registration을 식별한다.
   Quarantine의 tenant가 불명확하면 #109 infrastructure incident로 escalation한다.
2. 개인 token을 외부 secret store에서 현재 process의 `SLOTQ_OPERATOR_TOKEN`에 로드한다.
   Token 값을 shell 인자·history·console에 쓰지 않는다. CLI는 HTTPS certificate 검증을 우회하지 않으며 redirect를 따르지 않는다.
3. 아래 exact 조회로 current authority, DEAD/PUBLISHED, fence, attempts/lifetime, intake, receipt,
   #108 observation을 대조한다. List는 limit≤100, 마지막 item의 UUID cursor로 page한다.
4. 원인을 먼저 수정한다 (compatible handler, credential/infrastructure, outage 등). Poison payload는 수정하지 않는다.
   권한 없는 조회와 없는 target은 동일 404이므로 존재 확인을 위해 다른 tenant/consumer를 탐색하지 않는다.
5. Business DEAD는 그 consumer의 BUSINESS_REPLAY만 사용한다. Shared publication에는 별도
   publication 조회로 원 destination와 전체 `affectedConsumers`를 확인한다. DEAD publication 또는 실제
   retention gap incident만 복구한다. 한 consumer의 business DEAD는 shared publication 복구 사유가 아니다.
6. 새 UUID를 한 번 만들고 exact request JSON을 외부 private 파일에 저장한다. 조회한 expected state/fence와
   business transport/authority epoch를 포함한다. Reason은 nonblank≤500자로 원인과 수정 reference를 적고 PII/secret을 제외한다.
7. CLI로 한 번 요청한다. API PENDING admission 후 기존 executor/relay가 처리한다. 새 operation을 자동 생성하거나
   mutation을 자동 재시도하지 않는다. Audit correlation ID는 서버가 생성한다.
8. Response loss/timeout/503 OUTCOME_UNKNOWN이면 **같은 operation** 조회를 먼저 한다. 404/DB 장애를 rollback
   증거로 쓰지 않는다. 같은 credential의 유효성/grant를 확인하고 DB가 복구되면 저장한 exact request를 같은 UUID로 재호출한다.
   최초 result는 과거 admission이므로 이후 target이 다시 DEAD여도 과거 retry로 새 cycle이 열리지 않는다.
9. Exact current target와 authoritative receipt/outcome reference, operation/audit를 대조한다.
   Business는 DONE와 기대 receipt/Offer reference, publication은 relay의 PUBLISHED와 intake를 확인한다.
   Kafka offset은 business DONE가 아니다. Lifetime attempts/원 target/receipt가 보존되는지 확인한다.
10. Stale state/fence(409)는 재조회한다. 이전 operation outcome을 확정한 뒤 새 원인·새 승인 판단에 대해서만
    새로운 operation ID를 만든다. 미회복 DEAD, authority mismatch, audit 불일치, retention/quarantine,
    반복 unknown은 운영 책임자에게 incident reference로 escalation한다. Bulk/offset reset/raw SQL/payload edit로 우회하지 않는다.

```powershell
$privateOrigin = 'https://ops.slotq.internal'
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Consumer waitlist.promotion -Action target -Event $event -Registration $registration
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Consumer waitlist.promotion -Action replay -Event $event -Registration $registration -RequestFile C:/private/slotq/replay.json
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Consumer waitlist.promotion -Action operation -Operation $operation
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Consumer waitlist.promotion -Action audit
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Action publication -Event $event -Destination slotq.waitlist.events.v1
./tools/operator-recovery.ps1 -BaseUri $privateOrigin -Tenant $tenant -Action publication-recover -Event $event -RequestFile C:/private/slotq/publication.json
```

Request 예시 (token 없음):

```json
{"operationId":"00000000-0000-4000-8000-000000000001","reason":"handler compatibility incident fixed","expectedState":"DEAD","expectedFence":5,"expectedTransport":"KAFKA","expectedAuthorityEpoch":2}
```

```json
{"operationId":"00000000-0000-4000-8000-000000000002","destination":"slotq.waitlist.events.v1","affectedConsumers":["operations.event-observation","waitlist.promotion"],"reason":"confirmed retention incident corrected","expectedState":"PUBLISHED","expectedFence":5,"cause":"RETENTION_GAP"}
```

## 로컬 검증

Backend JDK 25·Docker에서 다음을 실행한다. 실제 MySQL의 failure trigger와 commit fault manager로
operation/audit rollback, commit 전/후 unknown을 검증한다. Spring Security chain에 실제 individual token을
보내는 MySQL integration은 expired/revoked/inactive, wrong grant/scope, CORS/TLS, scoped response를 검사한다.
Kafka-owned fixture는 production `JdbcKafkaIntakeStore`로 materialize하고 duplicate intake에 target/budget 불변을 대조한다.
Standalone TLS 배치와 조직 secret delivery/rotation 자체는 배치 담당자가 위 절차로 운영해야 한다.

```powershell
cd backend
./gradlew.bat test --tests '*HumanRecoveryIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*EventDeliveryBoundaryIntegrationTests' --tests '*EventTransportCutoverIntegrationTests' --tests '*EventFoundationArchitectureTests'
./gradlew.bat test
./gradlew.bat clean build
```
