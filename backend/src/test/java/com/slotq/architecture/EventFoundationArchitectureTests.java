package com.slotq.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryClaim;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliverySnapshot;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.EventRecordQuery;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class EventFoundationArchitectureTests {

    @Test
    void discoversTheActualFoundationAndItsTwoNarrowWriteStores() throws Exception {
        List<Class<?>> foundation = mainTypes().stream()
            .filter(type -> type.getPackageName().startsWith("com.slotq.events.")).toList();

        assertThat(foundation).contains(EventRecordStore.class, EventDeliveryStore.class,
            EventHandler.class, EventReplayService.class);
        assertThat(foundation.stream().filter(Class::isInterface)
            .filter(type -> type.getSimpleName().endsWith("Store")).toList())
            .containsExactlyInAnyOrder(EventRecordStore.class, EventDeliveryStore.class);
        assertThat(foundation.stream().filter(Class::isInterface)
            .filter(type -> type.getSimpleName().endsWith("Repository")).toList()).isEmpty();
    }

    @Test
    void appendPortLimitsGlobalAccessToFenceCollisionDetectionAndRegistrationLifecycle() {
        assertThat(signatures(EventRecordStore.class)).containsExactlyInAnyOrder(
            "long lockBoundary()",
            "void setBoundary(long)",
            "Optional findEventForAppend(EventId)",
            "StoredEvent insertEvent(EventEnvelope,long)",
            "boolean hasActiveRegistration(ConsumerRoute)",
            "List registrationsFor(String,List)",
            "void insertRegistration(UUID,ConsumerRoute,long)",
            "boolean isRegistrationActive(UUID)",
            "void deactivateRegistration(UUID,long)"
        );
    }

    @Test
    void immutableEvidenceQueryRequiresTenantAndEventAndCannotChangeDelivery() {
        assertThat(signatures(EventRecordQuery.class)).containsExactly("Optional find(TenantId,EventId)");
    }

    @Test
    void deliveryPortAllowsOnlyBoundedDiscoveryAndScopeBearingProtocolOperations() throws Exception {
        assertThat(signatures(EventDeliveryStore.class)).containsExactlyInAnyOrder(
            "void configureTimeouts(DeliveryPolicy)",
            "int materialize(int)",
            "List candidates(int)",
            "Optional lock(DeliveryKey)",
            "Instant databaseNow()",
            "Target target(DeliveryKey)",
            "void claim(DeliverySnapshot,Instant,Instant)",
            "void done(DeliveryClaim,Instant)",
            "void fail(DeliveryClaim,DeliveryFailure,Instant,Instant)",
            "void exhaust(DeliverySnapshot,Instant)",
            "void replay(DeliverySnapshot,String,Instant)"
        );
        assertThat(EventDeliveryStore.class.getMethod("candidates", int.class)
            .getGenericReturnType().getTypeName())
            .isEqualTo("java.util.List<com.slotq.events.application.DeliveryKey>");
        assertThat(EventDeliveryStore.class.getMethod("lock", DeliveryKey.class)
            .getGenericReturnType().getTypeName())
            .isEqualTo("java.util.Optional<com.slotq.events.application.DeliverySnapshot>");
    }

    @Test
    void protocolCarriersRetainExplicitTenantEventAndDurableTargetScope() throws Exception {
        assertThat(DeliveryKey.class.getRecordComponents()).extracting(component -> component.getType().getName())
            .containsExactly(TenantId.class.getName(), EventId.class.getName(), UUID.class.getName());
        assertThat(DeliveryClaim.class.getRecordComponents()).extracting(component -> component.getType().getName())
            .containsExactly(DeliveryKey.class.getName(), long.class.getName());
        assertThat(DeliverySnapshot.class.getRecordComponents()[0].getType()).isEqualTo(DeliveryKey.class);
        assertThat(EventEnvelope.class.getRecordComponents()[1].getType()).isEqualTo(TenantId.class);
        assertThat(StoredEvent.class.getRecordComponents()).extracting(component -> component.getType().getName())
            .containsExactly(EventEnvelope.class.getName(), long.class.getName(), Instant.class.getName());
        assertThat(signatures(EventReplayService.class)).containsExactly(
            "void replay(SystemPrincipal,DeliveryKey,String)");
        assertThat(EventReplayService.class.getMethod("replay", SystemPrincipal.class, DeliveryKey.class,
            String.class).getReturnType()).isEqualTo(void.class);
    }

    @Test
    void foundationDoesNotDependOnBookingWaitlistOrVenueConcreteImplementations() throws Exception {
        List<String> forbidden = List.of(
            "com/slotq/booking/", "com/slotq/waitlist/", "com/slotq/venue/persistence/",
            "com/slotq/venue/domain/Venue;", "com/slotq/venue/domain/Resource;",
            "com/slotq/venue/domain/BookingPolicy;"
        );
        List<String> violations = new ArrayList<>();
        for (Class<?> type : mainTypes()) {
            if (!type.getPackageName().startsWith("com.slotq.events.")) {
                continue;
            }
            String bytes = bytecode(type);
            for (String dependency : forbidden) {
                if (bytes.contains(dependency)) {
                    violations.add(type.getName() + " -> " + dependency);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void foundationAndReplayHaveNoHttpSurface() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : mainTypes()) {
            if (exposesFoundationOverHttp(bytecode(type))) {
                violations.add(type.getName());
            }
        }
        assertThat(violations).isEmpty();
        assertThat(exposesFoundationOverHttp(bytecode(UnsafeReplayController.class))).isTrue();
        assertThat(exposesFoundationOverHttp(bytecode(EventReplayService.class))).isFalse();
    }

    @Test
    void productionHandlersCannotDirectlyOpenConnectionsOrSubmitIndependentWork() throws Exception {
        List<Class<?>> main = mainTypes();
        assertThat(main).contains(EventHandler.class);
        List<String> violations = new ArrayList<>();
        for (Class<?> type : main) {
            if (EventHandler.class.isAssignableFrom(type) && !type.isInterface()
                && !Modifier.isAbstract(type.getModifiers())) {
                for (String forbidden : independentEffectReferences(bytecode(type))) {
                    violations.add(type.getName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void handlerGuardDetectsUnsafeIndependentEffectsUsingNegativeFixtures() throws Exception {
        assertThat(independentEffectReferences(bytecode(SynchronousHandler.class))).isEmpty();
        assertThat(independentEffectReferences(bytecode(RawConnectionHandler.class)))
            .contains("javax/sql/DataSource", "java/sql/Connection");
        assertThat(independentEffectReferences(bytecode(AsyncSubmissionHandler.class)))
            .contains("java/util/concurrent/Executor");
    }

    private List<String> signatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> !method.isSynthetic())
            .map(method -> method.getReturnType().getSimpleName() + " " + method.getName() + "("
                + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                    .collect(Collectors.joining(",")) + ")")
            .toList();
    }

    private List<Class<?>> mainTypes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path main = Path.of(EventHandler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> types = new ArrayList<>();
        try (var paths = Files.walk(main.resolve("com/slotq"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".class")).toList()) {
                String name = main.relativize(path).toString().replace(path.getFileSystem().getSeparator(), ".")
                    .replaceAll("\\.class$", "");
                types.add(Class.forName(name, false, getClass().getClassLoader()));
            }
        }
        return types;
    }

    private String bytecode(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing architecture fixture " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private boolean exposesFoundationOverHttp(String bytes) {
        return bytes.contains("com/slotq/events/")
            && bytes.contains("org/springframework/web/bind/annotation/");
    }

    private List<String> independentEffectReferences(String bytes) {
        return List.of("javax/sql/DataSource", "java/sql/Connection", "java/util/concurrent/Executor",
            "java/util/concurrent/CompletableFuture", "org/springframework/scheduling/annotation/Async",
            "org/springframework/transaction/support/TransactionTemplate",
            "org/springframework/transaction/PlatformTransactionManager").stream()
            .filter(bytes::contains).toList();
    }

    static class SynchronousHandler implements EventHandler {
        @Override public ConsumerRoute route() { return new ConsumerRoute("Synthetic", "Changed", 1); }
        @Override public void handle(StoredEvent event) { }
    }

    static class RawConnectionHandler extends SynchronousHandler {
        private final DataSource dataSource;
        RawConnectionHandler(DataSource dataSource) { this.dataSource = dataSource; }
        @Override public void handle(StoredEvent event) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    static class AsyncSubmissionHandler extends SynchronousHandler {
        private final Executor executor;
        AsyncSubmissionHandler(Executor executor) { this.executor = executor; }
        @Override public void handle(StoredEvent event) { executor.execute(() -> { }); }
    }

    @RestController
    static class UnsafeReplayController {
        private final EventReplayService replay;
        UnsafeReplayController(EventReplayService replay) { this.replay = replay; }
    }
}
