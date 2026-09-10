package com.slotq.events.application;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventHandlersTests {
    private static final ConsumerRoute ROUTE = new ConsumerRoute("SyntheticProjection", "SyntheticChanged", 1);

    @Test
    void resolvesOnlyTheExactConsumerTypeAndVersionIncludingCase() {
        EventHandler original = new PlainHandler(ROUTE);
        EventHandler version = new PlainHandler(new ConsumerRoute(ROUTE.consumerId(), ROUTE.eventType(), 2));
        EventHandler consumerCase = new PlainHandler(new ConsumerRoute("syntheticprojection", ROUTE.eventType(), 1));
        EventHandler eventCase = new PlainHandler(new ConsumerRoute(ROUTE.consumerId(), "syntheticchanged", 1));
        var handlers = new EventHandlers(List.of(original, version, consumerCase, eventCase));

        for (EventHandler handler : List.of(original, version, consumerCase, eventCase)) {
            assertThat(handlers.resolve(handler.route())).isSameAs(handler);
        }
    }

    @Test
    void missingRouteAndUnsupportedTargetedVersionHaveDifferentTerminalFailures() {
        var handlers = new EventHandlers(List.of(new PlainHandler(ROUTE)));

        assertFailure(handlers, new ConsumerRoute("OtherConsumer", ROUTE.eventType(), 1),
            DeliveryFailure.TARGET_HANDLER_MISSING);
        assertFailure(handlers, new ConsumerRoute(ROUTE.consumerId(), "OtherType", 1),
            DeliveryFailure.TARGET_HANDLER_MISSING);
        assertFailure(handlers, new ConsumerRoute(ROUTE.consumerId(), ROUTE.eventType(), 2),
            DeliveryFailure.UNSUPPORTED_VERSION);
        assertFailure(new EventHandlers(List.of()), ROUTE, DeliveryFailure.TARGET_HANDLER_MISSING);
    }

    @Test
    void duplicateExactRouteIsRejected() {
        assertThatThrownBy(() -> new EventHandlers(List.of(new PlainHandler(ROUTE), new PlainHandler(ROUTE))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidRoutes")
    void invalidRuntimeRouteFailsAtRegistration(ConsumerRoute route) {
        assertThatThrownBy(() -> new EventHandlers(List.of(new PlainHandler(route))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<ConsumerRoute> invalidRoutes() {
        return Stream.of(
            new ConsumerRoute(" ", ROUTE.eventType(), 1),
            new ConsumerRoute("a".repeat(101), ROUTE.eventType(), 1),
            new ConsumerRoute("consumer/unsafe", ROUTE.eventType(), 1),
            new ConsumerRoute(ROUTE.consumerId(), "이벤트", 1),
            new ConsumerRoute(ROUTE.consumerId(), "Changed ", 1),
            new ConsumerRoute(ROUTE.consumerId(), ROUTE.eventType(), 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeHandlers")
    void rejectsHandlersThatCanLeaveTheWritableSynchronousTransaction(String description, EventHandler handler) {
        assertThatThrownBy(() -> new EventHandlers(List.of(handler)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> unsafeHandlers() {
        return Stream.of(
            Arguments.of("async method", new AsyncMethodHandler()),
            Arguments.of("async class", new AsyncClassHandler()),
            Arguments.of("async inherited interface", new AsyncInterfaceHandler()),
            Arguments.of("requires new method", new RequiresNewHandler()),
            Arguments.of("not supported class", new NotSupportedHandler()),
            Arguments.of("read only method", new ReadOnlyHandler()),
            Arguments.of("different manager", new DifferentManagerHandler()),
            Arguments.of("different manager alias", new AliasedManagerHandler())
        );
    }

    @Test
    void validatesTheTargetClassBehindASpringProxy() {
        EventHandler unsafeProxy = (EventHandler) new ProxyFactory(new RequiresNewHandler()).getProxy();
        assertThatThrownBy(() -> new EventHandlers(List.of(unsafeProxy)))
            .isInstanceOf(IllegalArgumentException.class);

        EventHandler joinedProxy = (EventHandler) new ProxyFactory(new MandatoryHandler()).getProxy();
        assertThat(new EventHandlers(List.of(joinedProxy)).resolve(ROUTE)).isSameAs(joinedProxy);
    }

    @Test
    void acceptsSynchronousHandlersAndJoiningWritableTransactionDeclarations() {
        for (EventHandler handler : List.of(new PlainHandler(), new RequiredHandler(), new MandatoryHandler())) {
            assertThat(new EventHandlers(List.of(handler)).resolve(ROUTE)).isSameAs(handler);
        }
    }

    private void assertFailure(EventHandlers handlers, ConsumerRoute route, DeliveryFailure expected) {
        assertThatThrownBy(() -> handlers.resolve(route))
            .isInstanceOfSatisfying(EventHandlingException.class,
                failure -> assertThat(failure.failure()).isEqualTo(expected));
        assertThat(expected.retryable()).isFalse();
    }

    static class PlainHandler implements EventHandler {
        private final ConsumerRoute route;

        PlainHandler() { this(ROUTE); }
        PlainHandler(ConsumerRoute route) { this.route = route; }
        @Override public ConsumerRoute route() { return route; }
        @Override public void handle(StoredEvent event) { }
    }

    static class AsyncMethodHandler extends PlainHandler {
        @Override @Async public void handle(StoredEvent event) { }
    }

    @Async
    static class AsyncClassHandler extends PlainHandler { }

    interface AsyncContract extends EventHandler {
        @Override @Async void handle(StoredEvent event);
    }

    static class AsyncInterfaceHandler extends PlainHandler implements AsyncContract {
        @Override public void handle(StoredEvent event) { }
    }

    static class RequiresNewHandler extends PlainHandler {
        @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void handle(StoredEvent event) { }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    static class NotSupportedHandler extends PlainHandler { }

    static class ReadOnlyHandler extends PlainHandler {
        @Override @Transactional(readOnly = true) public void handle(StoredEvent event) { }
    }

    static class DifferentManagerHandler extends PlainHandler {
        @Override @Transactional(transactionManager = "independentManager")
        public void handle(StoredEvent event) { }
    }

    @Transactional("independentManager")
    static class AliasedManagerHandler extends PlainHandler { }

    static class RequiredHandler extends PlainHandler {
        @Override @Transactional public void handle(StoredEvent event) { }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    static class MandatoryHandler extends PlainHandler { }
}
