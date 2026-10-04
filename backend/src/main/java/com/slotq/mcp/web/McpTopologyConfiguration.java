package com.slotq.mcp.web;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="slotq.mcp.enabled",havingValue="true")
@ConditionalOnProperty(name="slotq.events.runtime-role",havingValue="product",matchIfMissing=true)
public class McpTopologyConfiguration {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> mcpServletBounds(DataSource source,
            @Value("${server.ssl.enabled:false}") boolean tls,@Value("${slotq.mcp.ingress:8}") int ingress) {
        if(!tls || !(source instanceof HikariDataSource pool) || pool.getConnectionTimeout()>2000
                || !boundedSocket(pool.getDataSourceProperties().getProperty("socketTimeout")) || ingress<1 || ingress>32)
            throw new IllegalStateException("MCP requires TLS, bounded authority acquisition/socket and ingress");
        return factory->factory.addConnectorCustomizers(connector->{
            connector.setProperty("maxThreads","64");connector.setProperty("minSpareThreads","16");
            connector.setProperty("maxConnections","128");connector.setProperty("acceptCount","16");
            connector.setProperty("connectionTimeout","2000");connector.setProperty("connectionUploadTimeout","2000");
            connector.setProperty("disableUploadTimeout","false");
        });
    }
    private static boolean boundedSocket(String value) {
        try {int n=Integer.parseInt(value);return n>0 && n<=2000;}catch(RuntimeException invalid){return false;}
    }
}
