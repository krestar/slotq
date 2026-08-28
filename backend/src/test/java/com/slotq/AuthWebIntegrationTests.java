package com.slotq;

import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "slotq.auth.dev-bootstrap-enabled=true",
    "slotq.cors.allowed-origins=http://localhost:5173"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AuthWebIntegrationTests.ProtectedTestEndpoints.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthWebIntegrationTests {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"");
    private static final PrincipalId MANAGER_PRINCIPAL = new PrincipalId(
        UUID.fromString("10000000-0000-0000-0000-000000000004")
    );
    private static final PrincipalId STAFF_PRINCIPAL = new PrincipalId(
        UUID.fromString("10000000-0000-0000-0000-000000000005")
    );

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired
    MockMvc mockMvc;

    @LocalServerPort
    int serverPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TenantUseCase tenantUseCase;

    @Autowired
    VenueConfigurationUseCase venueUseCase;

    @Autowired
    AccessControlProvisioning accessControlProvisioning;

    private Venue managedVenue;
    private Venue unassignedVenue;

    @BeforeEach
    void configureStoredManagerScope() {
        Tenant tenant = tenantUseCase.createTenant();
        managedVenue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "UTC", WeeklyOperatingHours.closedAllWeek(), new BookingPolicyTerms(30, 5, 0, 0)
        ));
        unassignedVenue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "UTC", WeeklyOperatingHours.closedAllWeek(), new BookingPolicyTerms(30, 5, 0, 0)
        ));
        accessControlProvisioning.registerPrincipal(MANAGER_PRINCIPAL);
        accessControlProvisioning.registerPrincipal(STAFF_PRINCIPAL);
        accessControlProvisioning.assignMembership(MANAGER_PRINCIPAL, tenant.id(), TenantRole.MANAGER);
        accessControlProvisioning.assignMembership(STAFF_PRINCIPAL, tenant.id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(
            MANAGER_PRINCIPAL, tenant.id(), TenantRole.MANAGER, managedVenue.id()
        );
        accessControlProvisioning.grantVenue(
            STAFF_PRINCIPAL, tenant.id(), TenantRole.STAFF, managedVenue.id()
        );
    }

    @Test
    void bootstrapReturnsNoStoreOpaqueBearerAndCredentialAuthenticatesProtectedCalls(CapturedOutput output)
        throws Exception {
        String customerToken = bootstrap("customer-a");
        String managerToken = bootstrap("tenant-a-manager");

        assertThat(customerToken).hasSizeGreaterThanOrEqualTo(43).doesNotContain("customer-a");
        mockMvc.perform(get("/__test/customer").header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/__test/venues/{venueId}", managedVenue.id().value())
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/__test/venues/{venueId}", unassignedVenue.id().value())
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isNotFound());

        String staffToken = bootstrap("tenant-a-staff");
        mockMvc.perform(get("/__test/venues/{venueId}/configuration", managedVenue.id().value())
                .header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden());

        Integer credentialColumns = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() "
                + "AND LOWER(column_name) REGEXP 'token|credential|secret'",
            Integer.class
        );
        assertThat(credentialColumns).isZero();
        assertThat(output).doesNotContain(customerToken).doesNotContain(managerToken).doesNotContain(staffToken);
    }

    @Test
    void bootstrapRejectsUnknownFixtureAndEveryIdentityOrScopeClaim() throws Exception {
        mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"unknown\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        for (String forbidden : new String[] {"principalId", "tenantId", "role", "venueGrant"}) {
            mockMvc.perform(post("/__dev/auth/session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fixtureKey\":\"customer-a\",\"" + forbidden + "\":\"forged\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void realHttpServerAcceptsTheProcessCredentialInsideTheSecurityChain() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> bootstrap = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/__dev/auth/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"fixtureKey\":\"customer-a\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        Matcher matcher = ACCESS_TOKEN.matcher(bootstrap.body());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        assertThat(matcher.find()).isTrue();

        HttpResponse<Void> protectedResponse = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/__dev/auth/session"))
                .header("Authorization", "Bearer " + matcher.group(1))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding()
        );
        assertThat(protectedResponse.statusCode()).isEqualTo(204);
    }

    @Test
    void publicAnonymousProtectedUnauthorizedAndCorsAllowDenyAreSeparated() throws Exception {
        mockMvc.perform(get("/__test/customer"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/venues"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/venues/{venueId}", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/venues/{venueId}/availability", UUID.randomUUID()))
            .andExpect(status().isNotFound());

        mockMvc.perform(options("/__test/customer")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mockMvc.perform(options("/__test/customer")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private String bootstrap(String fixtureKey) throws Exception {
        String body = mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"" + fixtureKey + "\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn().getResponse().getContentAsString();
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @TestConfiguration
    @RestController
    static class ProtectedTestEndpoints {

        private final AuthorizationUseCase authorizationUseCase;

        ProtectedTestEndpoints(AuthorizationUseCase authorizationUseCase) {
            this.authorizationUseCase = authorizationUseCase;
        }

        @GetMapping("/__test/customer")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void customer(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
            if (principal == null) {
                throw new IllegalStateException();
            }
        }

        @GetMapping("/__test/venues/{venueId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void operator(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID venueId
        ) {
            authorizationUseCase.requireVenueAccess(principal, new com.slotq.venue.domain.VenueId(venueId));
        }

        @GetMapping("/__test/venues/{venueId}/configuration")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void configuration(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID venueId
        ) {
            authorizationUseCase.requireVenueConfigurationAccess(
                principal,
                new com.slotq.venue.domain.VenueId(venueId)
            );
        }

        @ExceptionHandler(ResourceNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        void hiddenNotFound() {
        }

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void forbidden() {
        }
    }
}
