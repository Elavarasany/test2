package nmk_backend.config;

import nmk_backend.security.JwtAuthenticationFilter;
import nmk_backend.security.AdminSecurityRejectionAuditFilter;
import nmk_backend.security.ApiRateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AdminSecurityRejectionAuditFilter adminSecurityRejectionAuditFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    @Value("${app.cors.require-https:false}")
    private boolean requireHttpsOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          AdminSecurityRejectionAuditFilter adminSecurityRejectionAuditFilter,
                          ApiRateLimitFilter apiRateLimitFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.adminSecurityRejectionAuditFilter = adminSecurityRejectionAuditFilter;
        this.apiRateLimitFilter = apiRateLimitFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/prescriptions/upload").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/authorize-action")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")

                        // Staff location assignment is administrator-only.
                        .requestMatchers("/api/admin/staff/**").hasAuthority("ROLE_ADMIN")
                        // Register configuration is administrator-only.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/registers")
                            .hasAuthority("ROLE_ADMIN")
                        // Provider webhook exceptions contain payment identifiers; only managers/admins may review them.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/payments/webhook-review")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Stale payment-creation reservations expose payment/order identifiers; managers/admins only.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/payments/stale-creation-review", "/api/admin/pos/payments/stale-creation-review/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Sales and refund reporting is manager/admin only.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/reports/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Full sale voids require manager or administrator authority.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/sales/*/void")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Returns/refunds require manager or administrator authority.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/sales/*/returns")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Linked exchanges combine a return and replacement sale and require manager or administrator authority.
                        .requestMatchers(HttpMethod.POST, "/api/admin/pos/sales/*/exchange")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        // Cashiers may sell but cannot browse the full sales ledger.
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/sales", "/api/admin/pos/sales/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        .requestMatchers(HttpMethod.GET,
                                "/api/admin/pos/locations",
                                "/api/admin/pos/catalog",
                                "/api/admin/pos/registers")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/pos/checkout",
                                "/api/admin/pos/shifts/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/shifts/history")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/admin/pos/shifts/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CASHIER")
                        .requestMatchers("/api/admin/inventory/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")
                        .requestMatchers("/api/admin/procurement/**")
                            .hasAnyAuthority("ROLE_ADMIN", "ROLE_MANAGER")

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products", "/api/products/**").hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/marketing/banners",
                                "/api/marketing/offer",
                                "/api/website-images"
                        ).permitAll()

                        .requestMatchers(
                                "/", "/index.html", "/admin", "/admin/**",
                                "/track", "/track/**", "/assets/**", "/favicon.ico"
                        ).permitAll()

                        .requestMatchers("/api/payment/**").permitAll()
                        .requestMatchers("/api/orders/checkout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/orders/track/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/appointments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/appointments", "/api/appointments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/appointments/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/appointments/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterAfter(apiRateLimitFilter, org.springframework.web.filter.CorsFilter.class)
                .addFilterAfter(adminSecurityRejectionAuditFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static boolean isValidOrigin(String value) {
        if (value == null || value.isBlank() || value.contains("*")) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String path = uri.getRawPath();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (path == null || path.isEmpty());
        } catch (URISyntaxException invalidOrigin) {
            return false;
        }
    }

    @Bean
    public FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilterRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdminSecurityRejectionAuditFilter> adminSecurityRejectionAuditFilterRegistration(
            AdminSecurityRejectionAuditFilter filter
    ) {
        FilterRegistrationBean<AdminSecurityRejectionAuditFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("At least one explicit CORS origin must be configured");
        }
        for (String origin : origins) {
            if (!isValidOrigin(origin)) {
                throw new IllegalStateException("CORS entries must be explicit HTTP(S) origins without paths or wildcards");
            }
            if (requireHttpsOrigins && !origin.regionMatches(true, 0, "https://", 0, 8)) {
                throw new IllegalStateException("Production CORS origins must use HTTPS");
            }
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}