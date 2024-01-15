package com.dinehub.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MenuIntegrationIT {

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
    private ApplicationContext context;

    @Test
    @DisplayName("the application context starts with every bean wired")
    void contextLoads() {
        // Deliberately explicit rather than relying on the other tests to notice.
        // A unit test constructs a service with mocks and therefore proves
        // nothing about whether Spring can actually wire it — a repository
        // interface nested inside a class compiles, unit-tests green, and then
        // fails at startup with "no qualifying bean". This is the test that
        // catches that class of problem.
        assertThat(context.getBean(com.dinehub.menu.service.MenuService.class)).isNotNull();
        assertThat(context.getBean(
                com.dinehub.menu.repository.CategoryRepository.class)).isNotNull();
        assertThat(context.getBean(
                com.dinehub.menu.repository.MenuItemRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("the seeded menu is readable without authentication")
    void menuIsPubliclyReadable() throws Exception {
        // A customer browses before logging in. Requiring a token to see a menu
        // would be a strange shop.
        mockMvc.perform(get("/api/v1/menu/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").isNotEmpty());
    }

    @Test
    @DisplayName("availableOnly excludes items that are sold out")
    void availableOnlyFiltersSoldOutItems() throws Exception {
        String all = mockMvc.perform(get("/api/v1/menu/items"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String available = mockMvc.perform(get("/api/v1/menu/items?availableOnly=true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The seed deliberately includes one unavailable item so this path has
        // something to prove.
        assertThat(all).contains("Chocolate Tart");
        assertThat(available).doesNotContain("Chocolate Tart");
    }

    @Test
    @DisplayName("categories come back in display order")
    void categoriesAreOrdered() throws Exception {
        mockMvc.perform(get("/api/v1/menu/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Starters"))
                .andExpect(jsonPath("$[0].itemCount").value(2));
    }

    @Test
    @DisplayName("creating an item without a token is refused")
    void writesRequireAuthentication() throws Exception {
        String body = """
                {"categoryId":"aaaaaaaa-0000-0000-0000-000000000002","name":"Sneaky",
                 "price":1.00,"preparationMinutes":5}
                """;

        mockMvc.perform(post("/api/v1/menu/items")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the pricing lookup returns the snapshot order-service needs")
    void pricingLookupWorks() throws Exception {
        String body = """
                {"itemIds":["bbbbbbbb-0000-0000-0000-000000000003",
                            "bbbbbbbb-0000-0000-0000-000000000007"]}
                """;

        mockMvc.perform(post("/api/v1/menu/items/pricing")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // The sold-out item is returned with available=false rather than
                // omitted, so order-service can give a specific error.
                .andExpect(jsonPath("$[?(@.name == 'Chocolate Tart')].available").value(false));
    }

    @Test
    @DisplayName("an unknown item id is a 404 with the shared error shape")
    void unknownItemReturnsStructuredError() throws Exception {
        mockMvc.perform(get("/api/v1/menu/items/99999999-9999-9999-9999-999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());
    }

    @Test
    @DisplayName("readiness checks the database and the broker")
    void readinessChecksDependencies() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
