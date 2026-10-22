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
    private final org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;
    public SsoController(JwtTokenService tokens,PlatformStore store,RbacService rbac,JwtDecoder decoder,org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations,org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients){this.clients=clients;this.tokens=tokens;this.store=store;this.rbac=rbac;this.decoder=decoder;this.authorizations=authorizations;}
    @PostMapping("/api/platform/self/sso") public Map<String,String> bridge(@RequestHeader("Authorization")String bearer,HttpServletRequest request,HttpServletResponse response){
        long id=tokens.requireUserId(bearer);if(store.mustChange(id))throw new ForbiddenException("请先修改初始密码");
        if(request.getSession(false)!=null)request.changeSessionId();
        var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(id),null,List.of(org.springframework.security.core.authority.FactorGrantedAuthority.withFactor("PASSWORD").issuedAt(tokens.issuedAt(bearer)).build()));auth.setDetails(bearer);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        return Map.of("message","单点登录会话已建立");
    }
    /** Validate an OIDC access token issued to clientId and return its subject; shared by every client-facing API. */
    private long verify(String bearer,String clientId){
        if(clients.findByClientId(clientId)==null)throw new IllegalArgumentException("未知或未启用的客户端");
        try{
            if(bearer==null||!bearer.startsWith("Bearer "))throw new AuthenticationFailedException();
            var jwt=decoder.decode(bearer.substring(7));if(!jwt.getAudience().contains(clientId))throw new AuthenticationFailedException();
            var authorization=authorizations.findByToken(jwt.getTokenValue(),org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
            if(authorization==null||authorization.getAccessToken()==null||!authorization.getAccessToken().isActive())throw new AuthenticationFailedException();
            long user=Long.parseLong(jwt.getSubject());store.validAccount(user);
            // Forced offline, disable, leave and password change all bump the version, so the version alone decides validity.
            if(store.count("select count(*) from sys_user where id=? and enabled=true",user)!=1)throw new AuthenticationFailedException();
            Number version=jwt.getClaim("permission_version");if(version==null||version.longValue()!=store.version(user))throw new AuthenticationFailedException("权限版本已改变，请刷新或重新获取令牌");
            return user;
        }catch(AuthenticationFailedException e){throw e;}catch(Exception e){throw new AuthenticationFailedException();}
    }
    @GetMapping("/api/oidc/permissions/check") public Map<String,Object> check(@RequestHeader(value="Authorization",required=false)String bearer,@RequestParam String code,@RequestParam String clientId){
        long user=verify(bearer,clientId);
        rbac.assertPermission(user,code);return Map.of("allowed",true,"userId",user,"code",code,"grantedBy",rbac.grantingRoles(user,code),"permissionVersion",store.version(user));
    }
    /** UC-B08: the caller's current permission set, with the version an application can cache it under. */
    @GetMapping("/api/oidc/permissions") public Map<String,Object> permissions(@RequestHeader(value="Authorization",required=false)String bearer,@RequestParam String clientId){
        long user=verify(bearer,clientId);
        return Map.of("userId",user,"permissionVersion",store.version(user),"permissions",rbac.effectivePermissions(user).stream().map(p->p.code()).toList());
    }
    /** UC-D02: organisation data for integrated applications; contact details are not part of the directory. */
    @GetMapping("/api/oidc/directory/departments") public List<Map<String,Object>> departments(@RequestHeader(value="Authorization",required=false)String bearer,@RequestParam String clientId){
        verify(bearer,clientId);
        return store.rows("select id,name,parent_id from sys_department where deleted=false order by id");
    }
    @GetMapping("/api/oidc/directory/users") public Map<String,Object> users(@RequestHeader(value="Authorization",required=false)String bearer,@RequestParam String clientId,@RequestParam(required=false) Long departmentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){
        verify(bearer,clientId);
        if(page<0||size<1||size>500)throw new IllegalArgumentException("分页参数错误");
        String where=" from sys_user u join sys_account ac on ac.user_id=u.id join employee_profile e on e.user_id=u.id left join account_policy p on p.user_id=u.id where (p.deleted is null or p.deleted=false)"+(departmentId==null?"":" and e.department_id="+departmentId);
        return Map.of("total",store.count("select count(*)"+where),"page",page,"size",size,
            "items",store.rows("select u.id,ac.username,u.display_name,u.enabled,e.department_id,e.post_id,coalesce(p.person_type,'EMPLOYEE') person_type"+where+" order by u.id limit ? offset ?",size,(long)page*size));
    }
}
