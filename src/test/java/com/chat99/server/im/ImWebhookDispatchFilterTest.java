package com.chat99.server.im;

import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class ImWebhookDispatchFilterTest {

    @Mock
    private FilterChain chain;

    @Test
    void webhookPathStaysOnCallerThread() throws Exception {
        ImWebhookDispatchFilter filter = new ImWebhookDispatchFilter(
            new ImWebhookDispatchProperties(true, 8, 32, 200, 5000),
            command -> {
                throw new AssertionError("executor should not run");
            });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/im/message");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void alreadyOnPoolRunsOnCallerThread() throws Exception {
        ImWebhookDispatchFilter filter = new ImWebhookDispatchFilter(
            new ImWebhookDispatchProperties(true, 8, 32, 200, 5000),
            command -> {
                throw new AssertionError("executor should not run");
            });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/im/message");
        request.setAttribute(ImWebhookDispatchFilter.ON_POOL, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void disabledRunsOnCallerThread() throws Exception {
        ImWebhookDispatchFilter filter = new ImWebhookDispatchFilter(
            new ImWebhookDispatchProperties(false, 8, 32, 200, 5000),
            command -> {
                throw new AssertionError("executor should not run");
            });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/im/message");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
