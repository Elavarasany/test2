package nmk_backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(1024);

    @Test
    void rejectsBodyLargerThanConfiguredLimitBeforeControllerChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/checkout");
        request.setContent(new byte[1025]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void enforcesLimitEvenWhenContentLengthIsUnknown() throws Exception {
        byte[] oversized = new byte[1025];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/checkout") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(oversized);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void allowsBoundedMultipartPrescriptionUploadAboveJsonLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prescriptions/upload");
        request.setContent(new byte[2 * 1024 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(200, response.getStatus());
        assertTrue(chainCalled.get());
    }

    @Test
    void stillRejectsPrescriptionMultipartBodyAboveSixMegabytes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/prescriptions/upload");
        request.setContent(new byte[(6 * 1024 * 1024) + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void replaysAcceptedBodyForControllerDeserialization() throws Exception {
        byte[] payload = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/marketing/offers");
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> received = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                received.set(((HttpServletRequest) req).getInputStream().readAllBytes()));

        assertEquals(200, response.getStatus());
        assertArrayEquals(payload, received.get());
    }

    @Test
    void doesNotBufferReadOnlyRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
    }
}
