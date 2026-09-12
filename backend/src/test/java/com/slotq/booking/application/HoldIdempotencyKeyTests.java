package com.slotq.booking.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldIdempotencyKeyTests {

    @Test
    void acceptsOpaqueVisibleAsciiWithoutNormalization() {
        HoldIdempotencyKey key = new HoldIdempotencyKey("Customer-Intent_Aa/09=~");

        assertThat(key.value()).isEqualTo("Customer-Intent_Aa/09=~");
        assertThat(HoldIdempotencyKey.fromHeader(null)).isEmpty();
    }

    @Test
    void rejectsBlankNonAsciiWhitespaceAndLengthOverflow() {
        for (String invalid : new String[] {"", " ", "contains space", "한글", "k".repeat(256)}) {
            assertThatThrownBy(() -> new HoldIdempotencyKey(invalid))
                .isInstanceOf(HoldIdempotencyValidationException.class);
        }
    }
}
