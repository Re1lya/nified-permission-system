package com.cdwy.permission.config;

import com.cdwy.permission.exception.ForbiddenException;
import com.cdwy.permission.repository.UserAccountRepository;
import com.cdwy.permission.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Deny ordinary users by default on API routes, including future management endpoints. */
@Configuration
public class ApiAccessConfig implements WebMvcConfigurer {
    private final JwtTokenService tokens;
    private final UserAccountRepository users;

    public ApiAccessConfig(JwtTokenService tokens, UserAccountRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                return true;
            }
        }).addPathPatterns("/api/**");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Long id = tokens.requireUserId(request.getHeader("Authorization"));
                if (!users.findById(id).map(user -> user.isAdministrator()).orElse(false)) {
                    throw new ForbiddenException("管理接口仅允许管理员访问");
                }
                return true;
            }
        }).addPathPatterns("/api/**")
          .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/api/auth/me", "/api/demo/expenses");
    }
}
