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
/**
 * MVC 层统一守卫：限制初始密码未修改账号，并对未明确放开的管理 API 默认要求管理员。
 * 被排除的 platform、integration、oidc 路径并不表示无需保护：它们分别校验目标范围、签名或 OIDC 令牌。
 * 前端隐藏菜单只改善交互，真正授权必须在服务器完成。
 */
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
                    try{long actor=request.getAttribute("audit.actor") instanceof Long saved?saved:tokens.requireUserId(request.getHeader("Authorization"));platform.audit(actor,"HTTP_"+request.getMethod(),request.getRequestURI(),"SUCCESS");}catch(com.cdwy.permission.exception.AuthenticationFailedException ignored){/* Logout/password change intentionally revoked the bearer. */}
                }
            }
        }).addPathPatterns("/api/**");
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
                long id=tokens.requireUserId(request.getHeader("Authorization"));
                request.setAttribute("audit.actor",id);
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
