package nmk_backend.config;

import nmk_backend.security.AdminMutationAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAuditWebConfig implements WebMvcConfigurer {
    private final AdminMutationAuditInterceptor interceptor;

    public AdminAuditWebConfig(AdminMutationAuditInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}