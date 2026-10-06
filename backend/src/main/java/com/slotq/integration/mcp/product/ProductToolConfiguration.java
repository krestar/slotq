package com.slotq.integration.mcp.product;

import com.slotq.auth.access.*;
import com.slotq.mcp.*;
import java.net.http.HttpClient;
import java.time.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import javax.net.ssl.SSLContext;
import com.slotq.knowledge.application.CorpusCatalog;
import com.slotq.integration.mcp.knowledge.KnowledgeSearch;
import com.slotq.integration.mcp.knowledge.LexicalSearch;

/** Single explicit composition root; synthetic test roots do not become production registrations. */
@AutoConfiguration
@ConditionalOnProperty(name="slotq.mcp.enabled",havingValue="true")
@ConditionalOnProperty(name="slotq.events.runtime-role",havingValue="product",matchIfMissing=true)
@ConditionalOnMissingBean(McpRegistrations.class)
public class ProductToolConfiguration {
    @Bean HoldApprovals mcpHoldApprovals(ActorAccess authority,JdbcTemplate jdbc,PlatformTransactionManager tx,Clock clock){
        return new HoldApprovals(authority,jdbc,tx,clock);
    }
    @Bean ProductHttpClient mcpProductClient(ProductCredentialAccess credentials,ObjectProvider<SSLContext> trust) throws Exception {
        credentials.requireBoundedExecution();
        return new ProductHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(ProductHttpBinding.CONNECT_MAX).sslContext(trust.getIfAvailable(()->{
                try{return SSLContext.getDefault();}catch(Exception unavailable){throw new IllegalStateException("Product TLS trust unavailable");}
            })).build(),credentials);
    }
    @Bean McpRegistrations mcpProductTools(ProductHttpBinding binding,ProductHttpClient client,HoldApprovals approvals,
            CorpusCatalog catalog,ActorAccess authority,Clock clock){
        var product=new ProductTools(binding,client,approvals);
        var knowledge=new KnowledgeSearch(catalog,authority,new LexicalSearch(clock),clock);
        return ()->java.util.stream.Stream.concat(product.tools().stream(),java.util.stream.Stream.of(knowledge.tool())).toList();
    }
}
