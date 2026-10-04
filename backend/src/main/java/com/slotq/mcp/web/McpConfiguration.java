package com.slotq.mcp.web;

import com.slotq.auth.access.ActorAccess;
import com.slotq.auth.access.ProductCredentialAccess;
import com.slotq.mcp.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.*;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="slotq.mcp.enabled",havingValue="true")
@ConditionalOnProperty(name="slotq.events.runtime-role",havingValue="product",matchIfMissing=true)
public class McpConfiguration {
    @Bean ProductHttpBinding mcpProductBinding(ActorAccess authority,ProductCredentialAccess credentials,Clock clock,
            @Value("${slotq.mcp.product-origin}") String origin,@Value("${server.port:8080}") int port) {
        return new ProductHttpBinding(URI.create(origin),port,authority,credentials,clock);
    }
    @Bean ToolRegistry mcpRegistry(ObjectProvider<McpRegistrations> root) {
        return new ToolRegistry(root.getIfAvailable(()->List::of).tools());
    }
    @Bean LocalAdmission mcpAdmission(@Value("${slotq.mcp.quota.rate:5}") double rate,
            @Value("${slotq.mcp.quota.burst:20}") int burst,@Value("${slotq.mcp.quota.concurrency:4}") int concurrency,
            @Value("${slotq.mcp.quota.cardinality:4096}") int cardinality) {
        return new LocalAdmission(new LocalAdmission.Limit(rate,burst,concurrency),cardinality,System::nanoTime);
    }
    @Bean(destroyMethod="close") McpAudit mcpAudit(MeterRegistry meters,Clock clock,
            @Value("${slotq.mcp.audit.capacity:256}") int capacity,@Value("${slotq.mcp.audit.directory:}") String directory,
            @Value("${slotq.mcp.audit.retention-days:7}") int retention,@Value("${slotq.mcp.audit.daily-bytes:1048576}") long dailyBytes) {
        var json=JsonMapper.builder().build();var logger=LoggerFactory.getLogger("slotq.mcp.audit");
        java.util.function.Consumer<McpAudit.Event> sink=directory.isBlank()
            ?event->logger.info(json.writeValueAsString(event))
            :new MetadataFileSink(java.nio.file.Path.of(directory),clock,retention,dailyBytes);
        var audit=new McpAudit(capacity,sink);
        meters.gauge("slotq.mcp.audit.dropped",audit,McpAudit::dropped);
        meters.gauge("slotq.mcp.audit.failed",audit,McpAudit::failed);
        meters.gauge("slotq.mcp.audit.delivered",audit,McpAudit::delivered);
        return audit;
    }
    @Bean(destroyMethod="close") McpEngine mcpEngine(ActorAccess authority,ToolRegistry registry,LocalAdmission admission,
            McpAudit audit,Clock clock,@Value("${slotq.mcp.handler-budget:PT30S}") Duration budget,
            @Value("${slotq.mcp.workers:8}") int workers) {
        return new McpEngine(authority,registry,admission,audit,clock,budget,workers);
    }
    @Bean ServletRegistrationBean<McpServlet> mcpServlet(ActorAccess authority,McpEngine engine,Clock clock,
            @Value("${slotq.mcp.origin}") String origin,@Value("${slotq.mcp.ingress:8}") int ingress,
            @Value("${slotq.mcp.sessions:512}") int sessions,@Value("${slotq.mcp.handler-budget:PT30S}") Duration budget) {
        URI uri=URI.create(origin);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null
            || uri.getFragment()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty()))
            throw new IllegalArgumentException("Exact HTTPS MCP origin required");
        var registration=new ServletRegistrationBean<>(new McpServlet(authority,engine,clock,origin,ingress,sessions,budget),"/mcp");
        registration.setAsyncSupported(true);registration.setLoadOnStartup(1);return registration;
    }
}
