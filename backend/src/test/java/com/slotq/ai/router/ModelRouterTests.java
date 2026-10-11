package com.slotq.ai.router;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ModelRouterTests {
    final ModelRouter router=new ModelRouter();
    final Instant now=Instant.parse("2026-10-10T00:00:00Z");
    ModelRouter.Classification classification() { return new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,10000,1024); }
    ModelRouter.Candidate candidate(String id,long latency) {
        return new ModelRouter.Candidate(id,"gemini","gemini-3.5-flash-lite",GeminiAdapter.API_MODE,GeminiAdapter.ENDPOINT,null,true,1048576,65536,
            new ModelRouter.Controls(true,true,true,true,Set.of(ModelRouter.DataClass.SYNTHETIC),true,null,null),
            new ModelRouter.Price("fixture-free-v1","free-attested",BigDecimal.ZERO,BigDecimal.ZERO,"official-fixture",now),new ModelRouter.Measurement("fixture-revision","customer",4,3,12,new BigDecimal("0.95"),0,
                BigDecimal.valueOf(latency),BigDecimal.ZERO,"estimated",now));
    }
    ModelRouter.Snapshot snapshot(List<ModelRouter.Candidate> candidates) {
        return new ModelRouter.Snapshot("profile-v1","customer",classification(),candidates,ModelRouter.Policy.bounded(),
            new ModelRouter.Remaining(2,50000,BigDecimal.ZERO,now,now.plusSeconds(45)));
    }
    ModelRouter.Candidate change(ModelRouter.Candidate c,ModelRouter.Controls ctrl,ModelRouter.Measurement measurement) {
        return new ModelRouter.Candidate(c.id(),c.provider(),c.model(),c.apiMode(),c.endpoint(),c.resolvedVersion(),c.structuredOutput(),c.contextLimit(),c.outputLimit(),ctrl,c.price(),measurement);
    }
    @Test void scoringUsesRecordedNormalizationAndWeightsAndStableTieBreak() {
        var decision=router.route(snapshot(List.of(candidate("b",1000),candidate("a",1000))));
        assertThat(decision.selected()).isEqualTo("a");
        assertThat(decision.scores().get("a").total()).isEqualByComparingTo("0.960000");
        assertThat(decision.tieBreak()).isEqualTo("candidate-id-ascending");
        assertThat(router.route(snapshot(List.of(candidate("a",2000),candidate("b",1000)))).selected()).isEqualTo("b");
    }
    @Test void zeroAndOneCandidateAreExplicitAndReplayWorksAfterJsonRoundTrip() {
        assertThat(router.route(snapshot(List.of())).result()).isEqualTo("no_candidate");
        var decision=router.route(snapshot(List.of(candidate("a",1000))));
        assertThat(decision.result()).isEqualTo("one_candidate");
        var json=JsonMapper.builder().build();
        assertThat(router.replay(json.readValue(json.writeValueAsBytes(decision),ModelRouter.Decision.class))).isTrue();
    }
    @Test void securityAndDisclosureCannotBeOffsetByScore() {
        var c=candidate("a",1); var unsafe=new ModelRouter.Controls(true,false,true,true,Set.of(ModelRouter.DataClass.SYNTHETIC),true,null,null);
        var decision=router.route(snapshot(List.of(change(c,unsafe,c.measurement()),candidate("b",30000))));
        assertThat(decision.selected()).isEqualTo("b"); assertThat(decision.excluded().get("a")).contains("security_controls_unavailable");
        var denied=new ModelRouter.Controls(true,true,true,true,Set.of(),true,null,null);
        assertThat(router.route(snapshot(List.of(change(c,denied,c.measurement())))).excluded().get("a")).contains("disclosure_ineligible");
    }
    @Test void fallbackCandidateRequiresItsOwnDisclosureNotPrimaryPermission() {
        var primary=candidate("primary",1000); var other=candidate("alternate",1);
        var control=new ModelRouter.Controls(true,true,true,true,Set.of(ModelRouter.DataClass.PUBLIC_APPROVED),false,null,null);
        var decision=router.route(snapshot(List.of(primary,change(other,control,other.measurement()))));
        assertThat(decision.selected()).isEqualTo("primary"); assertThat(decision.excluded().get("alternate")).contains("disclosure_ineligible");
    }
    @Test void unavailableTrainingRegionAndRetentionFailRequiredDisclosure() {
        var c=candidate("a",1000);
        for(var classification:List.of(new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,false,null,null,10000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,"KR",null,10000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,0,10000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.CONFIDENTIAL,true,true,null,null,10000,1024))) {
            var s=snapshot(List.of(c));
            assertThat(router.route(new ModelRouter.Snapshot(s.workloadRevision(),s.workload(),classification,s.candidates(),s.policy(),s.remaining())).selected()).isNull();
        }
        var ctrl=new ModelRouter.Controls(true,true,true,true,Set.of(ModelRouter.DataClass.SYNTHETIC),null,null,null);
        assertThat(router.route(snapshot(List.of(change(c,ctrl,c.measurement())))).selected()).isNull();
    }
    @Test void qualityFloorAndSafetyFloorRemainHardGates() {
        var c=candidate("a",1); var m=c.measurement();
        for (var invalid:List.of(new ModelRouter.Measurement(m.revision(),m.workload(),4,3,12,new BigDecimal("0.84"),0,m.p95Millis(),BigDecimal.ZERO,"estimated",now),
                new ModelRouter.Measurement(m.revision(),m.workload(),4,3,12,BigDecimal.ONE,1,m.p95Millis(),BigDecimal.ZERO,"estimated",now))) {
            var result=router.route(snapshot(List.of(change(c,c.controls(),invalid))));
            assertThat(result.selected()).isNull(); assertThat(result.scores()).isEmpty();
        }
    }
    @Test void missingInvalidUnavailableAndIncompleteMeasurementsReceiveNoScore() {
        var c=candidate("a",1); var m=c.measurement();
        List<ModelRouter.Measurement> invalid=new ArrayList<>(); invalid.add(null);
        invalid.add(new ModelRouter.Measurement("x","customer",4,3,12,BigDecimal.ONE,0,null,BigDecimal.ZERO,"estimated",now));
        invalid.add(new ModelRouter.Measurement("x","customer",4,3,12,BigDecimal.ONE,0,BigDecimal.ONE,null,"unavailable",now));
        invalid.add(new ModelRouter.Measurement("x","customer",4,2,8,BigDecimal.ONE,0,BigDecimal.ONE,BigDecimal.ZERO,"estimated",now));
        invalid.add(new ModelRouter.Measurement("x","ops",4,3,12,BigDecimal.ONE,0,BigDecimal.ONE,BigDecimal.ZERO,"estimated",now));
        invalid.add(new ModelRouter.Measurement("x","customer",4,3,12,new BigDecimal("1.1"),0,BigDecimal.ONE,BigDecimal.ZERO,"estimated",now));
        for(var measurement:invalid) {
            var result=router.route(snapshot(List.of(change(c,c.controls(),measurement))));
            assertThat(result.excluded().get("a")).contains("measurement_missing_invalid_or_unavailable"); assertThat(result.scores()).isEmpty();
        }
    }
    @Test void missingLimitsUnsupportedModeAndPaidPriceAreRejected() {
        var c=candidate("a",1000);
        var invalid=new ModelRouter.Candidate(c.id(),c.provider(),c.model(),"hosted-agent",GeminiAdapter.ENDPOINT,null,null,null,0,c.controls(),null,c.measurement());
        var result=router.route(snapshot(List.of(invalid)));
        assertThat(result.excluded().get("a")).contains("unsupported_provider_model_mode","structured_output_unavailable","invalid_limits","price_or_free_tier_unavailable");
        var price=new ModelRouter.Price("paid","paid",BigDecimal.ONE,BigDecimal.ONE,"official",now);
        invalid=new ModelRouter.Candidate(c.id(),c.provider(),c.model(),c.apiMode(),c.endpoint(),null,true,1048576,65536,c.controls(),price,c.measurement());
        assertThat(router.route(snapshot(List.of(invalid))).excluded().get("a")).contains("price_or_free_tier_unavailable","cost_budget_exhausted");
    }
    @Test void contextAttemptTokenAndDeadlineOverflowFailClosed() {
        var c=candidate("a",1000);var s=snapshot(List.of(c));
        for(var b:List.of(new ModelRouter.Remaining(0,50000,BigDecimal.ZERO,now,now.plusSeconds(1)),
                new ModelRouter.Remaining(1,1,BigDecimal.ZERO,now,now.plusSeconds(1)),new ModelRouter.Remaining(1,50000,BigDecimal.ZERO,now,now)))
            assertThat(router.route(new ModelRouter.Snapshot(s.workloadRevision(),s.workload(),s.classification(),s.candidates(),s.policy(),b)).selected()).isNull();
        var oversized=new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,1048577,65537);
        assertThat(router.route(new ModelRouter.Snapshot(s.workloadRevision(),s.workload(),oversized,s.candidates(),s.policy(),s.remaining())).excluded().get("a")).contains("context_output_overflow");
    }
    @Test void invalidSnapshotAndPolicyDoNotProduceAnApparentlyValidRecord() {
        assertThatThrownBy(()->router.route(snapshot(List.of(candidate("a",1),candidate("a",2))))).isInstanceOf(IllegalArgumentException.class);
        var s=snapshot(List.of(candidate("a",1)));var p=s.policy();
        var invalid=new ModelRouter.Policy(p.revision(),p.qualityFloor(),BigDecimal.ONE,BigDecimal.ONE,p.costWeight(),p.latencyAnchorMillis(),p.costAnchorDollars(),p.tieBreak());
        assertThatThrownBy(()->router.route(new ModelRouter.Snapshot(s.workloadRevision(),s.workload(),s.classification(),s.candidates(),invalid,s.remaining()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotsAndDecisionCollectionsCannotChangeAfterSelection() {
        List<ModelRouter.Candidate> list=new ArrayList<>(List.of(candidate("a",1000)));var s=snapshot(list);list.clear();
        var decision=router.route(s); assertThat(decision.selected()).isEqualTo("a");
        assertThatThrownBy(()->s.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->decision.scores().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void futureDatedAndOverflowedMeasurementsDoNotBecomeEligible() {
        var c=candidate("a",1);var m=c.measurement();
        var future=new ModelRouter.Measurement(m.revision(),m.workload(),4,3,12,m.quality(),0,m.p95Millis(),BigDecimal.ZERO,"estimated",now.plusSeconds(1));
        assertThat(router.route(snapshot(List.of(change(c,c.controls(),future)))).selected()).isNull();
        var overflow=new ModelRouter.Measurement(m.revision(),m.workload(),Integer.MAX_VALUE,3,12,m.quality(),0,m.p95Millis(),BigDecimal.ZERO,"estimated",now);
        assertThat(router.route(snapshot(List.of(change(c,c.controls(),overflow)))).selected()).isNull();
        var price=new ModelRouter.Price("future","free-attested",BigDecimal.ZERO,BigDecimal.ZERO,"official-fixture",now.plusSeconds(1));
        var invalid=new ModelRouter.Candidate(c.id(),c.provider(),c.model(),c.apiMode(),c.endpoint(),null,true,1048576,65536,c.controls(),price,m);
        assertThat(router.route(snapshot(List.of(invalid))).selected()).isNull();
    }
}
