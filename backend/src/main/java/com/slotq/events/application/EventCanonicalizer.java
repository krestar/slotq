package com.slotq.events.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventCanonicalizer {

    public static final int IDENTIFIER_MAX_LENGTH = 100;
    private static final int MAX_PAYLOAD_BYTES = 16_777_215;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Instant MIN_OCCURRED_AT = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_OCCURRED_AT = Instant.parse("9999-12-31T23:59:59.999999Z");

    private final JsonMapper json = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .build();

    public EventEnvelope canonicalize(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "event envelope must not be null");
        Objects.requireNonNull(envelope.eventId(), "eventId must not be null");
        Objects.requireNonNull(envelope.eventId().value(), "eventId value must not be null");
        Objects.requireNonNull(envelope.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(envelope.tenantId().value(), "tenantId value must not be null");
        Objects.requireNonNull(envelope.aggregateId(), "aggregateId must not be null");
        requireIdentifier(envelope.aggregateType(), "aggregateType");
        requireIdentifier(envelope.eventType(), "eventType");
        if (envelope.schemaVersion() <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Instant occurredAt = Objects.requireNonNull(envelope.occurredAt(), "occurredAt must not be null")
            .truncatedTo(ChronoUnit.MICROS);
        if (occurredAt.isBefore(MIN_OCCURRED_AT) || occurredAt.isAfter(MAX_OCCURRED_AT)) {
            throw new IllegalArgumentException("occurredAt must fit MySQL DATETIME(6)");
        }
        return new EventEnvelope(
            envelope.eventId(), envelope.tenantId(), envelope.aggregateType(), envelope.aggregateId(),
            envelope.eventType(), envelope.schemaVersion(), occurredAt, canonicalizePayload(envelope.payload())
        );
    }

    public String canonicalizePayload(String payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        JsonNode value = json.readTree(payload);
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("payload must contain one JSON value");
        }
        StringBuilder canonical = new StringBuilder();
        appendValue(canonical, value);
        String result = canonical.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds MEDIUMTEXT storage limit");
        }
        return result;
    }

    public static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must use 1-100 ASCII letters, digits, '.', '_' or '-'");
        }
    }

    private void appendValue(StringBuilder output, JsonNode value) {
        if (value.isObject()) {
            output.append('{');
            boolean first = true;
            for (String name : value.propertyNames().stream().sorted().toList()) {
                if (!first) output.append(',');
                first = false;
                appendString(output, name);
                output.append(':');
                appendValue(output, value.get(name));
            }
            output.append('}');
        } else if (value.isArray()) {
            output.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) output.append(',');
                appendValue(output, value.get(index));
            }
            output.append(']');
        } else if (value.isString()) {
            appendString(output, value.asString());
        } else if (value.isNumber()) {
            BigDecimal number = value.decimalValue().stripTrailingZeros();
            output.append(number.signum() == 0 ? "0" : number.toString());
        } else if (value.isBoolean()) {
            output.append(value.booleanValue());
        } else if (value.isNull()) {
            output.append("null");
        } else {
            throw new IllegalArgumentException("payload contains an unsupported JSON value");
        }
    }

    private void appendString(StringBuilder output, String value) {
        // Reject unpaired surrogates rather than letting UTF-8/JDBC silently replace their value.
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("payload contains an unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("payload contains an unpaired Unicode surrogate");
            }
        }
        output.append(json.writeValueAsString(value));
    }
}
