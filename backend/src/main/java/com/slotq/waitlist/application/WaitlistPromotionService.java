package com.slotq.waitlist.application;

import java.time.Clock;
import java.util.Objects;

import com.slotq.auth.domain.SystemPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WaitlistPromotionService implements WaitlistPromotionUseCase {
    private final WaitlistPromotionReceiptStore receipts;
    private final WaitlistOfferCommandExecutor executor;
    private final WaitlistNotificationStore notifications;
    private final PromotionPolicy policy;
    private final Clock clock;

    WaitlistPromotionService(WaitlistPromotionReceiptStore receipts, WaitlistOfferCommandExecutor executor,
                            WaitlistNotificationStore notifications, PromotionPolicy policy, Clock clock) {
        this.receipts = receipts;
        this.executor = executor;
        this.notifications = notifications;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result promote(SystemPrincipal principal, Command command) {
        Objects.requireNonNull(principal, "system principal must not be null");
        var commandNow = clock.instant();
        var receipt = receipts.claim(command.tenantId(), command);
        if (!receipt.meaning().equals(command)) throw new PromotionIdentityException();
        if (receipt.result() != null) return receipt.result();
        var effect = executor.promote(principal, command, commandNow, policy);
        Result result;
        if (effect.offer() == null) {
            result = new Result(effect.outcome(), null, null, null);
        } else {
            var offer = effect.offer();
            notifications.offerAvailable(command.tenantId(), offer);
            result = new Result(Outcome.PROMOTED, offer.id().value(), offer.entryId().value(), offer.reservationId().value());
        }
        receipts.complete(command.tenantId(), command, result);
        return result;
    }
}
