package com.slotq.mcp;

import com.slotq.mcp.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class McpActivationTests {
    @Test void arbitraryComponentBeanDoesNotRegisterAToolInProductionComposition() {
        new WebApplicationContextRunner().withUserConfiguration(McpConfiguration.class)
            .withInitializer(context->context.getBeanFactory().setConversionService(
                org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withPropertyValues("slotq.mcp.enabled=true","slotq.mcp.origin=https://localhost:8443",
                "slotq.mcp.product-origin=https://localhost:8443","server.port=8443")
            .withBean(java.time.Clock.class,java.time.Clock::systemUTC)
            .withBean(com.slotq.auth.access.ActorAccess.class,()->McpFoundationTests.authority())
            .withBean(com.slotq.auth.access.ProductCredentialAccess.class,()->org.mockito.Mockito.mock(com.slotq.auth.access.ProductCredentialAccess.class))
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withBean(ToolDefinition.class,()->McpFoundationTests.tool("test.accidental",com.slotq.auth.access.AccessProfile.CUSTOMER,(c,i)->null))
            .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(ToolRegistry.class).all()).isEmpty();});
    }
    @Test void defaultRuntimeAndAllIsolatedRolesHaveNoServletEngineOrExposure() throws Exception {
        for(String role:new String[]{"product","relay","consumer","quiesced"}) {
            boolean enabled=!role.equals("product");
            new WebApplicationContextRunner().withUserConfiguration(McpConfiguration.class,McpTopologyConfiguration.class)
                .withPropertyValues("slotq.mcp.enabled="+enabled,"slotq.events.runtime-role="+role)
                .run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(McpEngine.class).doesNotHaveBean(McpServlet.class);});
            var filter=new McpExposureFilter(enabled,role);
            var request=new MockHttpServletRequest("POST","/mcp");var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->{throw new AssertionError("Inactive MCP reached security/controller");});
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }
    @Test void activeRuntimeRejectsUnexpectedMcpSubpaths() throws Exception {
        var filter=new McpExposureFilter(true,"product");var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/mcp/auto-discovered-tool"),response,(r,s)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(404);
    }
    @Test void unsafeTlsAuthorityOrBudgetConfigurationCannotActivate() {
        new WebApplicationContextRunner().withUserConfiguration(McpTopologyConfiguration.class)
            .withPropertyValues("slotq.mcp.enabled=true","server.ssl.enabled=false")
            .withBean(javax.sql.DataSource.class,()->new com.zaxxer.hikari.HikariDataSource())
            .run(context->assertThat(context).hasFailed());
        assertThatThrownBy(()->new McpEngine(null,null,null,null,java.time.Clock.systemUTC(),java.time.Duration.ofSeconds(31),1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ToolRegistry(java.util.List.of(McpFoundationTests.tool("test.invalid",com.slotq.auth.access.AccessProfile.CUSTOMER,(c,i)->null),
            McpFoundationTests.tool("test.invalid",com.slotq.auth.access.AccessProfile.CUSTOMER,(c,i)->null))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
