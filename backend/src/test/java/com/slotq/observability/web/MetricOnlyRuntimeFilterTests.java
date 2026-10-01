package com.slotq.observability.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class MetricOnlyRuntimeFilterTests {
    @Test void isolatedRolesRejectProductRecoveryDiagnosticsAndEncodedAlternativePaths() throws Exception {
        for(String role:new String[]{"consumer","relay"}) for(String path:new String[]{"/api/v1/venues","/internal/operations/tenants/x","/actuator/env","/actuator/prometheus/","/actuator%2fprometheus","/error"}) {
            var chain=new MockFilterChain();var response=new MockHttpServletResponse();
            new MetricOnlyRuntimeFilter(role).doFilter(new MockHttpServletRequest("GET",path),response,chain);
            assertThat(response.getStatus()).isEqualTo(404);assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void exactGetScrapeStillPassesThroughAuthenticationAndProductRoleRetainsApi() throws Exception {
        var chain=new MockFilterChain();var filter=new MetricOnlyRuntimeFilter("consumer");
        filter.doFilter(new MockHttpServletRequest("GET","/actuator/prometheus"),new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isNotNull();
        chain=new MockFilterChain();var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/actuator/prometheus"),response,chain);
        assertThat(response.getStatus()).isEqualTo(404);assertThat(chain.getRequest()).isNull();
        chain=new MockFilterChain();new MetricOnlyRuntimeFilter("product").doFilter(new MockHttpServletRequest("GET","/api/v1/venues"),new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
