package com.dinehub.common.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("generates a trace id when the caller supplies none")
    void generatesTraceId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/menu/items");
        var response = new MockHttpServletResponse();

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isNotNull().hasSize(16);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seen.get());
    }

    @Test
    @DisplayName("reuses the caller's trace id so one id spans every hop")
    void reusesIncomingTraceId() throws Exception {
        // This is what makes "show me everything that happened to this request"
        // a single query rather than a reconstruction.
        var request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(TraceIdFilter.HEADER, "upstream-trace-id");
        var response = new MockHttpServletResponse();

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("upstream-trace-id");
    }

    @Test
    @DisplayName("echoes the trace id back so a user can quote it")
    void echoesTraceIdInResponse() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/menu/items");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("clears the MDC afterwards so a pooled thread does not leak it")
    void clearsMdcAfterRequest() throws Exception {
        // Threads are pooled. A value left behind stamps the next, unrelated
        // request with the previous request's id.
        var request = new MockHttpServletRequest("GET", "/api/v1/menu/items");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("clears the MDC even when the request throws")
    void clearsMdcOnException() {
        var request = new MockHttpServletRequest("GET", "/api/v1/boom");
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(request, new MockHttpServletResponse(), exploding);
        } catch (Exception expected) {
            // the filter must still have cleaned up
        }

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("a blank incoming trace id is replaced rather than propagated")
    void replacesBlankTraceId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(TraceIdFilter.HEADER, "   ");
        var response = new MockHttpServletResponse();

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seen.get()).isNotBlank().doesNotContain(" ");
    }
}
