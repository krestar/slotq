package com.slotq.events.application;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public final class EventHandlers {
    private final Map<ConsumerRoute, EventHandler> handlers;

    public EventHandlers(List<EventHandler> handlers) {
        Map<ConsumerRoute, EventHandler> routes = new HashMap<>();
        for (EventHandler handler : handlers) {
            ConsumerRoute route = handler.route();
            EventCanonicalizer.requireIdentifier(route.consumerId(), "consumerId");
            EventCanonicalizer.requireIdentifier(route.eventType(), "eventType");
            if (route.schemaVersion() < 1 || routes.putIfAbsent(route, handler) != null) {
                throw new IllegalArgumentException("Duplicate or invalid event handler route");
            }
            requireJoinedHandler(handler);
        }
        this.handlers = Map.copyOf(routes);
    }

    public EventHandler resolve(ConsumerRoute route) {
        EventHandler handler = handlers.get(route);
        if (handler != null) {
            return handler;
        }
        boolean otherVersion = handlers.keySet().stream().anyMatch(candidate ->
            candidate.consumerId().equals(route.consumerId()) && candidate.eventType().equals(route.eventType()));
        throw new EventHandlingException(otherVersion
            ? DeliveryFailure.UNSUPPORTED_VERSION : DeliveryFailure.TARGET_HANDLER_MISSING);
    }

    private void requireJoinedHandler(EventHandler handler) {
        Class<?> type = AopUtils.getTargetClass(handler);
        try {
            Method method = type.getMethod("handle", StoredEvent.class);
            if (AnnotatedElementUtils.findMergedAnnotation(type, Async.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, Async.class) != null) {
                throw new IllegalArgumentException("Event handlers must execute synchronously");
            }
            Transactional transaction = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            if (transaction == null) {
                transaction = AnnotatedElementUtils.findMergedAnnotation(type, Transactional.class);
            }
            if (transaction != null && (transaction.readOnly() || !transaction.transactionManager().isEmpty()
                || (transaction.propagation() != Propagation.REQUIRED
                    && transaction.propagation() != Propagation.MANDATORY))) {
                throw new IllegalArgumentException("Event handlers must join the writable Product transaction");
            }
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("Invalid event handler", failure);
        }
    }
}
