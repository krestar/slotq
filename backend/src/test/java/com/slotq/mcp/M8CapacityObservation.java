package com.slotq.mcp;

import com.slotq.ai.runtime.AgentRuntime;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only read observations; no production quota, transaction, or pool behavior is replaced. */
final class M8CapacityObservation {
    static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    static final ThreadLocal<String> AUTH_METHOD = new ThreadLocal<>();
    static final List<Map<String, Object>> ACQUISITIONS = Collections.synchronizedList(new ArrayList<>());
    static final List<Map<String, Object>> HTTP = Collections.synchronizedList(new ArrayList<>());
    static final List<Map<String, Object>> TOOLS = Collections.synchronizedList(new ArrayList<>());
    static final List<Map<String, Object>> PROVIDERS = Collections.synchronizedList(new ArrayList<>());
    static final Map<String, Map<String, Object>> MATERIAL = new ConcurrentHashMap<>();
    static volatile String phase = "setup";

    static final class Trace {
        final String id;
        final AtomicInteger sql = new AtomicInteger();
        final AtomicInteger prepared = new AtomicInteger();
        volatile String target;
        volatile String keyDigest;
        volatile String knownTarget;
        volatile String runtimeFailure;
        volatile String runtimeId;
        volatile String wireRequestId;
        volatile int status;
        volatile int responseSql = -1;
        volatile boolean dropResponse;
        Trace(String id) { this.id = id; }
    }

    /** A real Hikari subtype keeps ActorAccessService's existing bounded-execution checks intact. */
    public static class DataSource extends HikariDataSource {
        @Override public Connection getConnection() throws SQLException {
            long started = System.nanoTime();
            String outcome = "SUCCESS";
            try {
                return counted(super.getConnection());
            } catch (SQLException failure) {
                M8RecoveryDrill.authFailure(Objects.toString(AUTH_METHOD.get(), "none"), failure);
                outcome = "UNAVAILABLE";
                throw failure;
            } finally {
                var trace = CURRENT.get();
                ACQUISITIONS.add(Map.of("startedNanos", started, "elapsedMs", (System.nanoTime() - started) / 1e6,
                    "trace", trace == null ? "background" : trace.id, "phase", phase,
                    "authMethod", Objects.toString(AUTH_METHOD.get(), "none"), "outcome", outcome,
                    "outerTransactionActive", TransactionSynchronizationManager.isActualTransactionActive()));
            }
        }
    }

    private static Connection counted(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                Object value = invoke(method, connection, args);
                if (value instanceof Statement statement && Set.of("prepareStatement", "prepareCall", "createStatement").contains(method.getName())) {
                    Trace preparedTrace = CURRENT.get();
                    if (preparedTrace != null) preparedTrace.prepared.incrementAndGet();
                    Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                        : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                        if (m.getName().startsWith("execute")) {
                            Trace executionTrace = CURRENT.get();
                            if (executionTrace != null) executionTrace.sql.incrementAndGet();
                        }
                        return invoke(m, statement, a);
                    });
                }
                return value;
            });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            M8RecoveryDrill.authFailure(Objects.toString(AUTH_METHOD.get(), "none"), failure.getCause());
            throw failure.getCause();
        }
    }

    static List<ToolDefinition> tools(McpRegistrations registrations) {
        return registrations.tools().stream().map(tool -> new ToolDefinition(tool.wire(), tool.permissions(), tool.resource(),
            (context, arguments) -> {
                long start = System.nanoTime();
                var trace = new Trace(context.requestId().toString());
                CURRENT.set(trace);
                String outcome = "UNKNOWN";
                var row = new LinkedHashMap<String, Object>();
                try {
                    var result = tool.handler().execute(context, arguments);
                    outcome = result.failure() == null ? "SUCCESS" : result.failure().name();
                    row.put("productDispatched", result.dispatched());
                    row.put("productRequestId", Objects.toString(result.productRequestId(), ""));
                    return result;
                } finally {
                    row.put("requestId", context.requestId().toString());
                    row.put("phase", phase);
                    row.put("tool", tool.wire().name());
                    row.put("startedNanos", start);
                    row.put("endedNanos", System.nanoTime());
                    row.put("outcome", outcome);
                    row.put("jdbcExecutions", trace.sql.get());
                    TOOLS.add(row);
                    CURRENT.remove();
                }
            }, tool.alternativePermissions(), tool.failureOutcome(), tool.maxResultBytes())).toList();
    }

    static Map<String, Object> permits(LocalAdmission admission) {
        synchronized (admission) {
            var counts = new TreeMap<String, Integer>();
            Map<?, ?> buckets = (Map<?, ?>) field(admission, "buckets");
            for (var entry : buckets.entrySet()) {
                String layer = field(entry.getKey(), "layer").toString();
                int inFlight = (int) field(entry.getValue(), "inFlight");
                counts.merge(layer, inFlight, Integer::sum);
            }
            return Map.of("logical", counts.getOrDefault("principal", 0), "layers", counts);
        }
    }

    static int runtimePermits(AgentRuntime runtime) {
        return ((Number) permits((LocalAdmission) field(runtime, "admission")).get("logical")).intValue();
    }

    static int ingress(Object servlet, int limit) {
        return limit - ((Semaphore) field(servlet, "ingress")).availablePermits();
    }

    static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Observation unavailable: " + name); }
        }
        throw new IllegalStateException("Observation field unavailable: " + name);
    }
}
