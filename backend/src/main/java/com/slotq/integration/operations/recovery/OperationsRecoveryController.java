package com.slotq.integration.operations.recovery;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/operations/tenants/{tenant}")
class OperationsRecoveryController {
    private final HumanRecoveryService recovery;
    private final RecoveryReadService reads;
    OperationsRecoveryController(HumanRecoveryService recovery, RecoveryReadService reads) {
        this.recovery=recovery; this.reads=reads;
    }

    @GetMapping("/consumers/{consumer}/deliveries")
    List<RecoveryReadService.DeliveryView> list(@AuthenticationPrincipal HumanOperator actor,
        @PathVariable UUID tenant,@PathVariable String consumer,
        @RequestParam(required=false) UUID afterEvent,@RequestParam(required=false) UUID afterRegistration,
        @RequestParam(defaultValue="50") int limit) {
        return reads.list(actor,tenant,consumer,afterEvent,afterRegistration,limit);
    }

    @GetMapping("/consumers/{consumer}/deliveries/{event}/{registration}")
    RecoveryReadService.DeliveryView exact(@AuthenticationPrincipal HumanOperator actor,
        @PathVariable UUID tenant,@PathVariable String consumer,@PathVariable UUID event,@PathVariable UUID registration) {
        return reads.exact(actor,tenant,consumer,event,registration);
    }

    @PostMapping("/consumers/{consumer}/deliveries/{event}/{registration}/replay")
    RecoveryResult replay(@AuthenticationPrincipal HumanOperator actor,@PathVariable UUID tenant,
        @PathVariable String consumer,@PathVariable UUID event,@PathVariable UUID registration,
        @RequestBody BusinessRequest request,HttpServletResponse response) {
        if (request.expectedFence()==null || request.expectedAuthorityEpoch()==null) throw RecoveryProblem.invalid();
        return recovery.recover(actor,new RecoveryCommand(request.operationId(),tenant,event,registration,consumer,
            "BUSINESS_REPLAY",null,null,request.reason(),request.expectedState(),request.expectedFence(),
            request.expectedTransport(),request.expectedAuthorityEpoch(),null),correlation(response));
    }

    @GetMapping("/publications/{event}")
    RecoveryReadService.PublicationView publication(@AuthenticationPrincipal HumanOperator actor,
        @PathVariable UUID tenant,@PathVariable UUID event,@RequestParam String destination) {
        return reads.publication(actor,tenant,event,destination);
    }

    @PostMapping("/publications/{event}/recover")
    RecoveryResult publicationRecovery(@AuthenticationPrincipal HumanOperator actor,@PathVariable UUID tenant,
        @PathVariable UUID event,@RequestBody PublicationRequest request,HttpServletResponse response) {
        if (request.affectedConsumers()==null || request.affectedConsumers().isEmpty() || request.expectedFence()==null)
            throw RecoveryProblem.invalid();
        return recovery.recover(actor,new RecoveryCommand(request.operationId(),tenant,event,null,
            request.affectedConsumers().getFirst(),"PUBLICATION_RECOVER",request.destination(),request.affectedConsumers(),
            request.reason(),request.expectedState(),request.expectedFence(),null,0,request.cause()),correlation(response));
    }

    @GetMapping("/consumers/{consumer}/operations/{operation}")
    RecoveryResult operation(@AuthenticationPrincipal HumanOperator actor,@PathVariable UUID tenant,
        @PathVariable String consumer,@PathVariable UUID operation) {
        return recovery.operation(actor,tenant,consumer,operation);
    }

    @GetMapping("/consumers/{consumer}/audit")
    List<RecoveryResult> audit(@AuthenticationPrincipal HumanOperator actor,@PathVariable UUID tenant,
        @PathVariable String consumer,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="50") int limit) {
        return recovery.audit(actor,tenant,consumer,after,limit);
    }

    private static String correlation(HttpServletResponse response) {
        String id=response.getHeader("X-Request-ID");
        return id==null ? UUID.randomUUID().toString() : id;
    }
    record BusinessRequest(UUID operationId,String reason,String expectedState,Long expectedFence,
                           String expectedTransport,Long expectedAuthorityEpoch) { }
    record PublicationRequest(UUID operationId,String destination,List<String> affectedConsumers,String reason,
                              String expectedState,Long expectedFence,String cause) { }
}
