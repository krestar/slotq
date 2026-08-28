package com.slotq;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "slotq.auth.dev-bootstrap-enabled=false",
    "slotq.cors.allowed-origins=https://slotq.example"
})
@ActiveProfiles("production")
@AutoConfigureMockMvc
class ProductionAuthIntegrationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void productionHasNoBootstrapOrDevResolverAndRejectsDevCredentials() throws Exception {
        assertThat(applicationContext.getBeansOfType(com.slotq.auth.web.BearerCredentialResolver.class))
            .isEmpty();
        mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"customer-a\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/production-protected")
                .header("Authorization", "Bearer runtime-dev-token"))
            .andExpect(status().isUnauthorized());
    }
}
