package com.cdwy.permission.platform;
import com.cdwy.permission.service.*;
import com.cdwy.permission.exception.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;

@RestController
public class SsoController {
    private final JwtTokenService tokens;private final PlatformStore store;private final RbacService rbac;private final JwtDecoder decoder;
    private final org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;
    public SsoController(JwtTokenService tokens,PlatformStore store,RbacService rbac,JwtDecoder decoder,org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations){this.tokens=tokens;this.store=store;this.rbac=rbac;this.decoder=decoder;this.authorizations=authorizations;}
    @PostMapping("/api/platform/self/sso") public Map<String,String> bridge(@RequestHeader("Authorization")String bearer,HttpServletRequest request,HttpServletResponse response){
        long id=tokens.requireUserId(bearer);if(store.mustChange(id))throw new ForbiddenException("请先修改初始密码");
        if(request.getSession(false)!=null)request.changeSessionId();
        var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(id),null,List.of(org.springframework.security.core.authority.FactorGrantedAuthority.withFactor("PASSWORD").issuedAt(tokens.issuedAt(bearer)).build()));auth.setDetails(bearer);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        return Map.of("message","本地单点登录会话已建立");
    }
    @GetMapping("/api/oidc/permissions/check") public Map<String,Object> check(@RequestHeader(value="Authorization",required=false)String bearer,@RequestParam String code,@RequestParam String clientId){
        if(!Set.of("oa-demo","hr-demo").contains(clientId))throw new IllegalArgumentException("未知本地客户端");
        try{
            if(bearer==null||!bearer.startsWith("Bearer "))throw new AuthenticationFailedException();
            var jwt=decoder.decode(bearer.substring(7));if(!jwt.getAudience().contains(clientId))throw new AuthenticationFailedException();
            var authorization=authorizations.findByToken(jwt.getTokenValue(),org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
            if(authorization==null||authorization.getAccessToken()==null||!authorization.getAccessToken().isActive())throw new AuthenticationFailedException();
            long user=Long.parseLong(jwt.getSubject());store.validAccount(user);
            if(store.count("select count(*) from sys_user u join sys_login_session s on s.user_id=u.id where u.id=? and u.enabled=true and s.id=? and s.expires_at>?",user,jwt.getClaimAsString("sid"),Instant.now())!=1)throw new AuthenticationFailedException();
            Number version=jwt.getClaim("permission_version");if(version==null||version.longValue()!=store.version(user))throw new AuthenticationFailedException("权限版本已改变，请重新获取令牌");
            rbac.assertPermission(user,code);return Map.of("allowed",true,"userId",user,"code",code);
        }catch(ForbiddenException|AuthenticationFailedException e){throw e;}catch(Exception e){throw new AuthenticationFailedException();}
    }
}
