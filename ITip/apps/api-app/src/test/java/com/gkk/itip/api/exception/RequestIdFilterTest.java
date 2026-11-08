package com.gkk.itip.api.exception;

import com.gkk.itip.common.web.RequestIdFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdAndExposesTheSameValueInHeaderAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "untrusted-client-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("otherContext", "keep");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String requestId = MDC.get("requestId");
            assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId);
            assertThat(requestId).isNotEqualTo("untrusted-client-value");
        });

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("keep");
    }

    @Test
    void separateRequestsReceiveDifferentIds() throws Exception {
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), firstResponse, (request, response) -> {});
        filter.doFilter(new MockHttpServletRequest(), secondResponse, (request, response) -> {});

        assertThat(firstResponse.getHeader("X-Request-Id"))
                .isNotEqualTo(secondResponse.getHeader("X-Request-Id"));
    }

    @Test
    void clearsRequestIdWhenTheChainThrows() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response,
                (request, servletResponse) -> {
                    assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
                    throw new ServletException("request failed");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
    }
}
