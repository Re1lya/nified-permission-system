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
    private final com.cdwy.permission.platform.PlatformStore platform;

    public ApiAccessConfig(JwtTokenService tokens, UserAccountRepository users,com.cdwy.permission.platform.PlatformStore platform) {
        this.tokens = tokens;
        this.users = users;
        this.platform = platform;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error){
                if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())&&response.getStatus()<400&&request.getHeader("Authorization")!=null&&!request.getRequestURI().startsWith("/api/integration/")){
                    try{long actor=tokens.requireUserId(request.getHeader("Authorization"));platform.audit(actor,"HTTP_"+request.getMethod(),request.getRequestURI(),"SUCCESS");}catch(com.cdwy.permission.exception.AuthenticationFailedException ignored){/* Logout/password change intentionally revoked the bearer. */}
                }
            }
        }).addPathPatterns("/api/**");
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
                long id=tokens.requireUserId(request.getHeader("Authorization"));
                if(platform.mustChange(id)&&!java.util.Set.of("/api/platform/self","/api/platform/self/password").contains(request.getRequestURI()))throw new ForbiddenException("请先修改初始密码");return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login","/api/auth/logout","/api/auth/refresh","/api/auth/me","/api/integration/**","/api/oidc/**");
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
          .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/api/auth/refresh", "/api/auth/me", "/api/demo/expenses", "/api/platform/**", "/api/integration/**", "/api/oidc/**");
    }
}
