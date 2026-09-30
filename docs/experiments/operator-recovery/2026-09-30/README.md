# Issue #110 완료 조건 대조와 검증

기준 `main`: `57cfd3c181e84ddd5dabfd1e81dd9c8eda611b7d` (#108/#109 merge 포함).
선택한 인증은 개인별 opaque bearer token 하나다. [인증 결정과 API 계약](../../../architecture/operator-recovery.md),
[발급·철회·복구 runbook](../../../runbooks/operator-recovery.md)을 함께 검토한다.

`HumanRecoveryIntegrationTests`는 MySQL 8.4 Testcontainers와 실제 Spring Security chain을 사용한다.
Authentication을 mock하거나 request operator ID를 principal로 주입하지 않는다.
Kafka-owned fixture는 원본 trace metadata까지 포함한 canonical wire를 production intake에 넣는다.
실제 Kafka publish 회귀에는 `apache/kafka:4.1.1` 단일 broker를 사용하며, 이는 TLS broker/HA fault 검증의 대체물이 아니다.

| Issue 완료 조건 | 현재 구현 및 실제 regression |
| --- | --- |
| SystemPrincipal과 구분된 individual human | `OperatorCredentials`의 SHA-256 → credential UUID → operator UUID mapping; `credentialNegativeBoundary`, `callerOperatorIdAndMissingExpectedVersionDoNotSupplyIdentityOrRecover` |
| 발급·만료·철회·stable principal | 외부 private 파일 CSPRNG/ACL 발급 CLI, DB TTL/revocation; `rotationKeepsStablePrincipalAndHistoricalResultButRevokedTokenStops`, `credentialExpiryDuringLockWaitIsRejectedUsingPostLockDbTime` |
| tenant/consumer/action 및 Product 분리 | 실제 유효 Product resolver credential 및 monitoring credential의 ops 접근 거부; `privateTlsAndCorsBoundaryAndOperatorCannotUseProductApi`, `currentCredentialAndGrantAreRevalidatedInsideTransaction`, `existingCrossTenantEventAndMixedRegistrationNeverRecover` |
| human business transition/audit physical atomicity | 세 mutation stage의 MySQL `CONNECTION_ID()`가 하나임을 대조; `preservedReceiptAndObservationRemainScopedAndRecoveryUsesOnePhysicalMysqlTransaction`; audit INSERT 및 operation INSERT/final UPDATE failure에서 state/operation/audit 전부 rollback |
| duplicate/stale/concurrent/unknown에서 추가 cycle/effect 없음 | `concurrentOperatorsAndDuplicateOperationSerialize`, `responseLossLaterDeadAndChangedRequestCannotOpenAnotherCycle`, `normalTargetsAndStaleFenceCannotReplay`, `commitOutcomeUnknownReconcilesFromOperationAndAudit`, `historicalOperationRetryStillRequiresCurrentGrant` |
| business와 publication action 분리 | `businessGrantDoesNotAuthorizePublicationAndPublicationNeedsWholeBlastRadius`, `publicationOnlyGrantDoesNotResetOrAuthorizeBusinessDeadTarget` |
| publication state/operation/audit atomicity, broker I/O 분리 | 동일 physical connection과 audit/operation 실패 rollback; `committedHumanPublicationRecoveryIsPublishedByExistingRelayOutsideRecoveryTransaction`은 HTTP 승인 → durable PENDING → 기존 relay claim/publish → 실제 broker record와 DB PUBLISHED를 확인 |
| publication response loss/duplicate/audit failure | `publicationResponseLossLaterDeadRelayAndDuplicateIntakePreserveTargetsBudgets`, `reusedPublicationOperationRejectsEveryChangedSemanticRequest`, commit-before/after unknown 및 operation finalization failure |
| DB direct / Kafka-owned 보호 | `protectedReplayPreservesOriginalIntakeAndInternalHistory`, `admittedReplayExecutesOnceAndHistoricalRetryAfterDoneDoesNotRepeatEffect`의 양 transport parameter; `staleTransportEpochCannotReplayAndHistoricalRetrySurvivesCutover` |
| private API/CLI/scoped/audit/runbook | Default disabled/TLS/Origin rejection; `wrongScopeMissingTargetsAndRevokedReadGrantAreIndistinguishableAndListsBounded`, `scopedReadsIncludePublicationFailureBeforeIntakeAndPageAllOriginals`, `publicationAuditReadRequiresEveryConsumerAndPaginationSkipsHiddenRows`, append-only audit UPDATE/DELETE rejection |

Audit의 JSON에는 immutable principal reference, original tenant/event/registration/consumer/destination,
action/cause/reason, 최초 correlation, expected/prior/post state, cycle/lifetime/fence, DB time가 들어간다.
Exact request와 fingerprint는 operation에 저장된다. HTTP response loss는 최초 result를 버린 뒤 같은
operation/request를 다시 호출하고, 다시 DEAD가 된 뒤에도 fence가 증가하지 않는 것으로 검증한다.
Commit fault manager는 실제 MySQL commit 후 응답 실패와 실제 rollback 후 outcome unknown을 별도로 만든다.
이는 process/broker HA matrix를 다시 실행했다는 주장이 아니다; #109의 기존 protocol/evidence는 보존한다.

발급 CLI는 repository 밖의 검증용 파일에 실제 실행해 token 형식과 현재 Windows 사용자만 허용하는
ACL을 확인했고 파일은 제거했다. 두 CLI의 PowerShell parser 검증도 통과했다.
조직의 실제 credential 전달/배치와 private TLS listener 운영은 runbook 절차를 따른다.

V20 trigger를 일반 테스트 계정으로 생성하도록 disposable MySQL fixture에 기존
`--log-bin-trust-function-creators=1` 설정을 적용했다. Production 서버 설정은 변경하지 않는다.
기존 schema version 및 credential-column 검사는 V20의 public credential UUID와 BINARY(32) hash만 정확히
허용하도록 갱신했다. 이전 Waitlist recovery raw/harness provenance는 다시 쓰지 않았다.

## 수행 명령

JDK 25.0.4.1, repository Gradle Wrapper 9.7.1과 Docker Desktop에서 수행한다.

```powershell
cd backend
./gradlew.bat test --tests '*HumanRecoveryIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*EventDeliveryBoundaryIntegrationTests' --tests '*EventTransportCutoverIntegrationTests' --tests '*EventFoundationArchitectureTests' --no-daemon --offline --console=plain
./gradlew.bat test --tests '*HumanRecoveryIntegrationTests' --tests '*AuthWebIntegrationTests' --tests '*ProductionAuthIntegrationTests' --tests '*HoldIdempotencyScopeMigrationTests' --tests '*WaitlistProcessRecoveryEvidenceTests' --tests '*SlotqApplicationTests' --no-daemon --offline --console=plain
./gradlew.bat test --tests '*WaitlistBaselineSmokeTests' --tests '*WaitlistBaselineEvidenceTests' --tests '*WaitlistProcessRecoveryEvidenceTests' --no-daemon --offline --console=plain
./gradlew.bat clean build --no-daemon --offline --console=plain
```

최종 집계는 clean build의 JUnit XML에서 생성한 `verification.json`을 따른다.
Opt-in skip은 성공으로 집계하지 않는다. GitHub CI 결과를 이번 로컬 결과로 대신 주장하지 않는다.

최종 `clean build`는 **BUILD SUCCESSFUL (16분 40초)**이다. 포함된 전체 Backend test의 JUnit XML은
77 suite / 664 test node 중 **655 passed, 9 opt-in skipped, 0 failure, 0 error**다.
Class-level opt-in skip node도 XML 집계에 포함한다. 새 human recovery regression은 **53/53 passed**이고,
전체 실행에는 기존 delivery/authority/security 및 실제 MySQL baseline smoke도 포함된다.
`verification.json`은 suite별 수치, human case 목록과 LF로 정규화한 source SHA-256을 보존한다.
