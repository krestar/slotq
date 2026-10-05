package com.slotq.knowledge;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.integration.operations.recovery.OperatorCredentials;
import com.slotq.knowledge.application.*;
import com.slotq.knowledge.domain.Corpus;
import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(properties = "slotq.observability.scrape-token=scrape-SENTINEL-01234567890123456789")
@ExtendWith(OutputCaptureExtension.class)
class CorpusIntegrationTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("corpus_lifecycle");
    @Autowired CorpusAuthoring authoring;
    @Autowired CorpusCatalog catalog;
    @Autowired CorpusIngestion ingestion;
    @Autowired ActorAccessService access;
    @Autowired OperatorCredentials operations;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean CorpusStore store;
    UUID principal;
    TenantId tenant;
    VenueId venue;
    String original;
    UUID publicDelegation, operatorDelegation;

    @BeforeEach void fixture() {
        principal = UUID.randomUUID(); tenant = new TenantId(UUID.randomUUID()); venue = new VenueId(UUID.randomUUID());
        seedScope(tenant, venue);
        original = credential(principal, "OWNER", venue, tenant);
        publicDelegation = delegate(original, venue, AccessProfile.CUSTOMER, Set.of(AccessAction.KNOWLEDGE_PUBLIC));
        operatorDelegation = delegate(original, venue, AccessProfile.MANAGEMENT,
                Set.of(AccessAction.KNOWLEDGE_PUBLIC, AccessAction.KNOWLEDGE_OPERATOR));
    }
    @Test void canonicalManifestReproducesDocumentVersionSourceAndExactReplay() {
        var manifest = CorpusIngestion.readManifest(getClass().getResourceAsStream("/knowledge/seed-manifest.json"));
        seedScope(manifest.expectedTenant(), manifest.venue());
        String owner = credential(UUID.randomUUID(), "OWNER", manifest.venue(), manifest.expectedTenant());
        var result = ingestion.ingest(owner, manifest);
        assertThat(ingestion.ingest(owner, manifest)).isEqualTo(result);
        for (int i = 0; i < result.size(); i++) {
            var input = manifest.entries().get(i).version(); var metadata = result.get(i);
            assertThat(metadata.reference().document()).isEqualTo(new DocumentKey(manifest.expectedTenant(), manifest.venue(), input.documentId()));
            assertThat(metadata.reference().versionId()).isEqualTo(input.versionId());
            assertThat(metadata.reference().contentDigest()).isEqualTo(input.expectedDigest());
            assertThat(metadata.source()).isEqualTo(input.source());
            assertThat(metadata.state()).isEqualTo(VersionState.PUBLISHED);
            assertThat(authoring.inspect(owner, manifest.venue(), input.documentId()).revision()).isEqualTo(2);
        }
        assertThat(result).hasSize(3);
    }
    @Test void ownerAndAssignedManagerCanAuthorButCustomerStaffAndUnassignedManagerCannot() {
        for (String role : List.of("OWNER", "MANAGER")) {
            String token = credential(UUID.randomUUID(), role, venue, tenant);
            assertThat(publish(token, input(UUID.randomUUID(), "Synthetic " + role, Visibility.VENUE_PUBLIC), 0).state())
                    .isEqualTo(VersionState.PUBLISHED);
        }
        for (String role : List.of("CUSTOMER", "STAFF", "UNASSIGNED_MANAGER")) {
            String token = credential(UUID.randomUUID(), role, venue, tenant);
            assertThatThrownBy(() -> authoring.stage(token, venue, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0))
                    .isInstanceOf(AccessFailure.class);
        }
    }
    @Test void allCommandsRejectDelegatedProductRecoveryScrapeAndUnknownCredentials(CapturedOutput output) throws Exception {
        var version = publish(original, input(UUID.randomUUID(), "raw-document-SENTINEL", Visibility.VENUE_PUBLIC), 0);
        var work = authoring.requestReindex(original, version.reference());
        var mcp = access.approveDelegation(original, venue, AccessProfile.MANAGEMENT,
                Set.of(AccessAction.MANAGEMENT_READ), Set.of("management.reservations.list"), Duration.ofMinutes(5));
        String product = access.issueProduct(mcp.delegationId(), ProductOperation.MANAGEMENT_LIST, null,
                Instant.now().plusSeconds(30)).value();
        String recovery = recoveryToken();
        for (String token : List.of(mcp.value(), product, recovery, "scrape-SENTINEL-01234567890123456789", "unknown-SENTINEL")) {
            List<Runnable> commands = List.of(
                    () -> authoring.inspect(token, venue, work.version().document().documentId()),
                    () -> authoring.stage(token, venue, input(work.version().document().documentId(), "New", Visibility.VENUE_PUBLIC), 2),
                    () -> authoring.validate(token, version.reference()), () -> authoring.publish(token, version.reference()),
                    () -> authoring.withdraw(token, venue, work.version().document().documentId(), 2),
                    () -> authoring.cleanup(token, venue, work.version().document().documentId()),
                    () -> authoring.requestReindex(token, version.reference()), () -> authoring.completeReindex(token, work));
            for (Runnable command : commands) assertThatThrownBy(command::run).isInstanceOf(AccessFailure.class);
            assertThat(output.getAll()).doesNotContain(token);
        }
        assertThat(current(version).metadata()).isEqualTo(version);
        assertThat(output.getAll()).doesNotContain(original, "raw-document-SENTINEL");
    }
    @Test void wrongTenantVenueAndManifestClaimsCannotSubstituteScope() {
        var version = publish(original, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0);
        TenantId otherTenant = new TenantId(UUID.randomUUID()); VenueId otherVenue = new VenueId(UUID.randomUUID());
        seedScope(otherTenant, otherVenue);
        assertThatThrownBy(() -> authoring.stage(original, otherVenue, input(UUID.randomUUID(), "X", Visibility.VENUE_PUBLIC), 0))
                .isInstanceOf(AccessFailure.class);
        VenueId sameTenantOtherVenue = new VenueId(UUID.randomUUID()); seedVenue(tenant, sameTenantOtherVenue);
        String manager = credential(UUID.randomUUID(), "MANAGER", venue, tenant);
        assertThatThrownBy(() -> authoring.inspect(manager, sameTenantOtherVenue, version.reference().document().documentId()))
                .isInstanceOf(AccessFailure.class);
        var substituted = new VersionReference(new DocumentKey(otherTenant, venue, version.reference().document().documentId()),
                version.reference().versionId(), version.reference().contentDigest(), Visibility.VENUE_PUBLIC);
        assertReason(() -> authoring.publish(original, substituted), CorpusFailure.Reason.SCOPE_MISMATCH);
        var manifest = new CorpusIngestion.Manifest(otherTenant, venue,
                List.of(new CorpusIngestion.Entry(input(UUID.randomUUID(), "X", Visibility.VENUE_PUBLIC), 0)));
        assertReason(() -> ingestion.ingest(original, manifest), CorpusFailure.Reason.SCOPE_MISMATCH);
        assertThat(catalog.revalidateExact(publicDelegation, substituted)).isEmpty();
        assertThat(catalog.observeExact(publicDelegation, substituted)).isEmpty();
        assertThatThrownBy(() -> catalog.current(publicDelegation, otherVenue, UUID.randomUUID())).isInstanceOf(AccessFailure.class);
        assertThat(current(version).metadata()).isEqualTo(version);
    }
    @Test void currentRoleGrantCredentialAndVenueStatusAreRevalidatedAtEveryCommand() {
        UUID managerId = UUID.randomUUID(); String manager = credential(managerId, "MANAGER", venue, tenant);
        var staged = authoring.stage(manager, venue, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0);
        authoring.validate(manager, staged.reference());
        db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(managerId));
        assertThatThrownBy(() -> authoring.publish(manager, staged.reference())).isInstanceOf(AccessFailure.class);
        db.update("INSERT INTO venue_grants(tenant_id,principal_id,role,venue_id) VALUES(?,?,'MANAGER',?)",
                bytes(tenant.value()), bytes(managerId), bytes(venue.value()));
        var published = authoring.publish(manager, staged.reference());
        db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(managerId));
        db.update("UPDATE tenant_memberships SET role='STAFF' WHERE principal_id=?", bytes(managerId));
        db.update("INSERT INTO venue_grants(tenant_id,principal_id,role,venue_id) VALUES(?,?,'STAFF',?)",
                bytes(tenant.value()), bytes(managerId), bytes(venue.value()));
        assertThatThrownBy(() -> authoring.requestReindex(manager, published.reference())).isInstanceOf(AccessFailure.class);
        db.update("UPDATE auth_access_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE principal_id=? AND audience='ORIGINAL'", bytes(managerId));
        assertThatThrownBy(() -> authoring.inspect(manager, venue, staged.reference().document().documentId())).isInstanceOf(AccessFailure.class);
        db.update("UPDATE venues SET status='INACTIVE' WHERE id=?", bytes(venue.value()));
        assertThatThrownBy(() -> authoring.inspect(original, venue, staged.reference().document().documentId())).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(() -> catalog.revalidateExact(publicDelegation, published.reference())).isInstanceOf(AccessFailure.class);
    }
    @Test void contentSourceAndVisibilityAreImmutableAndDocumentIdentitySurvivesUpdates() {
        UUID document = UUID.randomUUID(); var firstInput = input(document, "Version one", Visibility.VENUE_PUBLIC);
        var first = publish(original, firstInput, 0);
        for (VersionInput collision : List.of(
                new VersionInput(document, firstInput.versionId(), firstInput.source(), firstInput.visibility(), "Changed", Corpus.digest("Changed")),
                new VersionInput(document, firstInput.versionId(), new Source(UUID.randomUUID(), "seed:other", "Other"), firstInput.visibility(), firstInput.content(), firstInput.expectedDigest()),
                new VersionInput(document, firstInput.versionId(), firstInput.source(), Visibility.VENUE_OPERATOR, firstInput.content(), firstInput.expectedDigest()))) {
            assertReason(() -> authoring.stage(original, venue, collision, 2), CorpusFailure.Reason.IMMUTABLE_VERSION);
        }
        var second = publish(original, input(document, "Version two", Visibility.VENUE_PUBLIC), 2);
        assertThat(second.reference().document()).isEqualTo(first.reference().document());
        assertThat(state(first)).isEqualTo("SUPERSEDED");
        var superseded = catalog.observeExact(publicDelegation, first.reference()).orElseThrow();
        assertThat(superseded.metadata().state()).isEqualTo(VersionState.SUPERSEDED);
        assertThat(superseded.retrievalEligible()).isFalse();
        assertThat(superseded.currentVersionId()).isEqualTo(second.reference().versionId());
        assertThat(catalog.revalidateExact(publicDelegation, first.reference())).isEmpty();
        assertThat(current(second).metadata()).isEqualTo(second);
        assertReason(() -> authoring.publish(original, first.reference()), CorpusFailure.Reason.INVALID_STATE);
    }
    @Test void stagingAndFailedValidationKeepLastGoodPublication() {
        var first = publish(original, input(UUID.randomUUID(), "Last good", Visibility.VENUE_PUBLIC), 0);
        UUID document = first.reference().document().documentId();
        var invalid = new VersionInput(document, UUID.randomUUID(), new Source(UUID.randomUUID(), "seed:failed", "Failed"),
                Visibility.VENUE_PUBLIC, "New invalid digest", "0".repeat(64));
        var staged = authoring.stage(original, venue, invalid, 2);
        assertThat(staged.state()).isEqualTo(VersionState.STAGED);
        assertThat(catalog.revalidateExact(publicDelegation, staged.reference())).isEmpty();
        assertThat(current(first).metadata()).isEqualTo(first);
        assertThat(authoring.validate(original, staged.reference()).state()).isEqualTo(VersionState.FAILED);
        assertReason(() -> authoring.publish(original, staged.reference()), CorpusFailure.Reason.INVALID_STATE);
        assertThat(current(first).metadata()).isEqualTo(first);
        var failedManifest = new CorpusIngestion.Manifest(tenant, venue, List.of(new CorpusIngestion.Entry(invalid, 3)));
        assertReason(() -> ingestion.ingest(original, failedManifest), CorpusFailure.Reason.VALIDATION_FAILED);
        assertThat(current(first).metadata()).isEqualTo(first);
    }
    @Test void publicationFailureRollsBackPointerAndSupersessionTogether() {
        var first = publish(original, input(UUID.randomUUID(), "Last good", Visibility.VENUE_PUBLIC), 0);
        var next = authoring.stage(original, venue, input(first.reference().document().documentId(), "New", Visibility.VENUE_PUBLIC), 2);
        authoring.validate(original, next.reference());
        doAnswer(call -> {
            call.callRealMethod();
            if (call.getArgument(0).equals(first.reference().document()) && call.getArgument(1) == DocumentState.ACTIVE) {
                throw new IllegalStateException("Synthetic publication failure");
            }
            return null;
        }).when(store).publication(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> authoring.publish(original, next.reference())).isInstanceOf(IllegalStateException.class);
        assertThat(state(first)).isEqualTo("PUBLISHED"); assertThat(state(next)).isEqualTo("VALIDATED");
        assertThat(current(first).metadata()).isEqualTo(first);
        assertThat(authoring.inspect(original, venue, first.reference().document().documentId()).revision()).isEqualTo(3);
    }
    @Test void withdrawalCleanupKeepsIdentityTombstoneAndDoesNotClaimExternalDeletion() {
        var first = publish(original, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0);
        UUID document = first.reference().document().documentId(); var work = authoring.requestReindex(original, first.reference());
        assertReason(() -> authoring.cleanup(original, venue, document), CorpusFailure.Reason.INVALID_STATE);
        var withdrawn = authoring.withdraw(original, venue, document, 2);
        assertThat(withdrawn.state()).isEqualTo(DocumentState.WITHDRAWN);
        assertThat(state(first)).isEqualTo("WITHDRAWN");
        var observation = catalog.observeExact(publicDelegation, first.reference()).orElseThrow();
        assertThat(observation.documentState()).isEqualTo(DocumentState.WITHDRAWN);
        assertThat(observation.retrievalEligible()).isFalse();
        assertThat(catalog.current(publicDelegation, venue, document)).isEmpty();
        assertThat(authoring.completeReindex(original, work)).isFalse();
        assertThat(payload(first)).isEqualTo("Synthetic"); // logical withdrawal leaves local/provider residues possible
        assertThat(authoring.cleanup(original, venue, document)).isEqualTo(new CleanupResult(1, false));
        assertThat(authoring.cleanup(original, venue, document)).isEqualTo(new CleanupResult(0, false));
        assertThat(payload(first)).isNull();
        assertThat(store.version(first.reference().document(), first.reference().versionId()).orElseThrow().metadata().reference()).isEqualTo(first.reference());
        assertReason(() -> authoring.stage(original, venue, input(document, "Resurrection", Visibility.VENUE_PUBLIC), 3), CorpusFailure.Reason.INVALID_STATE);
        assertReason(() -> authoring.publish(original, first.reference()), CorpusFailure.Reason.INVALID_STATE);
        assertThat(authoring.completeReindex(original, work)).isFalse();
        assertThat(authoring.withdraw(original, venue, document, 0)).isEqualTo(withdrawn);
    }
    @Test void catalogEnforcesPublicOperatorLiveDelegationAndExactIdentity() {
        var pub = publish(original, input(UUID.randomUUID(), "Public", Visibility.VENUE_PUBLIC), 0);
        var operator = publish(original, input(UUID.randomUUID(), "Operators", Visibility.VENUE_OPERATOR), 0);
        assertThat(current(pub).content()).isEqualTo("Public");
        assertThat(catalog.revalidateExact(publicDelegation, operator.reference())).isEmpty();
        assertThat(catalog.revalidateExact(operatorDelegation, operator.reference())).isPresent();
        assertThat(catalog.revalidateExact(operatorDelegation, pub.reference())).isPresent();
        UUID staffId = UUID.randomUUID(); String staff = credential(staffId, "STAFF", venue, tenant);
        UUID staffDelegation = delegate(staff, venue, AccessProfile.MANAGEMENT, Set.of(AccessAction.KNOWLEDGE_OPERATOR));
        assertThat(catalog.revalidateExact(staffDelegation, operator.reference())).isPresent();
        db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(staffId));
        assertThatThrownBy(() -> catalog.revalidateExact(staffDelegation, operator.reference())).isInstanceOf(AccessFailure.class);
        var digestSubstitution = new VersionReference(pub.reference().document(), pub.reference().versionId(), "0".repeat(64), Visibility.VENUE_PUBLIC);
        assertThat(catalog.revalidateExact(publicDelegation, digestSubstitution)).isEmpty();
        var visibilitySubstitution = new VersionReference(operator.reference().document(), operator.reference().versionId(), operator.reference().contentDigest(), Visibility.VENUE_PUBLIC);
        assertThat(catalog.revalidateExact(publicDelegation, visibilitySubstitution)).isEmpty();
        access.revokeDelegation(publicDelegation);
        assertThatThrownBy(() -> catalog.revalidateExact(publicDelegation, pub.reference())).isInstanceOf(AccessFailure.class);
    }
    @Test void exactCatalogReadEscapesAnOuterRepeatableReadSnapshot() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Old", Visibility.VENUE_PUBLIC), 0);
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var worker = Executors.newSingleThreadExecutor()) {
            outer.execute(status -> {
                assertThat(state(first)).isEqualTo("PUBLISHED"); // establish old RR snapshot
                VersionMetadata second;
                try { second = worker.submit(() -> publish(original, input(first.reference().document().documentId(), "New", Visibility.VENUE_PUBLIC), 2)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                assertThat(state(first)).isEqualTo("PUBLISHED"); // prove the outer snapshot is stale
                assertThat(catalog.revalidateExact(publicDelegation, first.reference())).isEmpty();
                assertThat(catalog.observeExact(publicDelegation, first.reference()).orElseThrow().metadata().state()).isEqualTo(VersionState.SUPERSEDED);
                assertThat(catalog.revalidateExact(publicDelegation, second.reference())).isPresent();
                try { worker.submit(() -> authoring.withdraw(original, venue, first.reference().document().documentId(), 4)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                assertThat(catalog.revalidateExact(publicDelegation, second.reference())).isEmpty();
                return null;
            });
        }
    }
    @Test void concurrentUpdatesArbitrateOneRevisionAndCannotRollbackTheWinner() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Old", Visibility.VENUE_PUBLIC), 0);
        UUID document = first.reference().document().documentId();
        var outcomes = race(first.reference().document(),
                () -> authoring.stage(original, venue, input(document, "Update A", Visibility.VENUE_PUBLIC), 2),
                () -> authoring.stage(original, venue, input(document, "Update B", Visibility.VENUE_PUBLIC), 2));
        assertThat(outcomes.stream().filter(o -> o.failure == null)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.failure != null).findFirst().orElseThrow().failure).isEqualTo(CorpusFailure.Reason.STALE_REVISION);
        VersionMetadata winner = (VersionMetadata) outcomes.stream().filter(o -> o.failure == null).findFirst().orElseThrow().value;
        authoring.validate(original, winner.reference()); authoring.publish(original, winner.reference());
        assertThat(current(winner).metadata().reference()).isEqualTo(winner.reference());
        assertReason(() -> authoring.publish(original, first.reference()), CorpusFailure.Reason.INVALID_STATE);
        assertThat(state(first)).isEqualTo("SUPERSEDED");
    }
    @Test void oldIngestCompletionCannotPublishOverANewerStagedOrPublishedVersion() throws Exception {
        UUID document = UUID.randomUUID();
        var old = authoring.stage(original, venue, input(document, "Old ingest", Visibility.VENUE_PUBLIC), 0);
        authoring.validate(original, old.reference());
        var next = authoring.stage(original, venue, input(document, "New ingest", Visibility.VENUE_PUBLIC), 1);
        assertReason(() -> authoring.publish(original, old.reference()), CorpusFailure.Reason.STALE_REVISION);
        authoring.validate(original, next.reference());
        var outcomes = race(old.reference().document(), () -> authoring.publish(original, old.reference()),
                () -> authoring.publish(original, next.reference()));
        assertThat(outcomes.getFirst().failure).isEqualTo(CorpusFailure.Reason.STALE_REVISION);
        assertThat(outcomes.get(1).failure).isNull();
        var published = (VersionMetadata)outcomes.get(1).value;
        assertReason(() -> authoring.publish(original, old.reference()), CorpusFailure.Reason.STALE_REVISION);
        assertThat(current(published).metadata()).isEqualTo(published);
    }
    @Test void updateVersusWithdrawalHasOneWinnerAndWithdrawalFencesLatePublication() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Old", Visibility.VENUE_PUBLIC), 0);
        UUID document = first.reference().document().documentId();
        var outcomes = race(first.reference().document(),
                () -> authoring.stage(original, venue, input(document, "Update", Visibility.VENUE_PUBLIC), 2),
                () -> authoring.withdraw(original, venue, document, 2));
        assertThat(outcomes.stream().filter(o -> o.failure == null)).hasSize(1);
        var metadata = authoring.inspect(original, venue, document);
        authoring.withdraw(original, venue, document, metadata.revision());
        for (Outcome outcome : outcomes) if (outcome.value instanceof VersionMetadata staged) {
            assertReason(() -> authoring.publish(original, staged.reference()), CorpusFailure.Reason.INVALID_STATE);
        }
        assertThat(catalog.current(publicDelegation, venue, document)).isEmpty();
        assertReason(() -> authoring.publish(original, first.reference()), CorpusFailure.Reason.INVALID_STATE);
    }
    @Test void reindexRacesNewPublicationAndDelayedCompletionNeverRollsBackCurrent() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Old", Visibility.VENUE_PUBLIC), 0);
        var ticket = authoring.requestReindex(original, first.reference());
        var next = authoring.stage(original, venue, input(first.reference().document().documentId(), "New", Visibility.VENUE_PUBLIC), 2);
        authoring.validate(original, next.reference());
        // A staged update already invalidates old derived tickets without removing the old publication.
        assertThat(authoring.completeReindex(original, ticket)).isFalse();
        var racingTicket = authoring.requestReindex(original, first.reference());
        var outcomes = race(first.reference().document(), () -> authoring.completeReindex(original, racingTicket),
                () -> authoring.publish(original, next.reference()));
        assertThat(outcomes).allMatch(o -> o.failure == null);
        assertThat(outcomes.getFirst().value).isInstanceOf(Boolean.class); // true only if observed before publication
        assertThat(authoring.completeReindex(original, racingTicket)).isFalse();
        assertThat(authoring.completeReindex(original, ticket)).isFalse();
        assertThat(current(next).metadata().reference()).isEqualTo(next.reference());
        assertReason(() -> authoring.requestReindex(original, first.reference()), CorpusFailure.Reason.INVALID_STATE);
        var currentTicket = authoring.requestReindex(original, next.reference());
        assertThat(authoring.completeReindex(original, currentTicket)).isTrue();
    }
    @Test void withdrawalRacesDerivedCompletionAndResidueCannotReactivateIt() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0);
        var ticket = authoring.requestReindex(original, first.reference());
        var outcomes = race(first.reference().document(), () -> authoring.completeReindex(original, ticket),
                () -> authoring.withdraw(original, venue, first.reference().document().documentId(), 2));
        assertThat(outcomes).allMatch(o -> o.failure == null);
        assertThat(authoring.completeReindex(original, ticket)).isFalse();
        assertThat(payload(first)).isNotNull(); // stale derived content can exist, but exact authority rejects it
        assertThat(catalog.revalidateExact(publicDelegation, first.reference())).isEmpty();
        assertThat(authoring.inspect(original, venue, first.reference().document().documentId()).state()).isEqualTo(DocumentState.WITHDRAWN);
    }
    @Test void permissionChangeDuringDocumentLockWaitIsCheckedAfterAcquisition() throws Exception {
        UUID managerId = UUID.randomUUID(); String manager = credential(managerId, "MANAGER", venue, tenant);
        var first = publish(manager, input(UUID.randomUUID(), "Synthetic", Visibility.VENUE_PUBLIC), 0);
        try (var locker = lock(first.reference().document()); var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> authoring.withdraw(manager, venue, first.reference().document().documentId(), 2));
            awaitWaiters(1);
            db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(managerId));
            locker.commit();
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessFailure.class);
        }
        assertThat(current(first).metadata()).isEqualTo(first);
    }
    @Test void publishVersusWithdrawalKeepsATerminalTombstoneUnderRealLockCompetition() throws Exception {
        var first = publish(original, input(UUID.randomUUID(), "Old", Visibility.VENUE_PUBLIC), 0);
        UUID document = first.reference().document().documentId();
        var next = authoring.stage(original, venue, input(document, "New", Visibility.VENUE_PUBLIC), 2);
        authoring.validate(original, next.reference());
        var outcomes = race(first.reference().document(), () -> authoring.publish(original, next.reference()),
                () -> authoring.withdraw(original, venue, document, 3));
        assertThat(outcomes.stream().filter(o -> o.failure == null)).hasSize(1);
        DocumentMetadata latest = authoring.inspect(original, venue, document);
        authoring.withdraw(original, venue, document, latest.revision());
        assertThat(catalog.revalidateExact(publicDelegation, next.reference())).isEmpty();
        assertReason(() -> authoring.publish(original, next.reference()), CorpusFailure.Reason.INVALID_STATE);
        assertThat(authoring.inspect(original, venue, document).currentVersionId()).isNull();
    }
    @Test void scopedForeignKeysRejectCrossVenueDocumentAndVersionPointers() {
        var first = publish(original, input(UUID.randomUUID(), "One", Visibility.VENUE_PUBLIC), 0);
        var second = publish(original, input(UUID.randomUUID(), "Two", Visibility.VENUE_PUBLIC), 0);
        assertThatThrownBy(() -> db.update("UPDATE knowledge_documents SET current_version_id=? "
                + "WHERE tenant_id=? AND venue_id=? AND document_id=?", bytes(second.reference().versionId()),
                bytes(tenant.value()), bytes(venue.value()), bytes(first.reference().document().documentId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO knowledge_documents(tenant_id,venue_id,document_id,state,revision) "
                + "VALUES(?,?,?,'UNPUBLISHED',0)", bytes(UUID.randomUUID()), bytes(venue.value()), bytes(UUID.randomUUID())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(current(first).metadata()).isEqualTo(first);
    }
    @Test void reindexRejectsExactIdentitySubstitutionAndHasNoPublicationEffect() {
        var first = publish(original, input(UUID.randomUUID(), "One", Visibility.VENUE_PUBLIC), 0);
        var ticket = authoring.requestReindex(original, first.reference());
        var substituted = new VersionReference(first.reference().document(), first.reference().versionId(), "0".repeat(64), Visibility.VENUE_PUBLIC);
        assertReason(() -> authoring.requestReindex(original, substituted), CorpusFailure.Reason.INVALID_STATE);
        assertThat(authoring.completeReindex(original, new DerivedWork(substituted, ticket.documentRevision()))).isFalse();
        assertThat(authoring.completeReindex(original, new DerivedWork(first.reference(), ticket.documentRevision() - 1))).isFalse();
        assertThat(authoring.completeReindex(original, ticket)).isTrue();
        assertThat(authoring.inspect(original, venue, first.reference().document().documentId()).revision()).isEqualTo(2);
        assertThat(current(first).metadata()).isEqualTo(first);
    }
    @Test void manifestRejectsUnknownClaimsMalformedIdentityAndOversizedInputWithoutLeakingContent() throws Exception {
        String seed = new String(getClass().getResourceAsStream("/knowledge/seed-manifest.json").readAllBytes(), StandardCharsets.UTF_8);
        for (String text : List.of(seed.replace("\"documents\":", "\"role\":\"OWNER\",\"documents\":"),
                seed.replace("10000000-0000-0000-0000-000000000001", "1-0-0-0-1"), "raw-prompt-SENTINEL", "x".repeat(131073))) {
            assertThatThrownBy(() -> CorpusIngestion.readManifest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid bounded corpus manifest").hasNoCause();
        }
    }

    private VersionMetadata publish(String token, VersionInput input, long revision) {
        var staged = authoring.stage(token, venue, input, revision);
        authoring.validate(token, staged.reference()); return authoring.publish(token, staged.reference());
    }
    private VersionInput input(UUID document, String content, Visibility visibility) {
        return new VersionInput(document, UUID.randomUUID(), new Source(UUID.randomUUID(), "seed:synthetic", "Synthetic"), visibility, content, Corpus.digest(content));
    }
    private PublishedVersion current(VersionMetadata version) { return catalog.revalidateExact(publicDelegation, version.reference()).orElseThrow(); }
    private String state(VersionMetadata version) {
        return db.queryForObject("SELECT state FROM knowledge_versions WHERE tenant_id=? AND venue_id=? AND document_id=? AND version_id=?",
                String.class, bytes(version.reference().document().tenantId().value()), bytes(version.reference().document().venueId().value()),
                bytes(version.reference().document().documentId()), bytes(version.reference().versionId()));
    }
    private String payload(VersionMetadata version) { return store.version(version.reference().document(), version.reference().versionId()).orElseThrow().content(); }
    private void seedScope(TenantId tenant, VenueId venue) {
        db.update("INSERT INTO tenants(id,status) VALUES(?,'ACTIVE')", bytes(tenant.value())); seedVenue(tenant, venue);
    }
    private void seedVenue(TenantId tenant, VenueId venue) {
        db.update("INSERT INTO venues(id,tenant_id,status,timezone,name) VALUES(?,?,'ACTIVE','UTC','Synthetic')", bytes(venue.value()), bytes(tenant.value()));
    }
    private String credential(UUID principal, String role, VenueId venue, TenantId tenant) {
        db.update("INSERT INTO auth_principals(id) VALUES(?)", bytes(principal));
        if (!role.equals("CUSTOMER")) {
            String persisted = role.equals("UNASSIGNED_MANAGER") ? "MANAGER" : role;
            db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,?)", bytes(tenant.value()), bytes(principal), persisted);
            if (role.equals("MANAGER") || role.equals("STAFF")) {
                db.update("INSERT INTO venue_grants(tenant_id,principal_id,role,venue_id) VALUES(?,?,?,?)",
                        bytes(tenant.value()), bytes(principal), persisted, bytes(venue.value()));
            }
        }
        return access.provisionOriginal(new PrincipalId(principal), Instant.now().plusSeconds(3600)).value();
    }
    private UUID delegate(String token, VenueId venue, AccessProfile profile, Set<AccessAction> actions) {
        return access.approveDelegation(token, venue, profile, actions, Set.of("knowledge.search"), Duration.ofMinutes(10)).delegationId();
    }
    private String recoveryToken() throws Exception {
        String token = "sqop_" + Corpus.digest(UUID.randomUUID().toString()); UUID operator = UUID.randomUUID();
        db.update("INSERT INTO operations_operators(operator_id,principal_reference,active) VALUES(?, ?,TRUE)", bytes(operator), "synthetic-operator");
        db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES(?,?,?,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)",
                bytes(UUID.randomUUID()), bytes(operator), java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(operations.authenticate(token)).isPresent(); return token;
    }
    private Connection lock(DocumentKey key) throws Exception {
        Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT revision FROM knowledge_documents WHERE tenant_id=? AND venue_id=? AND document_id=? FOR UPDATE")) {
            statement.setBytes(1, bytes(key.tenantId().value())); statement.setBytes(2, bytes(key.venueId().value())); statement.setBytes(3, bytes(key.documentId()));
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
        return connection;
    }
    private void awaitWaiters(int count) {
        JdbcTemplate observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(observer.queryForObject(
                "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                + "ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='corpus_lifecycle' AND l.OBJECT_NAME='knowledge_documents'", Integer.class))
                .isGreaterThanOrEqualTo(count));
    }
    private List<Outcome> race(DocumentKey key, Callable<?> left, Callable<?> right) throws Exception {
        try (Connection locker = lock(key); var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> outcome(left)); var b = workers.submit(() -> outcome(right));
            awaitWaiters(2); assertThat(a.isDone()).isFalse(); assertThat(b.isDone()).isFalse();
            locker.commit();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
    private Outcome outcome(Callable<?> work) throws Exception {
        try { return new Outcome(work.call(), null); } catch (CorpusFailure failure) { return new Outcome(null, failure.reason()); }
    }
    private record Outcome(Object value, CorpusFailure.Reason failure) { }
    private static void assertReason(Runnable task, CorpusFailure.Reason reason) {
        assertThatThrownBy(task::run).isInstanceOf(CorpusFailure.class).extracting(e -> ((CorpusFailure)e).reason()).isEqualTo(reason);
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
