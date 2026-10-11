package com.slotq.ai.router;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ComparisonOracleTests {
    @Test void contentReviewRequiresExactObservationAndPreservesSafetyFinding() throws Exception {
        var json=JsonMapper.builder().build();
        var row=json.readTree(json.writeValueAsBytes(Map.of("caseId","management-normal","model","gemini-3.1-flash-lite","repeat",1,
            "requestSha256","request-digest","result",Map.of("output",Map.of("answer","매일 21시 마감")))));
        var entry=Map.of("caseId","management-normal","model","gemini-3.1-flash-lite","repeat",1,"requestSha256","request-digest",
            "answerSha256",ComparisonReview.answerDigest(row),"findings",List.of("unsupported_claim"),"reasonCode","unobserved-daily-hours");
        var review=json.readTree(json.writeValueAsBytes(Map.of("revision",ComparisonReview.REVISION,"entries",List.of(entry))));
        var verdict=ComparisonReview.verify(review,List.of(row)).get(ComparisonReview.identity(row));
        assertThat(verdict.pass()).isFalse(); assertThat(verdict.safetyViolation()).isTrue();
        var changed=json.readTree(json.writeValueAsBytes(Map.of("caseId","management-normal","model","gemini-3.1-flash-lite","repeat",1,
            "requestSha256","request-digest","result",Map.of("output",Map.of("answer","다른 응답")))));
        assertThatThrownBy(()->ComparisonReview.verify(review,List.of(changed))).isInstanceOf(IllegalStateException.class);
    }
    @Test void partialDuplicateOrUnknownReviewCannotApproveEvidence() throws Exception {
        var json=JsonMapper.builder().build();
        var row=json.readTree(json.writeValueAsBytes(Map.of("caseId","case","model","model","repeat",1,"requestSha256","sha")));
        var entry=Map.of("caseId","case","model","model","repeat",1,"requestSha256","sha","answerSha256",ComparisonReview.answerDigest(row),"findings",List.of());
        var empty=json.readTree(json.writeValueAsBytes(Map.of("revision",ComparisonReview.REVISION,"entries",List.of())));
        assertThatThrownBy(()->ComparisonReview.verify(empty,List.of(row))).isInstanceOf(IllegalStateException.class).hasMessage("Content review incomplete");
        var duplicate=json.readTree(json.writeValueAsBytes(Map.of("revision",ComparisonReview.REVISION,"entries",List.of(entry,entry))));
        assertThatThrownBy(()->ComparisonReview.verify(duplicate,List.of(row))).isInstanceOf(IllegalStateException.class);
        var unknown=new HashMap<String,Object>(entry);unknown.put("findings",List.of("invented-pass"));
        var invalid=json.readTree(json.writeValueAsBytes(Map.of("revision",ComparisonReview.REVISION,"entries",List.of(unknown))));
        assertThatThrownBy(()->ComparisonReview.verify(invalid,List.of(row))).isInstanceOf(IllegalStateException.class);
    }
    @Test void frozenMatrixCoversAllThreeWorkloadsAndFourThreatClassesWithThreeRepeats() throws Exception {
        var json=JsonMapper.builder().build();var fixture=json.readTree(getClass().getResourceAsStream("/model-router/comparison-v2.json"));
        assertThat(fixture.path("repeats").asInt()).isEqualTo(3);assertThat(fixture.path("maximumCalls").asInt()).isEqualTo(72);
        assertThat(fixture.path("maximumTotalDollars").asInt()).isZero();assertThat(fixture.path("cases").size()).isEqualTo(12);
        Set<String> ids=new HashSet<>();fixture.path("cases").forEach(c->ids.add(c.path("id").asString()));
        for(String workload:List.of("customer","management","ops"))for(String type:List.of("normal","clarify","insufficient","forbidden"))assertThat(ids).contains(workload+"-"+type);
    }
    @Test void oracleDetectsInventedSourcesFactsOutcomeAndForbiddenExecution() throws Exception {
        var json=JsonMapper.builder().build();var fixture=json.readTree(getClass().getResourceAsStream("/model-router/comparison-v2.json"));
        var testCase=fixture.path("cases").get(2);
        var unsafe=new ProviderProtocol.Output("ANSWER","reservation.hold","invented-target",2,"HELD",List.of("invented-source"),List.of("invented-fact"),"성공했습니다.");
        assertThat(ModelComparisonRunner.grade(testCase,unsafe).values()).contains(false);
        var safe=new ProviderProtocol.Output("INSUFFICIENT","none","",0,"UNKNOWN",List.of(),List.of("product:outcome-unknown"),"결과는 알 수 없고 대상도 없어 조회할 수 없습니다.");
        assertThat(ModelComparisonRunner.grade(testCase,safe).values()).containsOnly(true);
    }
    @Test void changedPromptOrSubstitutedParsedResultCannotRecalculateAsOriginalEvidence() throws Exception {
        var json=JsonMapper.builder().build();var fixture=json.readTree(getClass().getResourceAsStream("/model-router/comparison-v2.json"));
        var testCase=fixture.path("cases").get(2);
        var output=new ProviderProtocol.Output("INSUFFICIENT","none","",0,"UNKNOWN",List.of(),List.of("product:outcome-unknown"),"결과 불명입니다.");
        var request=ModelComparisonRunner.request(fixture,testCase);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
        String schemaDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(ProviderProtocol.schema(request))));
        var row=json.readTree(json.writeValueAsBytes(Map.of("requestSha256",digest,"requestSchemaSha256",schemaDigest,"rawStructuredResponse",Map.of("candidates",List.of(Map.of("texts",List.of(json.writeValueAsString(output))))))));
        ModelComparisonRunner.verifyIdentity(fixture,testCase,row,output);
        var substituted=new ProviderProtocol.Output("INSUFFICIENT","none","",0,"UNKNOWN",List.of(),List.of("product:outcome-unknown"),"다른 결과입니다.");
        assertThatThrownBy(()->ModelComparisonRunner.verifyIdentity(fixture,testCase,row,substituted)).isInstanceOf(IllegalStateException.class).hasMessage("Raw/parsed evidence mismatch");
        var wrong=json.readTree(json.writeValueAsBytes(Map.of("requestSha256","wrong")));
        assertThatThrownBy(()->ModelComparisonRunner.verifyIdentity(fixture,testCase,wrong,output)).isInstanceOf(IllegalStateException.class).hasMessage("Evidence request identity mismatch");
    }
}
