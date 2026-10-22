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

/** Local phase-one OIDC server. Runtime keys and grants deliberately expire on restart. */
@Configuration
public class OidcConfig {
    @Bean @Order(1) SecurityFilterChain authorizationServer(HttpSecurity http,com.cdwy.permission.service.JwtTokenService tokens) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.oidc(Customizer.withDefaults());
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
    @Bean RegisteredClientRepository clients(org.springframework.security.crypto.password.PasswordEncoder passwords,@org.springframework.beans.factory.annotation.Value("${permission.oidc.client-secret:}") String secret) {
        if(!secret.isEmpty()&&secret.length()<32)throw new IllegalArgumentException("permission.oidc.client-secret 至少 32 个字符");
        List<RegisteredClient> clients=new ArrayList<>();
        for(String name:List.of("oa-demo","hr-demo")){
            var client=RegisteredClient.withId(name).clientId(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:"+(name.equals("oa-demo")?18101:18102)+"/callback")
                .scope("openid").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(java.time.Duration.ofMinutes(5)).refreshTokenTimeToLive(java.time.Duration.ofHours(8)).reuseRefreshTokens(false).build());
            if(!secret.isEmpty())client.clientSecret(passwords.encode(secret)).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            clients.add(client.build());
        }
        return new InMemoryRegisteredClientRepository(clients);
    }
    @Bean OAuth2AuthorizationService authorizations(){return new InMemoryOAuth2AuthorizationService();}
    @Bean OAuth2AuthorizationConsentService consents(){return new InMemoryOAuth2AuthorizationConsentService();}
    @Bean AuthorizationServerSettings authorizationSettings(){return AuthorizationServerSettings.builder().build();}
    @Bean JWKSource<SecurityContext> jwks() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var key=new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
        return new ImmutableJWKSet<>(new JWKSet(key));
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
