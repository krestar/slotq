package com.slotq.integration.operations.recovery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.slotq.events.application.*;
import com.slotq.events.persistence.JdbcKafkaIntakeStore;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

import static com.slotq.integration.operations.recovery.OperatorCredentials.bytes;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL and the human credential/security chain, without mock authentication. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties={"slotq.operations.recovery.enabled=true","slotq.observability.scrape-token=monitor-credential-with-thirty-two-bytes"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(HumanRecoveryIntegrationTests.ProductCredentials.class)
class HumanRecoveryIntegrationTests {
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("operator_recovery")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
        new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.1.1");
    static final String BUSINESS="waitlist.promotion", OBSERVER="operations.event-observation", DEST="slotq.waitlist.events.v1";
    static final List<String> AFFECTED=List.of(OBSERVER,BUSINESS);
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;
    @Autowired EventDeliveryStore store;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired DeliveryTransactions transactions;
    @Autowired DeliveryPolicy policy;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired KafkaPublicationLedger publications;
    @Autowired WaitlistKafkaMessage wire;
    @Autowired JdbcKafkaIntakeStore intake;
    @Autowired OperatorCredentials credentials;
    @Autowired HumanRecoveryService recovery;
    @Autowired RecoveryReadService reads;
    @Autowired MockMvc http;
    final JsonMapper json=JsonMapper.builder().build();
    UUID businessRegistration,observerRegistration,tenant;
    Actor actor;
    long offset;

    @BeforeAll void registerOriginalRoutes() {
        businessRegistration=registrations.activate(new ConsumerRoute(BUSINESS,"waitlist.promotion-requested",1));
        observerRegistration=registrations.activate(new ConsumerRoute(OBSERVER,"waitlist.promotion-requested",1));
        db.update("UPDATE event_kafka_discovery SET destination=? WHERE singleton_id=1",DEST);
    }
    @BeforeEach void identity() {
        db.update("UPDATE event_transport_assignments SET transport='DB_DIRECT',authority_epoch=1");
        tenant=UUID.randomUUID();
        db.update("INSERT INTO tenants(id,status) VALUES (?,'ACTIVE')",bytes(tenant));
        actor=actor();
        for(String consumer:AFFECTED) for(String action:List.of("READ","BUSINESS_REPLAY","PUBLICATION_RECOVER")) grant(actor,consumer,action);
    }

    @ParameterizedTest @ValueSource(strings={"missing","invalid","expired","revoked","inactive","product","monitor"})
    void credentialNegativeBoundary(String kind) throws Exception {
        var request=get(listUrl()).secure(true); String token=actor.token();
        switch(kind) {
            case "missing" -> token=null;
            case "invalid" -> token="sqop_"+"0".repeat(64);
            case "expired" -> db.update("UPDATE operations_credentials SET issued_at=TIMESTAMPADD(DAY,-2,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)) WHERE credential_id=?",bytes(actor.principal().credentialId()));
            case "revoked" -> db.update("UPDATE operations_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE credential_id=?",bytes(actor.principal().credentialId()));
            case "inactive" -> db.update("UPDATE operations_operators SET active=FALSE WHERE operator_id=?",bytes(actor.principal().operatorId()));
            case "product" -> token="product-valid-customer-owner-manager-staff";
            case "monitor" -> token="monitor-credential-with-thirty-two-bytes";
        }
        if(token!=null) request.header("Authorization","Bearer "+token);
        http.perform(request).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_REQUIRED"));
    }

    @Test void privateTlsAndCorsBoundaryAndOperatorCannotUseProductApi() throws Exception {
        http.perform(get("/api/v1/reservations/"+UUID.randomUUID()).header("Authorization","Bearer product-valid-customer-owner-manager-staff"))
            .andExpect(result->assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        http.perform(get("/actuator/prometheus").header("Authorization","Bearer monitor-credential-with-thirty-two-bytes"))
            .andExpect(status().isOk());
        http.perform(get("/actuator/prometheus").header("Authorization","Bearer "+actor.token())).andExpect(status().isUnauthorized());
        http.perform(get(listUrl()).header("Authorization","Bearer "+actor.token())).andExpect(status().isForbidden());
        http.perform(get(listUrl()).secure(true).header("Authorization","Bearer "+actor.token()).header("Origin","https://product.example"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        http.perform(get("/api/v1/reservations/"+UUID.randomUUID()).header("Authorization","Bearer "+actor.token())).andExpect(status().isUnauthorized());
    }

    @Test void currentCredentialAndGrantAreRevalidatedInsideTransaction() {
        StoredEvent e=event(false); RecoveryCommand c=business(e);
        db.update("UPDATE operations_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE credential_id=?",bytes(actor.principal().credentialId()));
        problem(()->recovery.recover(actor.principal(),c,"correlation"),401);
        db.update("UPDATE operations_credentials SET revoked_at=NULL WHERE credential_id=?",bytes(actor.principal().credentialId()));
        db.update("UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6) WHERE operator_id=? AND action='BUSINESS_REPLAY'",bytes(actor.principal().operatorId()));
        problem(()->recovery.recover(actor.principal(),c,"correlation"),404); unchanged(e,"event_deliveries",5);
    }

    @Test void wrongScopeMissingTargetsAndRevokedReadGrantAreIndistinguishableAndListsBounded() throws Exception {
        StoredEvent e=event(false);
        db.update("UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6) WHERE operator_id=? AND consumer_id=?",bytes(actor.principal().operatorId()),OBSERVER);
        for(String url:List.of(exactUrl(e,businessRegistration,BUSINESS,UUID.randomUUID()),exactUrl(e,observerRegistration,OBSERVER,tenant),exactUrl(e,UUID.randomUUID(),BUSINESS,tenant)))
            http.perform(get(url).secure(true).header("Authorization","Bearer "+actor.token()))
                .andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"TARGET_NOT_FOUND\"}"));
        String body=http.perform(get(exactUrl(e,businessRegistration,BUSINESS,tenant)).secure(true).header("Authorization","Bearer "+actor.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DEAD")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("payload","token_hash",actor.token(),"principalReference");
        http.perform(get(listUrl()).secure(true).header("Authorization","Bearer "+actor.token()).param("limit","101")).andExpect(status().isBadRequest());
        http.perform(get(listUrl()).secure(true).header("Authorization","Bearer "+actor.token()).param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void existingCrossTenantEventAndMixedRegistrationNeverRecover() {
        StoredEvent e=event(false); UUID other=UUID.randomUUID();
        db.update("INSERT INTO tenants(id,status) VALUES (?,'ACTIVE')",bytes(other));
        db.update("INSERT INTO operations_grants(operator_id,tenant_id,consumer_id,action) VALUES (?,?,?,'BUSINESS_REPLAY')",bytes(actor.principal().operatorId()),bytes(other),BUSINESS);
        RecoveryCommand c=business(e);
        problem(()->recovery.recover(actor.principal(),new RecoveryCommand(c.operationId(),other,c.eventId(),c.registrationId(),BUSINESS,c.action(),null,null,c.reason(),"DEAD",5,"DB_DIRECT",1,null),"correlation"),404);
        problem(()->recovery.recover(actor.principal(),new RecoveryCommand(c.operationId(),tenant,c.eventId(),observerRegistration,BUSINESS,c.action(),null,null,c.reason(),"DEAD",5,"DB_DIRECT",1,null),"correlation"),404);
        unchanged(e,"event_deliveries",5);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void protectedReplayPreservesOriginalIntakeAndInternalHistory(boolean kafka) {
        StoredEvent e=event(kafka); RecoveryCommand c=business(e);
        String original=db.queryForObject("SELECT payload FROM event_records WHERE event_id=?",String.class,bytes(c.eventId()));
        var before=db.queryForList("SELECT * FROM event_kafka_target_intakes WHERE event_id=?",bytes(c.eventId()));
        int trusted=db.queryForObject("SELECT COUNT(*) FROM event_replay_audit",Integer.class);
        RecoveryResult result=recovery.recover(actor.principal(),c,"correlation");
        assertThat(result.postFence()).isEqualTo(6); assertThat(result.priorCycleAttempts()).isEqualTo(3); assertThat(result.lifetimeAttempts()).isEqualTo(5);
        assertThat(state(e,"event_deliveries")).containsEntry("state","PENDING").containsEntry("cycle_attempts",0).containsEntry("lifetime_attempts",5L).containsEntry("fencing_token",6L);
        assertThat(db.queryForObject("SELECT payload FROM event_records WHERE event_id=?",String.class,bytes(c.eventId()))).isEqualTo(original);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_replay_audit",Integer.class)).isEqualTo(trusted);
        assertThat(db.queryForList("SELECT * FROM event_kafka_target_intakes WHERE event_id=?",bytes(c.eventId()))).hasSize(before.size());
        assertThat(reads.exact(actor.principal(),tenant,BUSINESS,c.eventId(),businessRegistration).transport()).isEqualTo(kafka?"KAFKA":"DB_DIRECT");
        assertAtomic(c,result);
    }

    @Test void responseLossLaterDeadAndChangedRequestCannotOpenAnotherCycle() {
        StoredEvent e=event(false); RecoveryCommand c=business(e);
        RecoveryResult first=recovery.recover(actor.principal(),c,"first-response-lost");
        assertThat(recovery.recover(actor.principal(),c,"retry")).isEqualTo(first);
        dead(e,businessRegistration,8,8);
        assertThat(recovery.recover(actor.principal(),c,"after-dead")).isEqualTo(first); unchanged(e,"event_deliveries",8); assertAtomic(c,first);
        problem(()->recovery.recover(actor.principal(),new RecoveryCommand(c.operationId(),tenant,c.eventId(),c.registrationId(),BUSINESS,c.action(),null,null,"different reason","DEAD",5,"DB_DIRECT",1,null),"retry"),409);
    }

    @Test void rotationKeepsStablePrincipalAndHistoricalResultButRevokedTokenStops() {
        StoredEvent e=event(false); RecoveryCommand c=business(e); RecoveryResult first=recovery.recover(actor.principal(),c,"first");
        Actor rotated=credential(actor.principal().operatorId());
        db.update("UPDATE operations_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE credential_id=?",bytes(actor.principal().credentialId()));
        assertThat(credentials.authenticate(actor.token())).isEmpty(); assertThat(recovery.recover(rotated.principal(),c,"rotated")).isEqualTo(first);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","PROCESSING","DONE"})
    void normalTargetsAndStaleFenceCannotReplay(String state) {
        StoredEvent e=event(false);
        if(state.equals("PROCESSING")) db.update("UPDATE event_deliveries SET state='PROCESSING',lease_until=TIMESTAMPADD(SECOND,20,UTC_TIMESTAMP(6)),next_attempt_at=NULL WHERE event_id=? AND registration_id=?",bytes(e.envelope().eventId().value()),bytes(businessRegistration));
        else db.update("UPDATE event_deliveries SET state=?,next_attempt_at=IF(?='PENDING',UTC_TIMESTAMP(6),NULL) WHERE event_id=? AND registration_id=?",state,state,bytes(e.envelope().eventId().value()),bytes(businessRegistration));
        problem(()->recovery.recover(actor.principal(),business(e),"correlation"),409); dead(e,businessRegistration,6,5);
        problem(()->recovery.recover(actor.principal(),business(e),"correlation"),409);
    }

    @Test void staleTransportEpochCannotReplayAndHistoricalRetrySurvivesCutover() {
        StoredEvent e=event(true); RecoveryCommand c=business(e);
        db.update("UPDATE event_transport_assignments SET authority_epoch=3 WHERE registration_id=?",bytes(businessRegistration));
        problem(()->recovery.recover(actor.principal(),c,"stale"),404);
        db.update("UPDATE event_transport_assignments SET authority_epoch=2 WHERE registration_id=?",bytes(businessRegistration));
        RecoveryResult result=recovery.recover(actor.principal(),c,"first");
        db.update("UPDATE event_transport_assignments SET transport='DB_DIRECT',authority_epoch=3 WHERE registration_id=?",bytes(businessRegistration));
        assertThat(recovery.recover(actor.principal(),c,"historical")).isEqualTo(result);
    }

    @ParameterizedTest @ValueSource(strings={"business","publication"})
    void concurrentOperatorsAndDuplicateOperationSerialize(String action) throws Exception {
        StoredEvent e=event(false); publicationDead(e); RecoveryCommand c=action.equals("business")?business(e):publication(e);
        Actor second=actor(); for(String consumer:AFFECTED) grant(second,consumer,c.action());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{start.await();return recoverOrCode(actor.principal(),c);});
            var right=pool.submit(()->{start.await();return recoverOrCode(second.principal(),withOperation(c,UUID.randomUUID()));});start.countDown();
            assertThat(List.of(left.get(15,TimeUnit.SECONDS),right.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("PENDING","409");
        }
        StoredEvent duplicate=event(false); publicationDead(duplicate); RecoveryCommand same=action.equals("business")?business(duplicate):publication(duplicate);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{gate.await();return recovery.recover(actor.principal(),same,"left");});
            var right=pool.submit(()->{gate.await();return recovery.recover(actor.principal(),same,"right");});gate.countDown();
            assertThat(left.get(15,TimeUnit.SECONDS)).isEqualTo(right.get(15,TimeUnit.SECONDS));
        }
        assertThat(state(duplicate,action.equals("business")?"event_deliveries":"event_kafka_publications").get("fencing_token")).isEqualTo(6L);
    }

    @ParameterizedTest @ValueSource(strings={"business-audit","business-operation","business-operation-finalize",
        "publication-audit","publication-operation","publication-operation-finalize"})
    void auditOrOperationInsertFailureRollsBackStateAndReservation(String fault) {
        StoredEvent e=event(false); publicationDead(e); RecoveryCommand c=fault.startsWith("business")?business(e):publication(e);
        String table=fault.endsWith("audit")?"operations_recovery_audit":"operations_recovery_operations";
        db.execute("CREATE TRIGGER recovery_test_failure BEFORE "+(fault.endsWith("finalize")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test operation or audit failure'");
        try { problem(()->recovery.recover(actor.principal(),c,"correlation"),500); } finally { db.execute("DROP TRIGGER recovery_test_failure"); }
        unchanged(e,fault.startsWith("business")?"event_deliveries":"event_kafka_publications",5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_operations WHERE operation_id=?",Integer.class,bytes(c.operationId()))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM operations_recovery_audit WHERE operation_id=?",Integer.class,bytes(c.operationId()))).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"business-committed","business-rollback","publication-committed","publication-rollback"})
    void commitOutcomeUnknownReconcilesFromOperationAndAudit(String mode) {
        StoredEvent e=event(false); publicationDead(e); RecoveryCommand c=mode.startsWith("business")?business(e):publication(e);
        var fault=new CommitFaultManager(mode.endsWith("committed"));
        var service=new HumanRecoveryService(db,credentials,new DeliveryTransactions(fault,store,policy),store,catalog);fault.armed.set(true);
        if(mode.endsWith("committed")) { RecoveryResult result=service.recover(actor.principal(),c,"unknown");assertThat(recovery.recover(actor.principal(),c,"retry")).isEqualTo(result);assertAtomic(c,result); }
        else { problem(()->service.recover(actor.principal(),c,"unknown"),503);unchanged(e,mode.startsWith("business")?"event_deliveries":"event_kafka_publications",5);assertAtomic(c,recovery.recover(actor.principal(),c,"same-operation")); }
    }

    @Test void businessGrantDoesNotAuthorizePublicationAndPublicationNeedsWholeBlastRadius() {
        StoredEvent e=event(false); publicationDead(e);
        db.update("UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6) WHERE operator_id=? AND action='PUBLICATION_RECOVER'",bytes(actor.principal().operatorId()));
        problem(()->recovery.recover(actor.principal(),publication(e),"denied"),404);
        assertThat(recovery.recover(actor.principal(),business(e),"business-only").postState()).isEqualTo("PENDING");unchanged(e,"event_kafka_publications",5);
        db.update("UPDATE operations_grants SET revoked_at=NULL WHERE operator_id=? AND consumer_id=? AND action='PUBLICATION_RECOVER'",bytes(actor.principal().operatorId()),BUSINESS);
        problem(()->recovery.recover(actor.principal(),publication(e),"missing-observer"),404);
    }

    @Test void publicationResponseLossLaterDeadRelayAndDuplicateIntakePreserveTargetsBudgets() {
        StoredEvent e=event(true); publicationDead(e); RecoveryCommand c=publication(e);
        var before=db.queryForList("SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM event_deliveries WHERE event_id=? ORDER BY registration_id",bytes(c.eventId()));
        RecoveryResult first=recovery.recover(actor.principal(),c,"response-lost");assertThat(recovery.recover(actor.principal(),c,"retry")).isEqualTo(first);
        var claim=publications.claim(new KafkaPublicationLedger.Key(tenant,c.eventId(),DEST),new KafkaPublicationPolicy(3,Duration.ofSeconds(30),Duration.ofSeconds(10),100,List.of(Duration.ZERO,Duration.ZERO))).orElseThrow();
        assertThat(claim.token()).isEqualTo(7);assertThat(publications.load(claim).event().envelope().eventId().value()).isEqualTo(c.eventId());
        publications.published(claim,0,777);publicationDead(e);
        assertThat(recovery.recover(actor.principal(),c,"after-later-dead")).isEqualTo(first);
        var encoded=wire.encode(e,origin(e));long duplicateOffset=offset++;
        for(String consumer:AFFECTED) intake.intake(new ConsumerRecord<byte[],byte[]>(DEST,0,duplicateOffset,encoded.key().getBytes(StandardCharsets.UTF_8),encoded.body().getBytes(StandardCharsets.UTF_8)),catalog.definition(consumer),2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?",Integer.class,bytes(c.eventId()))).isEqualTo(2);
        assertThat(db.queryForList("SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM event_deliveries WHERE event_id=? ORDER BY registration_id",bytes(c.eventId()))).isEqualTo(before);assertAtomic(c,first);
    }

    @Test void publishedRecoveryRequiresRetentionCauseAndExpectedFence() {
        StoredEvent e=event(false); publicationDead(e);
        db.update("UPDATE event_kafka_publications SET state='PUBLISHED',ack_partition=0,ack_offset=1,ack_at=UTC_TIMESTAMP(6) WHERE event_id=?",bytes(e.envelope().eventId().value()));
        problem(()->recovery.recover(actor.principal(),publication(e),"wrong-state"),409);RecoveryCommand c=publication(e);
        RecoveryCommand retention=new RecoveryCommand(c.operationId(),tenant,c.eventId(),null,OBSERVER,c.action(),DEST,AFFECTED,"verified retention incident","PUBLISHED",5,null,0,"RETENTION_GAP");
        assertThat(recovery.recover(actor.principal(),retention,"retention").postState()).isEqualTo("PENDING");
    }

    @Test void appendOnlyHumanAuditAndHttpRecoveryReturnDurableOutcome() throws Exception {
        StoredEvent e=event(false);RecoveryCommand c=business(e);
        String request=json.writeValueAsString(new OperationsRecoveryController.BusinessRequest(c.operationId(),c.reason(),"DEAD",5L,"DB_DIRECT",1L));
        http.perform(post(exactUrl(e,businessRegistration,BUSINESS,tenant)+"/replay").secure(true).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.postFence").value(6)).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get(base()+"/consumers/"+BUSINESS+"/operations/"+c.operationId()).secure(true).header("Authorization","Bearer "+actor.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(c.operationId().toString()));
        http.perform(get(base()+"/consumers/"+BUSINESS+"/audit").secure(true).header("Authorization","Bearer "+actor.token())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThatThrownBy(()->db.update("UPDATE operations_recovery_audit SET action='READ' WHERE operation_id=?",bytes(c.operationId()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM operations_recovery_audit WHERE operation_id=?",bytes(c.operationId()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void preservedReceiptAndObservationRemainScopedAndRecoveryUsesOnePhysicalMysqlTransaction(boolean publication) {
        StoredEvent e=event(false);UUID event=e.envelope().eventId().value();
        var payload=json.readTree(e.envelope().payload());
        UUID venue=UUID.fromString(payload.get("venueId").asString()),resource=UUID.fromString(payload.get("resourceId").asString());
        db.update("""
            INSERT INTO waitlist_promotion_receipts(tenant_id,consumer_id,event_id,signal_type,source_id,occurred_at,
                venue_id,resource_id,slot_inventory_id,outcome) VALUES (?,?,?,'PROMOTION_REQUESTED',?,UTC_TIMESTAMP(6),?,?,?,'NO_CANDIDATE')
            """,bytes(tenant),BUSINESS,bytes(event),bytes(e.envelope().aggregateId()),bytes(venue),bytes(resource),bytes(e.envelope().aggregateId()));
        db.update("""
            INSERT INTO event_observation_projections(tenant_id,event_id,registration_id,consumer_id,event_type,schema_version,
                aggregate_type,aggregate_id,occurred_at,venue_id,resource_id,slot_inventory_id,projected_at)
            VALUES (?,?,?,?,'waitlist.promotion-requested',1,'SlotInventory',?,UTC_TIMESTAMP(6),?,?,?,UTC_TIMESTAMP(6))
            """,bytes(tenant),bytes(event),bytes(observerRegistration),OBSERVER,bytes(e.envelope().aggregateId()),bytes(venue),bytes(resource),bytes(e.envelope().aggregateId()));
        String receipt=json.writeValueAsString(db.queryForMap("SELECT * FROM waitlist_promotion_receipts WHERE event_id=?",bytes(event)));
        assertThat(reads.exact(actor.principal(),tenant,BUSINESS,event,businessRegistration).receiptOutcome()).isEqualTo("NO_CANDIDATE");
        assertThat(reads.exact(actor.principal(),tenant,BUSINESS,event,businessRegistration).observationRegistration()).isNull();
        assertThat(reads.exact(actor.principal(),tenant,OBSERVER,event,observerRegistration).observationRegistration()).isEqualTo(observerRegistration);
        if (publication) publicationDead(e);
        db.execute("CREATE TABLE recovery_test_connections(stage VARCHAR(16),connection_id BIGINT)");
        db.execute("CREATE TRIGGER recovery_connection_target BEFORE UPDATE ON "+(publication?"event_kafka_publications":"event_deliveries")+" FOR EACH ROW INSERT INTO recovery_test_connections VALUES ('target',CONNECTION_ID())");
        db.execute("CREATE TRIGGER recovery_connection_operation BEFORE UPDATE ON operations_recovery_operations FOR EACH ROW INSERT INTO recovery_test_connections VALUES ('operation',CONNECTION_ID())");
        db.execute("CREATE TRIGGER recovery_connection_audit BEFORE INSERT ON operations_recovery_audit FOR EACH ROW INSERT INTO recovery_test_connections VALUES ('audit',CONNECTION_ID())");
        try {
            recovery.recover(actor.principal(),publication?publication(e):business(e),"physical-transaction");
            assertThat(db.queryForList("SELECT stage FROM recovery_test_connections",String.class)).containsExactlyInAnyOrder("target","operation","audit");
            assertThat(db.queryForList("SELECT DISTINCT connection_id FROM recovery_test_connections",Long.class)).hasSize(1);
            assertThat(json.writeValueAsString(db.queryForMap("SELECT * FROM waitlist_promotion_receipts WHERE event_id=?",bytes(event)))).isEqualTo(receipt);
        } finally {
            for(String name:List.of("recovery_connection_target","recovery_connection_operation","recovery_connection_audit")) db.execute("DROP TRIGGER "+name);
            db.execute("DROP TABLE recovery_test_connections");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void admittedReplayExecutesOnceAndHistoricalRetryAfterDoneDoesNotRepeatEffect(boolean kafka) {
        StoredEvent e=event(kafka);RecoveryCommand c=business(e);
        db.execute("CREATE TABLE IF NOT EXISTS human_recovery_test_effect(event_id BINARY(16) PRIMARY KEY,applications INT)");
        RecoveryResult admitted=recovery.recover(actor.principal(),c,"admitted");
        var handler=new EventHandler() {
            public ConsumerRoute route(){return new ConsumerRoute(BUSINESS,"waitlist.promotion-requested",1);}
            public void handle(StoredEvent event){db.update("INSERT INTO human_recovery_test_effect VALUES (?,1)",bytes(event.envelope().eventId().value()));}
        };
        var scope=new DeliveryExecutionScope(BUSINESS,kafka?"KAFKA":"DB_DIRECT",kafka?2:1);
        var worker=new EventDeliveryWorker(store,transactions,policy,new EventHandlers(List.of(handler)),canonicalizer,emf,scope);
        var key=new DeliveryKey(new TenantId(tenant),e.envelope().eventId(),businessRegistration);
        var claim=worker.claim(key).orElseThrow();worker.process(claim);worker.process(claim);
        assertThat(worker.claim(key)).isEmpty();assertThat(recovery.recover(actor.principal(),c,"historical-after-done")).isEqualTo(admitted);
        assertThat(db.queryForObject("SELECT applications FROM human_recovery_test_effect WHERE event_id=?",Integer.class,bytes(c.eventId()))).isEqualTo(1);
        assertThat(state(e,"event_deliveries")).containsEntry("state","DONE").containsEntry("cycle_attempts",1).containsEntry("lifetime_attempts",6L).containsEntry("fencing_token",7L);
    }

    @ParameterizedTest @ValueSource(strings={"target","action","destination","consumers","reason","fence"})
    void reusedPublicationOperationRejectsEveryChangedSemanticRequest(String change) {
        StoredEvent e=event(false);publicationDead(e);RecoveryCommand c=publication(e);
        recovery.recover(actor.principal(),c,"first");
        RecoveryCommand changed=new RecoveryCommand(c.operationId(),tenant,
            change.equals("target")?UUID.randomUUID():c.eventId(),change.equals("action")?businessRegistration:null,
            change.equals("action")?BUSINESS:OBSERVER,change.equals("action")?"BUSINESS_REPLAY":"PUBLICATION_RECOVER",
            change.equals("action")?null:change.equals("destination")?"arbitrary.topic":DEST,
            change.equals("action")?null:change.equals("consumers")?List.of(OBSERVER):AFFECTED,
            change.equals("reason")?"changed":c.reason(),"DEAD",change.equals("fence")?6:5,
            change.equals("action")?"DB_DIRECT":null,change.equals("action")?1:0,change.equals("action")?null:"PUBLICATION_DEAD");
        problem(()->recovery.recover(actor.principal(),changed,"reused"),409);
    }

    @Test void omittedPublicationScopeArbitraryDestinationAndMalformedReasonAreRejected() {
        StoredEvent e=event(false);publicationDead(e);RecoveryCommand c=publication(e);
        problem(()->recovery.recover(actor.principal(),new RecoveryCommand(c.operationId(),tenant,c.eventId(),null,OBSERVER,c.action(),DEST,List.of(OBSERVER),c.reason(),"DEAD",5,null,0,"PUBLICATION_DEAD"),"omitted"),404);
        problem(()->recovery.recover(actor.principal(),new RecoveryCommand(c.operationId(),tenant,c.eventId(),null,OBSERVER,c.action(),"arbitrary.topic",AFFECTED,c.reason(),"DEAD",5,null,0,"PUBLICATION_DEAD"),"topic"),404);
        for(String reason:List.of(" ","x".repeat(501),"control\n")) {
            RecoveryCommand business=business(e);
            problem(()->recovery.recover(actor.principal(),new RecoveryCommand(business.operationId(),tenant,business.eventId(),business.registrationId(),BUSINESS,"BUSINESS_REPLAY",null,null,reason,"DEAD",5,"DB_DIRECT",1,null),"invalid"),400);
        }
        unchanged(e,"event_kafka_publications",5);unchanged(e,"event_deliveries",5);
    }

    private Actor actor() { UUID id=UUID.randomUUID();db.update("INSERT INTO operations_operators(operator_id,principal_reference) VALUES (?,?)",bytes(id),"staff-ref-"+id);return credential(id); }
    @Test void scopedReadsIncludePublicationFailureBeforeIntakeAndPageAllOriginals() {
        StoredEvent first=event(false),second=event(false);publicationDead(first);
        db.update("DELETE FROM event_deliveries WHERE event_id=?",bytes(first.envelope().eventId().value()));
        var view=reads.exact(actor.principal(),tenant,BUSINESS,first.envelope().eventId().value(),businessRegistration);
        assertThat(view.state()).isNull();assertThat(view.cycleAttempts()).isNull();assertThat(view.fencingToken()).isNull();assertThat(view.publicationState()).isEqualTo("DEAD");
        var page=reads.list(actor.principal(),tenant,BUSINESS,null,null,1);var last=page.getLast();
        var next=reads.list(actor.principal(),tenant,BUSINESS,last.eventId(),last.registrationId(),1);
        assertThat(page).hasSize(1);assertThat(next).hasSize(1);
        assertThat(List.of(page.getFirst().eventId(),next.getFirst().eventId())).containsExactlyInAnyOrder(first.envelope().eventId().value(),second.envelope().eventId().value());
        problem(()->recovery.recover(actor.principal(),business(first),"no-delivery"),404);
    }

    @Test void publicationOnlyGrantDoesNotResetOrAuthorizeBusinessDeadTarget() {
        StoredEvent e=event(false);publicationDead(e);Actor publisher=actor();
        for (String consumer:AFFECTED) grant(publisher,consumer,"PUBLICATION_RECOVER");
        problem(()->recovery.recover(publisher.principal(),business(e),"missing-business-grant"),404);
        assertThat(recovery.recover(publisher.principal(),publication(e),"publication-only").postState()).isEqualTo("PENDING");
        unchanged(e,"event_deliveries",5);
    }

    @Test void historicalOperationRetryStillRequiresCurrentGrant() {
        StoredEvent e=event(false);RecoveryCommand c=business(e);recovery.recover(actor.principal(),c,"first");
        db.update("UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6) WHERE operator_id=? AND action='BUSINESS_REPLAY'",bytes(actor.principal().operatorId()));
        problem(()->recovery.recover(actor.principal(),c,"revoked-retry"),404);
        assertThat(state(e,"event_deliveries")).containsEntry("state","PENDING").containsEntry("fencing_token",6L);
    }

    @Test void committedHumanPublicationRecoveryIsPublishedByExistingRelayOutsideRecoveryTransaction() throws Exception {
        StoredEvent e=event(true);publicationDead(e);RecoveryCommand c=publication(e);
        var before=db.queryForList("SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM event_deliveries WHERE event_id=? ORDER BY registration_id",bytes(c.eventId()));
        String request=json.writeValueAsString(new OperationsRecoveryController.PublicationRequest(c.operationId(),DEST,AFFECTED,
            c.reason(),"DEAD",5L,"PUBLICATION_DEAD"));
        http.perform(post(base()+"/publications/"+c.eventId()+"/recover").secure(true).header("Authorization","Bearer "+actor.token())
            .contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.action").value("PUBLICATION_RECOVER"));
        assertThat(state(e,"event_kafka_publications")).containsEntry("state","PENDING").containsEntry("fencing_token",6L);
        var publicationPolicy=new KafkaPublicationPolicy(3,Duration.ofSeconds(30),Duration.ofSeconds(10),100,List.of(Duration.ZERO,Duration.ZERO));
        var template=new KafkaRelayConfiguration().publicationTemplate(new KafkaRelayConfiguration.ClientSettings(KAFKA.getBootstrapServers(),"PLAINTEXT","","","",""));
        try {
            var worker=new KafkaRelayWorker(publications,template,wire,publicationPolicy,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),DEST);
            var claim=publications.claim(new KafkaPublicationLedger.Key(tenant,c.eventId(),DEST),publicationPolicy).orElseThrow();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            worker.publish(claim);
            var ack=db.queryForMap("SELECT state,ack_partition,ack_offset FROM event_kafka_publications WHERE event_id=?",bytes(c.eventId()));
            assertThat(ack.get("state")).isEqualTo("PUBLISHED");
            var config=new java.util.HashMap<String,Object>();
            config.put("bootstrap.servers",KAFKA.getBootstrapServers());config.put("group.id","human-recovery-evidence-"+UUID.randomUUID());
            config.put("enable.auto.commit",false);config.put("auto.offset.reset","none");
            config.put("key.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
            config.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
            try(var reader=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(config)) {
                var partition=new org.apache.kafka.common.TopicPartition(DEST,((Number)ack.get("ack_partition")).intValue());
                reader.assign(List.of(partition));reader.seek(partition,((Number)ack.get("ack_offset")).longValue());
                var records=reader.poll(Duration.ofSeconds(10));assertThat(records.count()).isGreaterThanOrEqualTo(1);
                var record=records.iterator().next();assertThat(json.readTree(record.value()).get("eventId").asString()).isEqualTo(c.eventId().toString());
            }
            assertThat(db.queryForList("SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM event_deliveries WHERE event_id=? ORDER BY registration_id",bytes(c.eventId()))).isEqualTo(before);
        } finally { template.destroy(); }
    }

    @Test void publicationAuditReadRequiresEveryConsumerAndPaginationSkipsHiddenRows() {
        StoredEvent e=event(false);publicationDead(e);
        RecoveryCommand pub=withOperation(publication(e),new UUID(0,1));RecoveryResult first=recovery.recover(actor.principal(),pub,"publication");
        RecoveryCommand business=withOperation(business(e),new UUID(0,2));RecoveryResult second=recovery.recover(actor.principal(),business,"business");
        assertThat(recovery.audit(actor.principal(),tenant,OBSERVER,null,1)).containsExactly(first);
        db.update("UPDATE operations_grants SET revoked_at=UTC_TIMESTAMP(6) WHERE operator_id=? AND consumer_id=? AND action='READ'",bytes(actor.principal().operatorId()),BUSINESS);
        assertThat(recovery.audit(actor.principal(),tenant,OBSERVER,null,1)).isEmpty();problem(()->recovery.operation(actor.principal(),tenant,OBSERVER,pub.operationId()),404);
        db.update("UPDATE operations_grants SET revoked_at=NULL WHERE operator_id=? AND consumer_id=? AND action='READ'",bytes(actor.principal().operatorId()),BUSINESS);
        assertThat(recovery.audit(actor.principal(),tenant,BUSINESS,null,1)).containsExactly(second);
    }

    @Test void callerOperatorIdAndMissingExpectedVersionDoNotSupplyIdentityOrRecover() throws Exception {
        StoredEvent e=event(false);RecoveryCommand c=business(e);String url=exactUrl(e,businessRegistration,BUSINESS,tenant)+"/replay";
        String incomplete=json.writeValueAsString(java.util.Map.of("operationId",c.operationId(),"reason",c.reason(),"expectedState","DEAD","expectedTransport","DB_DIRECT","expectedAuthorityEpoch",1));
        http.perform(post(url).secure(true).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(incomplete)).andExpect(status().isBadRequest());
        String valid=json.writeValueAsString(new OperationsRecoveryController.BusinessRequest(c.operationId(),c.reason(),"DEAD",5L,"DB_DIRECT",1L));
        http.perform(post(url).secure(true).header("Authorization","Bearer "+actor.token()).header("X-Operator-ID",UUID.randomUUID())
            .contentType("application/json").content(valid)).andExpect(status().isOk()).andExpect(jsonPath("$.operatorId").value(actor.principal().operatorId().toString()));
    }

    @Test void credentialExpiryDuringLockWaitIsRejectedUsingPostLockDbTime() throws Exception {
        StoredEvent e=event(false);RecoveryCommand c=business(e);
        try(var blocker=db.getDataSource().getConnection();var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("UPDATE operations_credentials SET expires_at=TIMESTAMPADD(SECOND,1,UTC_TIMESTAMP(6)) WHERE credential_id=?")) {
                statement.setBytes(1,bytes(actor.principal().credentialId()));statement.executeUpdate();
            }
            var waiting=pool.submit(()->recoverOrCode(actor.principal(),c));Thread.sleep(1200);
            assertThat(waiting.isDone()).isFalse();blocker.commit();assertThat(waiting.get(5,TimeUnit.SECONDS)).isEqualTo("401");unchanged(e,"event_deliveries",5);
        }
    }
    private Actor credential(UUID operator) {
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token="sqop_"+java.util.HexFormat.of().formatHex(random);UUID credential=UUID.randomUUID();
        db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES (?,?,?,TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)))",bytes(credential),bytes(operator),OperatorCredentials.hash(token));
        return new Actor(credentials.authenticate(token).orElseThrow(),token);
    }
    private void grant(Actor actor,String consumer,String action) { db.update("INSERT INTO operations_grants(operator_id,tenant_id,consumer_id,action) VALUES (?,?,?,?)",bytes(actor.principal().operatorId()),bytes(tenant),consumer,action); }
    private StoredEvent event(boolean kafka) {
        UUID slot=UUID.randomUUID();StoredEvent e=new TransactionTemplate(manager).execute(status->append.append(new EventEnvelope(EventId.newId(),new TenantId(tenant),"SlotInventory",slot,"waitlist.promotion-requested",1,Instant.now(),json.writeValueAsString(java.util.Map.of("venueId",UUID.randomUUID(),"resourceId",UUID.randomUUID(),"slotInventoryId",slot)))));
        if(kafka) { db.update("UPDATE event_transport_assignments SET transport='KAFKA',authority_epoch=2");var encoded=wire.encode(e,origin(e));long recordOffset=offset++;
            for(String consumer:AFFECTED) assertThat(intake.intake(new ConsumerRecord<byte[],byte[]>(DEST,0,recordOffset,encoded.key().getBytes(StandardCharsets.UTF_8),encoded.body().getBytes(StandardCharsets.UTF_8)),catalog.definition(consumer),2)).isEqualTo(JdbcKafkaIntakeStore.Outcome.TARGET);
        } else transactions.execute(()->store.materialize(100));
        dead(e,businessRegistration,5,5);return e;
    }
    private void dead(StoredEvent e,UUID registration,long fence,long lifetime) { db.update("UPDATE event_deliveries SET state='DEAD',cycle_attempts=3,lifetime_attempts=?,fencing_token=?,lease_until=NULL,next_attempt_at=NULL,failure_code='PAYLOAD_INVALID',failure_detail='PII-free code' WHERE event_id=? AND registration_id=?",lifetime,fence,bytes(e.envelope().eventId().value()),bytes(registration)); }
    private void publicationDead(StoredEvent e) { publications.discover(DEST,100);db.update("UPDATE event_kafka_publications SET state='DEAD',cycle_attempts=3,lifetime_attempts=5,fencing_token=5,lease_until=NULL,next_attempt_at=NULL,ack_partition=NULL,ack_offset=NULL,ack_at=NULL WHERE event_id=?",bytes(e.envelope().eventId().value())); }
    private RecoveryCommand business(StoredEvent e) { boolean kafka="KAFKA".equals(db.queryForObject("SELECT transport FROM event_transport_assignments WHERE registration_id=?",String.class,bytes(businessRegistration)));return new RecoveryCommand(UUID.randomUUID(),tenant,e.envelope().eventId().value(),businessRegistration,BUSINESS,"BUSINESS_REPLAY",null,null,"compatible handler restored","DEAD",5,kafka?"KAFKA":"DB_DIRECT",kafka?2:1,null); }
    private RecoveryCommand publication(StoredEvent e) { return new RecoveryCommand(UUID.randomUUID(),tenant,e.envelope().eventId().value(),null,OBSERVER,"PUBLICATION_RECOVER",DEST,AFFECTED,"publication incident corrected","DEAD",5,null,0,"PUBLICATION_DEAD"); }
    private RecoveryCommand withOperation(RecoveryCommand c,UUID id) { return new RecoveryCommand(id,c.tenantId(),c.eventId(),c.registrationId(),c.consumerId(),c.action(),c.destination(),c.affectedConsumers(),c.reason(),c.expectedState(),c.expectedFence(),c.expectedTransport(),c.expectedAuthorityEpoch(),c.publicationCause()); }
    private java.util.Map<String,Object> state(StoredEvent e,String table) { return db.queryForMap("SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM "+table+" WHERE event_id=?"+(table.equals("event_deliveries")?" AND registration_id=?":""),table.equals("event_deliveries")?new Object[]{bytes(e.envelope().eventId().value()),bytes(businessRegistration)}:new Object[]{bytes(e.envelope().eventId().value())}); }
    private void unchanged(StoredEvent e,String table,long fence) { assertThat(state(e,table)).containsEntry("state","DEAD").containsEntry("fencing_token",fence); }
    private void assertAtomic(RecoveryCommand c,RecoveryResult result) { String operation=db.queryForObject("SELECT result_json FROM operations_recovery_operations WHERE operation_id=?",String.class,bytes(c.operationId()));String audit=db.queryForObject("SELECT result_json FROM operations_recovery_audit WHERE operation_id=?",String.class,bytes(c.operationId()));assertThat(operation).isEqualTo(audit).doesNotContain(actor.token(),"payload","token_hash");assertThat(json.readValue(audit,RecoveryResult.class)).isEqualTo(result); }
    private void problem(Runnable request,int status) { assertThatThrownBy(request::run).isInstanceOfSatisfying(RecoveryProblem.class,error->assertThat(error.status()).isEqualTo(status)); }
    private String recoverOrCode(HumanOperator principal,RecoveryCommand c) { try{return recovery.recover(principal,c,"concurrent").postState();}catch(RecoveryProblem p){return ""+p.status();} }
    private String base() { return "/internal/operations/tenants/"+tenant; }
    private String listUrl() { return base()+"/consumers/"+BUSINESS+"/deliveries"; }
    private String exactUrl(StoredEvent e,UUID registration,String consumer,UUID scope) { return "/internal/operations/tenants/"+scope+"/consumers/"+consumer+"/deliveries/"+e.envelope().eventId().value()+"/"+registration; }
    private record Actor(HumanOperator principal,String token) { }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ProductCredentials {
        @org.springframework.context.annotation.Bean
        com.slotq.auth.web.BearerCredentialResolver productCredentialResolver() {
            return token->token.equals("product-valid-customer-owner-manager-staff")
                ? java.util.Optional.of(new com.slotq.auth.domain.AuthenticatedPrincipal(com.slotq.auth.domain.PrincipalId.newId()))
                : java.util.Optional.empty();
        }
    }
    private ProductTelemetry.Origin origin(StoredEvent e) {
        return db.queryForObject("SELECT origin_request_id,origin_trace_id,origin_span_id FROM event_records WHERE event_id=?",
            (row,n)->new ProductTelemetry.Origin(row.getString(1),row.getString(2),row.getString(3)),bytes(e.envelope().eventId().value()));
    }
    private final class CommitFaultManager extends JpaTransactionManager {
        final AtomicBoolean armed=new AtomicBoolean();private final boolean commitFirst;
        CommitFaultManager(boolean commitFirst) { super(emf);setDataSource(db.getDataSource());afterPropertiesSet();this.commitFirst=commitFirst; }
        @Override protected void doCommit(DefaultTransactionStatus status) { if(armed.compareAndSet(true,false)) { if(commitFirst)super.doCommit(status);else super.doRollback(status);throw new TransactionSystemException("Test commit response unknown"); }super.doCommit(status); }
    }
}
