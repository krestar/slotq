package com.slotq.mcp;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import static com.slotq.mcp.M8CapacityEvidence.JSON;

/** Dedicated physical observer connection and Docker statistics stream, independent of Product Hikari. */
final class M8CapacityResources implements AutoCloseable {
    final List<Map<String, Object>> samples = Collections.synchronizedList(new ArrayList<>());
    private final SingleConnectionDataSource source;
    private final JdbcTemplate db;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final ResultCallback.Adapter<Statistics> docker;
    private volatile Map<String, Object> latest = Map.of();
    private volatile Object dockerSample;
    private volatile long dockerAt;

    M8CapacityResources(MySQLContainer mysql) throws Exception {
        source = new SingleConnectionDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword(), true);
        db = new JdbcTemplate(source);
        db.setQueryTimeout(2);
        var ready = new CountDownLatch(1);
        docker = new ResultCallback.Adapter<>() {
            @Override public void onNext(Statistics statistics) {
                dockerSample = JSON.valueToTree(statistics);
                dockerAt = System.nanoTime();
                ready.countDown();
            }
        };
        DockerClientFactory.instance().client().statsCmd(mysql.getContainerId()).exec(docker);
        M8CapacityEvidence.require(ready.await(10, TimeUnit.SECONDS), "MySQL Docker statistics unavailable");
        sample();
        sampler.scheduleWithFixedDelay(this::sample, 0, 250, TimeUnit.MILLISECONDS);
    }

    Map<String, Object> latest() { return latest; }

    private void sample() {
        long started = System.nanoTime();
        var row = new LinkedHashMap<String, Object>();
        row.put("startedNanos", started);
        row.put("phase", M8CapacityObservation.phase);
        try {
            row.put("backlog", db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state<>'DONE'", Long.class));
            row.put("unmaterialized", db.queryForObject("SELECT COUNT(*) FROM event_records e WHERE NOT EXISTS (SELECT 1 FROM event_deliveries d WHERE d.event_id=e.event_id)", Long.class));
            row.put("lockWaits", db.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class));
            row.put("mysqlStatus", db.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Innodb_row_lock_waits','Innodb_row_lock_time','Bytes_received','Bytes_sent','Innodb_data_reads','Innodb_data_writes','Innodb_os_log_written')"));
            row.put("mysqlFileIo", db.queryForMap("SELECT COALESCE(SUM(COUNT_READ),0) read_count,COALESCE(SUM(COUNT_WRITE),0) write_count,COALESCE(SUM(SUM_TIMER_READ),0) read_picoseconds,COALESCE(SUM(SUM_TIMER_WRITE),0) write_picoseconds,COALESCE(SUM(SUM_NUMBER_OF_BYTES_READ),0) read_bytes,COALESCE(SUM(SUM_NUMBER_OF_BYTES_WRITE),0) written_bytes FROM performance_schema.file_summary_by_instance"));
            row.put("databaseBytes", db.queryForObject("SELECT COALESCE(SUM(DATA_LENGTH+INDEX_LENGTH),0) FROM information_schema.TABLES WHERE TABLE_SCHEMA='m8_capacity'", Long.class));
            row.put("hostStorageFreeBytes", Files.getFileStore(Path.of(".")).getUsableSpace());
            row.put("docker", dockerSample == null ? Map.of() : dockerSample);
            row.put("dockerSampleNanos", dockerAt);
            row.put("status", "OBSERVED");
        } catch (Exception failure) {
            row.put("status", "UNAVAILABLE"); row.put("failureClass", failure.getClass().getSimpleName());
            if (failure.getCause() instanceof java.sql.SQLException sql) {
                row.put("sqlState", Objects.toString(sql.getSQLState(), "unknown")); row.put("sqlErrorCode", sql.getErrorCode());
            }
        }
        row.put("endedNanos", System.nanoTime());
        samples.add(row);
        latest = Collections.unmodifiableMap(row);
    }

    @Override public void close() throws Exception {
        sampler.shutdown();
        M8CapacityEvidence.require(sampler.awaitTermination(10, TimeUnit.SECONDS), "Resource sampler still running");
        docker.close();
        source.destroy();
    }
}
