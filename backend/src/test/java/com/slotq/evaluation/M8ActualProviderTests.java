package com.slotq.evaluation;

import com.slotq.ai.router.GeminiAdapter;
import com.slotq.ai.router.ModelRouter;
import com.slotq.ai.router.ProviderBudget;
import com.slotq.ai.router.ProviderProtocol;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class M8ActualProviderTests {
    @ParameterizedTest
    @EnumSource(value = GeminiAdapter.Failure.class, names = {
        "RATE_LIMIT", "TEMPORARY_OVERLOAD", "AUTH_CONFIGURATION", "ACCOUNT_QUOTA",
        "TIMEOUT_REMOTE_UNKNOWN", "MALFORMED"
    })
    void unknownUsagePreservesAttemptJournalReservationAndStopsBeforeNextCell(GeminiAdapter.Failure failure) throws Exception {
        Integer httpStatus = switch (failure) {
            case RATE_LIMIT, ACCOUNT_QUOTA -> 429;
            case TEMPORARY_OVERLOAD -> 503;
            case AUTH_CONFIGURATION -> 403;
            case MALFORMED -> 200;
            default -> null;
        };
        var response = new GeminiAdapter.Result(null, failure, 1, 1,
            new GeminiAdapter.Usage(null, null, null, null, "unavailable"), "unavailable", null,
            httpStatus, GeminiAdapter.disposition(failure));
        verifySettlement(response, 1, 35000, true);
    }

    @Test
    void rejectedBeforeDispatchDoesNotConsumeAnAttemptOrUnknownUsageReservation() throws Exception {
        var response = new GeminiAdapter.Result(null, GeminiAdapter.Failure.SLOTQ_REJECTION, 0, 0,
            new GeminiAdapter.Usage(null, null, null, null, "unavailable"), "unavailable", null, null,
            "terminal_slotq_rejection");
        verifySettlement(response, 0, 0, false);
    }

    @Test
    void measuredUsageRemainsMeasuredEvenWhenTheResponseIsMalformed() throws Exception {
        var response = new GeminiAdapter.Result(null, GeminiAdapter.Failure.MALFORMED, 1, 1,
            new GeminiAdapter.Usage(100L, 20L, 0L, 120L, "measured"), "estimated", "synthetic-version", 200,
            "terminal_unavailable");
        verifySettlement(response, 1, 120, false);
    }

    private void verifySettlement(GeminiAdapter.Result response, int expectedCalls, long expectedTokens,
                                  boolean unknownUsage) throws Exception {
        var first = M8ModelQualificationTests.CELL;
        var second = new M8Evaluation.Cell(first.caseId(), first.caseVersion(), 2, first.model(), first.lane());
        var cells = List.of(first, second);
        var plan = M8ModelQualificationTests.plan(cells);
        var adapter = mock(GeminiAdapter.class);
        when(adapter.generate(anyString(), any(), any(), any())).thenReturn(response);
        var provider = new M8ActualProvider(plan, adapter);
        String runId = "scorer-provider-settlement-" + UUID.randomUUID();
        var session = new M8Evaluation(runId, cells, M8ModelQualificationTests.manifest(plan),
            M8ModelQualification.budget(plan),
            cell -> M8Bindings.create(cell, runId, Instant.parse("2026-10-20T00:00:00Z"), Map.of()), null, plan);
        var budget = new ProviderBudget(Clock.systemUTC(), Instant.now().plusSeconds(45), 1, 35000);
        var returned = new AtomicReference<GeminiAdapter.Result>();
        session.execute((cell, input, recorder) -> {
            provider.enter(cell, recorder);
            var request = new ProviderProtocol.Request("합성 정산 회귀 입력", 1024);
            var classification = new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC, true, true,
                null, null, 40000, 1024);
            returned.set(provider.generate(cell.model(), classification, request, budget));
            // Injected attempts never establish actual-provider provenance.
            recorder.put("result", Map.of("observationKind", "SCORER_TEST_VECTOR", "providerCalls", response.attempts(),
                "tokens", response.usage().total() == null ? 0 : response.usage().total(), "failure", response.failure().name()));
        });
        assertThat(returned.get()).isSameAs(response);
        assertThat(budget.usageUnknown()).isEqualTo(unknownUsage);
        assertThat(budget.inFlight()).isFalse();
        var completion = JSON.readTree(Files.readAllBytes(session.directory().resolve("completion.json")));
        assertThat(completion.path("executed").asInt()).isEqualTo(1);
        assertThat(completion.path("providerCalls").asInt()).isEqualTo(expectedCalls);
        assertThat(completion.path("tokens").asLong()).isEqualTo(expectedTokens);
        assertThat(completion.path("stopPoint").asString()).isEqualTo("PROVIDER_STOP");
        var journal = JSON.readTree(Files.readAllBytes(session.directory().resolve("cells/0/provider-call-a.json")));
        assertThat(journal.path("result").path("attempts").asInt()).isEqualTo(expectedCalls);
        assertThat(journal.path("result").path("failure").asString()).isEqualTo(response.failure().name());
        assertThat(journal.path("result").path("httpStatus").asInt(-1)).isEqualTo(response.httpStatus() == null ? -1 : response.httpStatus());
        if (unknownUsage) assertThat(journal.path("result").path("usage").path("total").isNull()).isTrue();
        verify(adapter, times(1)).generate(anyString(), any(), any(), any());
        assertThat(Files.exists(session.directory().resolve("cells/1/record.json"))).isFalse();
    }
}
