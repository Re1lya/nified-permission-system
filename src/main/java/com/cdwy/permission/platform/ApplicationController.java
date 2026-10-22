package com.cdwy.permission.platform;

import com.cdwy.permission.service.*;
import com.cdwy.permission.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Business catalog, protocol registration and acceptance share the same application identity. */
@RestController @RequestMapping("/api/platform")
public class ApplicationController {
    private final PlatformStore s;private final JwtTokenService tokens;private final PasswordEncoder passwords;
    private final JdbcRegisteredClientRepository registry;
    public ApplicationController(PlatformStore s,JwtTokenService tokens,PasswordEncoder passwords,JdbcTemplate db){this.s=s;this.tokens=tokens;this.passwords=passwords;this.registry=new JdbcRegisteredClientRepository(db);}
    public record Configuration(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,100}")String clientId,
        @NotBlank @Size(max=2048)String entryUrl,@NotBlank @Pattern(regexp="OIDC|LEGACY")String protocol,
        @Size(max=20)List<String> redirectUris,@Size(max=256)String clientSecret,@Size(max=2000)String compatibilityPlan){}
    private static void url(String text){try{var u=URI.create(text);boolean loopback=Set.of("127.0.0.1","localhost","[::1]").contains(Objects.toString(u.getHost(),""));if(u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||!("https".equals(u.getScheme())||loopback&&"http".equals(u.getScheme())))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("地址必须使用 HTTPS，本地回环演示允许 HTTP；不能包含凭证或片段");}}
    @GetMapping("/applications") public List<Map<String,Object>> list(@RequestHeader("Authorization")String auth){s.requireSuper(tokens.requireUserId(auth));return s.rows("select a.*,b.name from application_access a join biz_system b on b.id=a.system_id order by a.system_id");}
    @GetMapping("/applications/{id}/config") public Map<String,Object> config(@RequestHeader("Authorization")String auth,@PathVariable long id){s.requireSuper(tokens.requireUserId(auth));var rows=s.rows("select * from application_access where system_id=?",id);if(rows.isEmpty())throw new ResourceNotFoundException("应用未配置");var out=new LinkedHashMap<>(rows.getFirst());var c=registry.findByClientId((String)out.get("client_id"));out.put("redirectUris",c==null?List.of():c.getRedirectUris());out.put("confidential",c!=null&&c.getClientSecret()!=null);return out;}
    @PutMapping("/applications/{id}/config") @Transactional public void configure(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Configuration c){
        long actor=tokens.requireUserId(auth);s.requireSuper(actor);s.db().queryForObject("select id from biz_system where id=? and deleted=false for update",Long.class,id);url(c.entryUrl());
        var rows=s.rows("select client_id from application_access where system_id=?",id);
        if(!rows.isEmpty()&&!c.clientId().equals(rows.getFirst().get("client_id")))throw new ConflictException("客户端编号不可变更，请新建应用");
        var old=registry.findByClientId(c.clientId());if(old!=null&&!old.getId().equals("system-"+id))throw new ConflictException("客户端编号已被占用");
        if(ApplicationProtocol.of(c.protocol()).singleSignOn()){
            if(c.redirectUris()==null||c.redirectUris().isEmpty())throw new IllegalArgumentException("至少配置一个精确回调地址");c.redirectUris().forEach(ApplicationController::url);
            String secret=c.clientSecret();if(secret!=null&&!secret.isEmpty()&&secret.length()<32)throw new IllegalArgumentException("客户端密钥至少 32 字符");
            var builder=RegisteredClient.withId("system-"+id).clientId(c.clientId()).clientName(c.clientId())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid").scope("profile")
                .redirectUris(uris->uris.addAll(c.redirectUris()))
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).refreshTokenTimeToLive(Duration.ofHours(8)).reuseRefreshTokens(false).build());
            String encoded=secret!=null&&!secret.isEmpty()?passwords.encode(secret):old==null?null:old.getClientSecret();
            if(encoded==null)builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
            else builder.clientSecret(encoded).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            registry.save(builder.build());
        } else if(c.compatibilityPlan()==null||c.compatibilityPlan().isBlank())throw new IllegalArgumentException("不能改造的系统必须填写隔离和兼容处理方案，不能声明已实现免登");
        if(rows.isEmpty())s.db().update("insert into application_access(system_id,client_id,entry_url,protocol,status,compatibility_plan) values(?,?,?,?,'DRAFT',?)",id,c.clientId(),c.entryUrl(),c.protocol(),c.compatibilityPlan());
        else s.db().update("update application_access set entry_url=?,protocol=?,status='DRAFT',compatibility_plan=?,acceptance=null where system_id=?",c.entryUrl(),c.protocol(),c.compatibilityPlan(),id);
        s.db().update("delete from oauth2_authorization where registered_client_id=?","system-"+id);
        s.audit(actor,"APPLICATION_CONFIG",Long.toString(id),"SUCCESS");
    }
    public record Acceptance(boolean login,boolean callback,boolean permission,boolean revocation,boolean isolation,@NotBlank @Size(max=1500)String evidence){}
    @PostMapping("/applications/{id}/acceptance") @Transactional public void accept(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Acceptance a){
        long actor=tokens.requireUserId(auth);s.requireSuper(actor);
        var rows=s.rows("select protocol from application_access where system_id=? for update",id);if(rows.isEmpty())throw new ResourceNotFoundException("应用未配置");
        boolean oidc=rows.getFirst().get("protocol").equals("OIDC");
        if(!a.isolation()||oidc&&!(a.login()&&a.callback()&&a.permission()&&a.revocation()))throw new IllegalArgumentException("接入验收项尚未全部通过");
        s.db().update("update application_access set status=?,acceptance=? where system_id=?",oidc?"ACTIVE":"ISOLATED",a.evidence(),id);s.audit(actor,"APPLICATION_ACCEPTANCE",Long.toString(id),"SUCCESS");
    }
    @GetMapping("/self/portal") public List<Map<String,Object>> portal(@RequestHeader("Authorization")String auth){
        long actor=tokens.requireUserId(auth);
        return s.rows("select a.system_id,b.code,b.name,a.entry_url,a.protocol,a.status from application_access a join biz_system b on b.id=a.system_id where b.enabled=true and a.status in('ACTIVE','ISOLATED')"+(s.superAdmin(actor)?"":" and b.id in(select p.system_id from sys_permission p join sys_role_permission rp on rp.permission_id=p.id join sys_role r on r.id=rp.role_id join sys_user_role ur on ur.role_id=r.id where ur.user_id="+actor+" and r.enabled=true and p.active=true)")+" order by b.name");
    }
}
