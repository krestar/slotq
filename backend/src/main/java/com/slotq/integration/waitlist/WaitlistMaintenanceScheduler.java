package com.slotq.integration.waitlist;

import java.time.Duration;
import com.slotq.auth.domain.SystemPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "slotq.waitlist.promotion.maintenance-enabled", havingValue = "true")
public final class WaitlistMaintenanceScheduler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WaitlistMaintenanceScheduler.class);
    private final WaitlistMaintenanceRuntime runtime;
    private final WaitlistPromotionReadiness readiness;
    public WaitlistMaintenanceScheduler(WaitlistMaintenanceRuntime runtime, WaitlistPromotionReadiness readiness,
        @Value("${slotq.waitlist.promotion.maintenance-interval:PT1S}") Duration interval) {
        if (interval.compareTo(Duration.ofMillis(1)) < 0 || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Maintenance interval must be positive and bounded");
        }
        this.runtime = runtime; this.readiness = readiness;
    }
    @Scheduled(fixedDelayString = "${slotq.waitlist.promotion.maintenance-interval:PT1S}")
    public void tick() {
        if (!readiness.isReady()) return;
        var result = runtime.runCycle(SystemPrincipal.INSTANCE);
        if (!result.failures().isEmpty()) {
            // Do not log payload/SQL/customer data or convert failures to successful no-ops.
            LOG.warn("Waitlist maintenance cycle reported {} target/scan failures", result.failures().size());
        }
    }
}
