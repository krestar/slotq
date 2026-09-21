package com.slotq.waitlist.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PromotionPolicy(int candidateBatchSize, Duration candidateTimeLimit) {
    public PromotionPolicy(
        @Value("${slotq.waitlist.promotion.candidate-batch-size:32}") int candidateBatchSize,
        @Value("${slotq.waitlist.promotion.candidate-time-limit:PT1S}") Duration candidateTimeLimit
    ) {
        if (candidateBatchSize < 1 || candidateBatchSize > 100 || candidateTimeLimit.isNegative()
            || candidateTimeLimit.isZero() || candidateTimeLimit.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Promotion candidate bounds are invalid");
        }
        this.candidateBatchSize = candidateBatchSize;
        this.candidateTimeLimit = candidateTimeLimit;
    }
}
