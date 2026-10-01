package com.slotq.events.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.slotq.events.application.*;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Forces two independent consumers to reach first target insertion together under real MySQL RR. */
@Testcontainers @SpringBootTest @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaIntakeConcurrencyIntegrationTests {
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL=
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq_intake_concurrency");
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;
    @Autowired EventTransportCutover cutover;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired KafkaIntakeWire wire;
    @Autowired com.slotq.integration.waitlist.WaitlistKafkaMessage mapping;

    @BeforeAll void authority(){for(var consumer:catalog.consumers())for(var route:consumer.routes())registrations.activate(route);cutover.complete("KAFKA",100);}

    @RepeatedTest(3) void simultaneousColdTargetsKeepBothPrefixesAndFirstMaterializationWithoutGapDeadlock() throws Exception {
        UUID tenant=UUID.randomUUID(),slot=UUID.randomUUID();db.update("INSERT INTO tenants(id,status) VALUES (?,'ACTIVE')",bytes(tenant));
        StoredEvent event=new TransactionTemplate(manager).execute(s->append.append(new EventEnvelope(EventId.newId(),new TenantId(tenant),"SlotInventory",slot,"waitlist.promotion-requested",1,Instant.now(),"{\"venueId\":\""+UUID.randomUUID()+"\",\"resourceId\":\""+UUID.randomUUID()+"\",\"slotInventoryId\":\""+slot+"\"}")));
        var origin=db.queryForObject("SELECT origin_request_id,origin_trace_id,origin_span_id FROM event_records WHERE event_id=?",(r,n)->new ProductTelemetry.Origin(r.getString(1),r.getString(2),r.getString(3)),bytes(event.envelope().eventId().value()));var message=mapping.encode(event,origin);
        String topic="slotq.intake111."+UUID.randomUUID();var record=new ConsumerRecord<byte[],byte[]>(topic,0,0,message.key().getBytes(StandardCharsets.UTF_8),message.body().getBytes(StandardCharsets.UTF_8));
        var gate=new CyclicBarrier(2);var source=Objects.requireNonNull(db.getDataSource());
        var coordinated=new JdbcTemplate(source){@Override public int update(String sql,Object...args){
            if(sql.stripLeading().startsWith("INSERT INTO event_deliveries"))try{gate.await(10,TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}
            return super.update(sql,args);
        }};
        var intake=new JdbcKafkaIntakeStore(coordinated,manager,wire);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=catalog.consumers().stream().map(c->pool.submit(()->intake.intake(record,c,2))).toList();
            for(var future:futures)assertThat(future.get(15,TimeUnit.SECONDS)).isEqualTo(JdbcKafkaIntakeStore.Outcome.TARGET);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='PENDING' AND lifetime_attempts=0 AND fencing_token=0",Integer.class,bytes(event.envelope().eventId().value()))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=? AND first_materialization=1",Integer.class,bytes(event.envelope().eventId().value()))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_consumer_positions WHERE topic=? AND last_durable_offset=0",Integer.class,topic)).isEqualTo(2);
        // A repeated coordinate must reuse both targets without another insertion or retry-budget change.
        for(var consumer:catalog.consumers())assertThat(intake.intake(record,consumer,2)).isEqualTo(JdbcKafkaIntakeStore.Outcome.REUSED_TARGET);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_intake_records WHERE topic=?",Integer.class,topic)).isEqualTo(2);
    }
    private static byte[] bytes(UUID id){return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
}
