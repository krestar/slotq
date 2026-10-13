package com.slotq.ai.flow;

import com.slotq.ai.router.ModelRouter;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Adopted #165 measurements, including excluded candidates. This is configuration, not fresh flow evidence. */
public final class RepresentativeRouterProfile {
    public static List<ModelRouter.Candidate> candidates() {
        try (var input = RepresentativeRouterProfile.class.getResourceAsStream("/ai/representative-router-v1.json")) {
            var candidates = JsonMapper.builder().build().readValue(input, ModelRouter.Candidate[].class);
            return Arrays.stream(candidates).map(c -> new ModelRouter.Candidate(
                c.measurement().workload() + "." + c.id(), c.provider(), c.model(), c.apiMode(), c.endpoint(),
                c.resolvedVersion(), c.structuredOutput(), c.contextLimit(), c.outputLimit(), c.controls(), c.price(), c.measurement())).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Adopted Router profile unavailable");
        }
    }
    private RepresentativeRouterProfile() { }
}
