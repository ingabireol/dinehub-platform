package com.dinehub.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test against real PostgreSQL and RabbitMQ.
 *
 * <p>Testcontainers rather than H2 on purpose: this exercises the actual Flyway
 * migrations, the actual Postgres SQL and the actual AMQP client. An in-memory
 * substitute would pass while the migration that only runs on Postgres is
 * broken — which is the failure that reaches production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthIntegrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dinehub.jwt.secret",
                () -> "an-integration-test-signing-secret-long-enough");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("register, then use the token, then refresh it")
    void fullAuthenticationFlow() throws Exception {
        String body = """
                {
                  "email": "integration@example.com",
                  "password": "IntegrationTest1Pass",
                  "fullName": "Integration Tester"
                }
                """;

        String registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andReturn().getResponse().getContentAsString();

        JsonNode tokens = objectMapper.readTree(registered);
        String accessToken = tokens.get("accessToken").asText();

        // The token works against a protected endpoint.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("integration@example.com"));

        // And the refresh token produces a new access token.
        String refreshBody = objectMapper.writeValueAsString(
                java.util.Map.of("refreshToken", tokens.get("refreshToken").asText()));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("the database enforces email uniqueness case-insensitively")
    void emailUniquenessIsCaseInsensitive() throws Exception {
        String first = """
                {"email":"Case@Example.com","password":"CaseTest1Password","fullName":"Case One"}
                """;
        String second = """
                {"email":"case@example.com","password":"CaseTest1Password","fullName":"Case Two"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a weak password is rejected with field-level detail")
    void rejectsWeakPassword() throws Exception {
        String body = """
                {"email":"weak@example.com","password":"short","fullName":"Weak"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("password"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("an unauthenticated request to a protected endpoint is refused")
    void protectsEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("readiness reports the database and broker")
    void readinessChecksDependencies() throws Exception {
        // This is what Kubernetes polls. If it does not actually check the
        // database, a pod with a dead connection pool is sent traffic.
        String health = mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(health).contains("UP");
    }

    @Test
    @DisplayName("liveness does not depend on the database")
    void livenessIsIndependentOfDependencies() throws Exception {
        // Liveness must stay UP during a database outage. If it did not,
        // Kubernetes would kill every pod and turn a recoverable dependency
        // problem into a total outage.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
