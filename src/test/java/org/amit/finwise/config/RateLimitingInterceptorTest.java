package org.amit.finwise.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies per-user rate limiting: the (limit+1)th request within the refresh
 * window receives 429 Too Many Requests.
 */
class RateLimitingInterceptorTest {

    private RateLimitingInterceptor interceptor;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setLimitForPeriod(3);
        props.setLimitRefreshPeriodSeconds(60);
        props.setTimeoutMs(0);
        interceptor = new RateLimitingInterceptor(props);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "testuser", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void requestsWithinLimitAreAllowed() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cfo/chat");
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(request, response, new Object());
            assertThat(allowed).as("Request %d should be allowed", i + 1).isTrue();
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void requestBeyondLimitIsRejectedWith429() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cfo/chat");
            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(request, response, new Object());
        }

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cfo/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void differentUsersHaveIndependentBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/cfo/chat");
            interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
        }

        UsernamePasswordAuthenticationToken otherAuth = new UsernamePasswordAuthenticationToken(
                "otheruser", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(otherAuth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cfo/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).as("Different user should have a fresh bucket").isTrue();
    }
}
