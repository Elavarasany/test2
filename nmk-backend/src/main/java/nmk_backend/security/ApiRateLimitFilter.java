package nmk_backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nmk_backend.service.ApiRateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final ApiRateLimitService rateLimitService;
    private final int loginLimit;
    private final int appointmentLimit;
    private final int checkoutLimit;
    private final int verificationLimit;
    private final int trackingLimit;
    private final int windowSeconds;

    public ApiRateLimitFilter(
            ApiRateLimitService rateLimitService,
            @Value("${app.rate-limit.login-per-minute:5}") int loginLimit,
            @Value("${app.rate-limit.appointment-per-minute:5}") int appointmentLimit,
            @Value("${app.rate-limit.checkout-per-minute:10}") int checkoutLimit,
            @Value("${app.rate-limit.payment-verify-per-minute:15}") int verificationLimit,
            @Value("${app.rate-limit.tracking-per-minute:30}") int trackingLimit,
            @Value("${app.rate-limit.window-seconds:60}") int windowSeconds
    ) {
        this.rateLimitService = rateLimitService;
        this.loginLimit = loginLimit;
        this.appointmentLimit = appointmentLimit;
        this.checkoutLimit = checkoutLimit;
        this.verificationLimit = verificationLimit;
        this.trackingLimit = trackingLimit;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitService.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        Rule rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Uses the socket peer address. Configure the trusted reverse proxy to
            // supply the real remote address; never trust arbitrary X-Forwarded-For.
            var decision = rateLimitService.increment(
                    rule.name(), request.getRemoteAddr(), rule.limit(), windowSeconds
            );
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"message\":\"Too many requests. Please wait before trying again.\","
                                + "\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}"
                );
                return;
            }
        } catch (RuntimeException rateLimitFailure) {
            log.error("API rate limit store is unavailable for route {}", rule.name(), rateLimitFailure);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Request protection is temporarily unavailable. Please retry shortly.\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Rule ruleFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("POST".equals(method) && "/api/admin/auth/login".equals(path)) {
            return new Rule("admin-login", loginLimit);
        }
        if ("POST".equals(method)
                && ("/api/admin/auth/authorize-action".equals(path)
                || "/api/admin/auth/verify-password".equals(path))) {
            return new Rule("admin-password-authorization", loginLimit);
        }
        if ("POST".equals(method) && "/api/appointments".equals(path)) {
            return new Rule("appointment-booking", appointmentLimit);
        }
        if ("POST".equals(method) && path.matches("/api/admin/orders/[0-9]+/refunds")) {
            return new Rule("razorpay-refund-initiation", 5);
        }

        if ("POST".equals(method) && "/api/admin/pos/payments/settlements/sync".equals(path)) {
            return new Rule("razorpay-settlement-sync", 3);
        }
        if ("POST".equals(method) && "/api/orders/checkout".equals(path)) {
            return new Rule("order-checkout", checkoutLimit);
        }
        if ("POST".equals(method) && "/api/prescriptions/upload".equals(path)) {
            return new Rule("prescription-upload", 6);
        }
        if ("POST".equals(method) && "/api/payment/verify".equals(path)) {
            return new Rule("payment-verification", verificationLimit);
        }
        if ("GET".equals(method) && path != null && path.startsWith("/api/orders/track/")) {
            return new Rule("public-order-tracking", trackingLimit);
        }
        // Provider webhooks are deliberately excluded to preserve legitimate retries.
        return null;
    }

    private record Rule(String name, int limit) {}
}
