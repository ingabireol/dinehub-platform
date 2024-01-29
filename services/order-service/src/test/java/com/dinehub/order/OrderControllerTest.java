package com.dinehub.order;

import com.dinehub.common.security.Roles;
import com.dinehub.order.dto.OrderDtos;
import com.dinehub.order.service.OrderService;
import com.dinehub.order.web.OrderController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests at the HTTP boundary.
 *
 * <p>Standalone MockMvc rather than a full Spring context: this is checking the
 * request mapping, binding, validation and status codes, and does not need a
 * database to do it. The security rules are covered by the integration test,
 * where they are actually wired.
 */
class OrderControllerTest {

    private OrderService orderService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        customerId = UUID.randomUUID();

        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new com.dinehub.common.api.GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation
                                .AuthenticationPrincipalArgumentResolver(),
                        new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .build();
    }

    private OrderDtos.OrderResponse sampleOrder(UUID id) {
        return new OrderDtos.OrderResponse(
                id, customerId, "c@dinehub.local", "PLACED", new BigDecimal("28.00"),
                "12 Example Street", null, null, Instant.now(), Instant.now(),
                List.of(new OrderDtos.OrderLineResponse(UUID.randomUUID(), "Tilapia",
                        new BigDecimal("12.50"), 2, new BigDecimal("25.00"))));
    }

    @Test
    @DisplayName("placing an order returns 201 with the created order")
    void placeOrderReturnsCreated() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.placeOrder(any(), anyString(), any())).thenReturn(sampleOrder(orderId));

        String body = objectMapper.writeValueAsString(new OrderDtos.CreateOrderRequest(
                List.of(new OrderDtos.OrderLineRequest(UUID.randomUUID(), 2)),
                "12 Example Street", null));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Email", "c@dinehub.local")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(28.00));
    }

    @Test
    @DisplayName("an empty basket is rejected before it reaches the service")
    void rejectsEmptyBasket() throws Exception {
        String body = """
                {"items": [], "deliveryAddress": "12 Example Street"}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("items"));

        verify(orderService, org.mockito.Mockito.never()).placeOrder(any(), anyString(), any());
    }

    @Test
    @DisplayName("a zero quantity is rejected with a field-level message")
    void rejectsZeroQuantity() throws Exception {
        String body = """
                {"items": [{"menuItemId": "%s", "quantity": 0}]}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].message").value("quantity must be at least 1"));
    }

    @Test
    @DisplayName("an absurd quantity is rejected")
    void rejectsExcessiveQuantity() throws Exception {
        // Unbounded quantity is both a pricing accident waiting to happen and a
        // trivial way to make the kitchen queue useless.
        String body = """
                {"items": [{"menuItemId": "%s", "quantity": 99999}]}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("fetching one order passes the requester's role through")
    void getOrderPassesRole() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(eq(orderId), any(), eq(Roles.KITCHEN)))
                .thenReturn(sampleOrder(orderId));

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header("X-User-Role", Roles.KITCHEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()));

        verify(orderService).getOrder(eq(orderId), any(), eq(Roles.KITCHEN));
    }

    @Test
    @DisplayName("the role defaults to CUSTOMER when the gateway sent no header")
    void defaultsToCustomerRole() throws Exception {
        // Defaulting to the least-privileged role matters: defaulting to ADMIN
        // would turn a missing header into privilege escalation.
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(any(), any(), eq(Roles.CUSTOMER)))
                .thenReturn(sampleOrder(orderId));

        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isOk());

        verify(orderService).getOrder(eq(orderId), any(), eq(Roles.CUSTOMER));
    }

    @Test
    @DisplayName("an oversized page request is capped")
    void capsPageSize() throws Exception {
        // An unbounded page size is a trivial way to make the service load every
        // order it has ever taken into memory.
        when(orderService.listCustomerOrders(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/orders/mine?size=100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(orderService).listCustomerOrders(any(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize())
                .isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("cancelling without a body is allowed")
    void cancelWithoutBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.cancelOrder(eq(orderId), any(), anyString(), any()))
                .thenReturn(sampleOrder(orderId));

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
                .andExpect(status().isOk());
    }
}
