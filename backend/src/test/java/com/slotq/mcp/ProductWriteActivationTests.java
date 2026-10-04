package com.slotq.mcp;

import com.slotq.auth.access.ProductCredentialAccess;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.integration.mcp.product.ProductHttpClient;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductWriteActivationTests {
    @Test void unsafeAcquisitionSocketConnectionTransactionReconnectAndMultiHostProfilesAreRejectedBeforeDbUse() {
        for(String fault:new String[]{"acquisition","socket","connect","transaction","reconnect","poolReconnect","urlOverride","multiHost"}) {
            try(var pool=new HikariDataSource()) {
                pool.setJdbcUrl("jdbc:mysql://localhost:3306/slotq");pool.setConnectionTimeout(2000);
                pool.addDataSourceProperty("socketTimeout","2000");pool.addDataSourceProperty("connectTimeout","2000");
                var tx=new DataSourceTransactionManager(pool);tx.setDefaultTimeout(10);
                switch(fault) {
                    case "acquisition"->pool.setConnectionTimeout(30000);
                    case "socket"->pool.addDataSourceProperty("socketTimeout","0");
                    case "connect"->pool.addDataSourceProperty("connectTimeout","0");
                    case "transaction"->tx.setDefaultTimeout(-1);
                    case "reconnect"->pool.addDataSourceProperty("autoReconnect","true");
                    case "poolReconnect"->pool.addDataSourceProperty("autoReconnectForPools","true");
                    case "urlOverride"->pool.setJdbcUrl("jdbc:mysql://localhost:3306/slotq?socketTimeout=0");
                    case "multiHost"->pool.setJdbcUrl("jdbc:mysql://localhost:3306,localhost:3307/slotq");
                }
                var authority=new ActorAccessService(new JdbcTemplate(pool),mock(AuthorizationUseCase.class),Clock.systemUTC(),tx);
                assertThatThrownBy(authority::requireBoundedExecution).as(fault).isInstanceOf(IllegalStateException.class);
                assertThat(pool.getHikariPoolMXBean()).isNull();
            }
        }
    }
    @Test void httpRedirectProfileCannotActivate() {
        assertThatThrownBy(()->new ProductHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),mock(ProductCredentialAccess.class)))
            .isInstanceOf(IllegalStateException.class);
    }
}
