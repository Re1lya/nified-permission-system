package com.cdwy.permission.platform;

import com.cdwy.permission.dto.LoginRequest;
import com.cdwy.permission.service.RbacService;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;

/** Persistent OIDC provider; business applications are registered through the onboarding API. */
@Configuration
public class OidcConfig {
    @Bean @Order(1) SecurityFilterChain authorizationServer(HttpSecurity http,com.cdwy.permission.service.JwtTokenService tokens,RbacService rbac,PlatformStore store) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            // UC-B08: userinfo carries the subject's current permission codes and the version they belong to.
            server.oidc(oidc->oidc.userInfoEndpoint(info->info.userInfoMapper(context->{
                var authorization=context.getAuthorization();long user=Long.parseLong(authorization.getPrincipalName());
                try {
                    store.validAccount(user);
                    Number version=(Number)authorization.getAccessToken().getClaims().get("permission_version");
                    if(store.count("select count(*) from sys_user where id=? and enabled=true",user)!=1||version==null||version.longValue()!=store.version(user))throw new IllegalArgumentException();
                }catch(RuntimeException invalid){throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token","账号或权限版本已失效",null));}
                var idToken=authorization.getToken(org.springframework.security.oauth2.core.oidc.OidcIdToken.class);
                Map<String,Object> claims=new LinkedHashMap<>(idToken==null?Map.of("sub",Long.toString(user)):idToken.getToken().getClaims());
                claims.put("permissions",rbac.effectivePermissions(user).stream().map(permission->permission.code()).toList());
                claims.put("permission_version",store.version(user));
                return new org.springframework.security.oauth2.core.oidc.OidcUserInfo(claims);
            })));
            server.authorizationEndpoint(endpoint->endpoint.consentPage("/consent"));
        }).authorizeHttpRequests(a->a.anyRequest().authenticated())
          .exceptionHandling(e->e.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter(){
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res,jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
                var context=org.springframework.security.core.context.SecurityContextHolder.getContext();var auth=context.getAuthentication();
                if(auth!=null&&auth.getDetails() instanceof String bearer){try{tokens.requireUserId(bearer);}catch(com.cdwy.permission.exception.AuthenticationFailedException e){context.setAuthentication(null);if(req.getSession(false)!=null)req.getSession(false).invalidate();}}
                chain.doFilter(req,res);
            }
        },org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
    @Bean @Order(2) SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        // Bearer APIs are authorized by ApiAccessConfig and service-level scope checks.
        http.securityMatcher("/api/**").authorizeHttpRequests(a->a.anyRequest().permitAll()).csrf(c->c.disable());
        return http.build();
    }
    @Bean @Order(3) SecurityFilterChain browserChain(HttpSecurity http,AuthenticationProvider platformAuthentication) throws Exception {
        http.authorizeHttpRequests(a->a.requestMatchers("/", "/index.html","/app.js","/app.css","/favicon.ico","/ui/**","/error").permitAll().anyRequest().authenticated())
            .authenticationProvider(platformAuthentication).formLogin(form->form.loginPage("/login").permitAll());
        return http.build();
    }
    @Bean AuthenticationProvider platformAuthentication(RbacService service, PlatformStore store) {
        return new AuthenticationProvider() {
            public Authentication authenticate(Authentication auth) {
                try {
                    var login=service.login(new LoginRequest(auth.getName(),String.valueOf(auth.getCredentials())));
                    if(store.mustChange(login.userId()))throw new BadCredentialsException("请先在管理门户修改初始密码");
                    var authentication=UsernamePasswordAuthenticationToken.authenticated(login.userId().toString(),null,List.of(org.springframework.security.core.authority.FactorGrantedAuthority.fromFactor("PASSWORD")));
                    authentication.setDetails("Bearer "+login.accessToken());return authentication;
                } catch(com.cdwy.permission.exception.AuthenticationFailedException e){throw new BadCredentialsException("账号、密码或账号状态不正确");}
            }
            public boolean supports(Class<?> type){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);}
        };
    }
    /**
     * Two loopback demo clients. Without a secret they are public PKCE clients. With PERMISSION_OIDC_CLIENT_SECRET (32+ chars)
     * they may also authenticate with that secret; only then does the server issue refresh tokens (UC-A07), as public clients never get one.
     */
    @Bean @DependsOn("entityManagerFactory") RegisteredClientRepository clients(org.springframework.jdbc.core.JdbcTemplate db,
            javax.sql.DataSource dataSource, org.springframework.security.crypto.password.PasswordEncoder passwords,
            @org.springframework.beans.factory.annotation.Value("${permission.oidc.demo-clients:false}") boolean demos,
            @org.springframework.beans.factory.annotation.Value("${permission.oidc.client-secret:}") String secret) throws Exception {
        try(var connection=dataSource.getConnection()) {
            // Production databases are migrated explicitly; ephemeral development H2 gets the same schema.
            if(connection.getMetaData().getDatabaseProductName().equals("H2"))
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("oidc-schema.sql"),new org.springframework.core.io.ClassPathResource("session-schema-h2.sql")).execute(dataSource);
        }
        var repository=new JdbcRegisteredClientRepository(db);
        if(demos) for(String name:List.of("oa-demo","hr-demo")) {
            if(repository.findByClientId(name)!=null)continue;
            var client=RegisteredClient.withId(name).clientId(name).clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:"+(name.equals("oa-demo")?18101:18102)+"/callback")
                .scope("openid").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build());
            if(!secret.isEmpty()) {
                if(secret.length()<32)throw new IllegalArgumentException("客户端密钥至少 32 字符");
                client.clientSecret(passwords.encode(secret)).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            }
            repository.save(client.build());
        }
        return new RegisteredClientRepository() {
            public void save(RegisteredClient client){repository.save(client);}
            private RegisteredClient enabled(RegisteredClient client){
                if(client==null)return null;
                var entries=db.queryForList("select b.enabled,a.status from application_access a join biz_system b on b.id=a.system_id where a.client_id=?",client.getClientId());
                if(entries.isEmpty())return demos&&Set.of("oa-demo","hr-demo").contains(client.getClientId())?client:null;
                return Boolean.TRUE.equals(entries.getFirst().get("enabled"))&&"ACTIVE".equals(entries.getFirst().get("status"))?client:null;
            }
            public RegisteredClient findById(String id){return enabled(repository.findById(id));}
            public RegisteredClient findByClientId(String id){return enabled(repository.findByClientId(id));}
        };
    }
    @Bean OAuth2AuthorizationService authorizations(org.springframework.jdbc.core.JdbcTemplate db,RegisteredClientRepository clients){return new JdbcOAuth2AuthorizationService(db,clients);}
    @Bean OAuth2AuthorizationConsentService consents(org.springframework.jdbc.core.JdbcTemplate db,RegisteredClientRepository clients){return new JdbcOAuth2AuthorizationConsentService(db,clients);}
    @Bean AuthorizationServerSettings authorizationSettings(@org.springframework.beans.factory.annotation.Value("${permission.oidc.issuer:}") String issuer){
        var settings=AuthorizationServerSettings.builder();if(!issuer.isBlank())settings.issuer(issuer);return settings.build();
    }
    @Bean @DependsOn("entityManagerFactory") JWKSource<SecurityContext> jwks(org.springframework.jdbc.core.JdbcTemplate db) throws Exception {
        var saved=db.queryForList("select jwk from platform_signing_key where id='active'",String.class);
        if(saved.isEmpty()) {
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();
            var key=new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
            try{db.update("insert into platform_signing_key(id,jwk) values('active',?)",key.toJSONString());}
            catch(org.springframework.dao.DuplicateKeyException race){/* Another instance created the shared key. */}
        }
        return new ImmutableJWKSet<>(new JWKSet(JWK.parse(db.queryForObject("select jwk from platform_signing_key where id='active'",String.class))));
    }
    @Bean JwtDecoder oidcDecoder(JWKSource<SecurityContext> keys){return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);}
    @Bean org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer<org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext> tokenClaims(PlatformStore store,com.cdwy.permission.service.JwtTokenService tokens){
        return context->{
            var principal=context.getPrincipal();
            if(AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())){
                // The platform session behind the original login may have rotated; re-check the account itself.
                long user=Long.parseLong(principal.getName());
                try{store.validAccount(user);}catch(com.cdwy.permission.exception.AuthenticationFailedException e){throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,"账号已停用或过期",null));}
                if(store.count("select count(*) from sys_user where id=? and enabled=true",user)!=1)throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,"账号已停用",null));
                context.getClaims().claim("permission_version",store.version(user));
            }
            else if(principal.getDetails() instanceof String bearer){
                long user=tokens.requireUserId(bearer);
                context.getClaims().claim("permission_version",store.version(user)).claim("sid",tokens.sessionId(bearer));
            }
        };
    }
}
