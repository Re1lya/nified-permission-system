package com.cdwy.permission.platform;

import com.cdwy.permission.dto.LoginResponse;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.service.*;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKMatcher;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OidcConfigurationTests {
    OidcConfig config = new OidcConfig();
    DriverManagerDataSource data;
    JdbcTemplate db;
    BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);

    @BeforeEach void database() {
        data = new DriverManagerDataSource("jdbc:h2:mem:oidc-config-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(data);
        db.execute("create table biz_system(id bigint primary key, enabled boolean)");
        db.execute("create table application_access(system_id bigint, client_id varchar(100), status varchar(20))");
        db.execute("create table platform_signing_key(id varchar(20) primary key, jwk clob)");
    }
    @AfterEach void closeDatabase() { db.execute("SHUTDOWN"); }

    @Test void demoClientsUsePkceAndOptionalHashedSecret() throws Exception {
        String secret = "test-client-secret-at-least-32-characters";
        var registry = config.clients(db, data, passwords, true, secret);
        var oa = registry.findByClientId("oa-demo");
        assertTrue(oa.getClientSettings().isRequireProofKey());
        assertTrue(oa.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
        assertTrue(passwords.matches(secret, oa.getClientSecret()));
        assertTrue(registry.findByClientId("hr-demo").getRedirectUris().contains("http://127.0.0.1:18102/callback"));
        assertEquals(oa.getId(), registry.findById(oa.getId()).getId());
        assertNull(registry.findByClientId("missing"));
        config.clients(db, data, passwords, true, "ignored-on-existing-client");
        assertEquals(2, db.queryForObject("select count(*) from oauth2_registered_client", Integer.class));
    }
    @Test void publicDemoClientHasNoRefreshGrantAndShortSecretFails() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> config.clients(db, data, passwords, true, "short"));
        var registry = config.clients(db, data, passwords, true, "");
        assertNull(registry.findByClientId("oa-demo").getClientSecret());
        assertFalse(registry.findByClientId("oa-demo").getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
    }
    @Test void clientLookupRequiresActiveAcceptanceAndEnabledSystem() throws Exception {
        var registry = config.clients(db, data, passwords, false, "");
        var client = RegisteredClient.withId("system-1").clientId("business")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://example.test/callback").build();
        registry.save(client);
        assertNull(registry.findById(client.getId()));
        db.update("insert into biz_system values(1,true)");
        db.update("insert into application_access values(1,'business','DRAFT')");
        assertNull(registry.findByClientId("business"));
        db.update("update application_access set status='ACTIVE'");
        assertNotNull(registry.findByClientId("business"));
        db.update("update biz_system set enabled=false");
        assertNull(registry.findByClientId("business"));
    }
    @Test void signingKeySurvivesConfigurationReloadAndIssuerIsExplicitWhenConfigured() throws Exception {
        var selector = new JWKSelector(new JWKMatcher.Builder().build());
        var first = config.jwks(db).get(selector, null).get(0);
        var second = config.jwks(db).get(selector, null).get(0);
        assertEquals(first.getKeyID(), second.getKeyID());
        assertEquals(first.toPublicJWK(), second.toPublicJWK());
        assertEquals(1, db.queryForObject("select count(*) from platform_signing_key", Integer.class));
        assertNull(config.authorizationSettings("").getIssuer());
        assertEquals("https://issuer.test", config.authorizationSettings("https://issuer.test").getIssuer());
    }
    @Test void formAuthenticationUsesPlatformLoginAndRejectsInitialPassword() {
        var rbac = mock(RbacService.class); var store = mock(PlatformStore.class);
        when(rbac.login(any())).thenReturn(new LoginResponse("token", 7L, "alice", "员工", List.of()));
        var provider = config.platformAuthentication(rbac, store);
        assertTrue(provider.supports(UsernamePasswordAuthenticationToken.class));
        assertFalse(provider.supports(AnonymousAuthenticationToken.class));
        var request = UsernamePasswordAuthenticationToken.unauthenticated("alice", "password");
        var result = provider.authenticate(request);
        assertEquals("7", result.getName()); assertEquals("Bearer token", result.getDetails());
        when(store.mustChange(7L)).thenReturn(true);
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(request));
        when(rbac.login(any())).thenThrow(new AuthenticationFailedException());
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(request));
    }
    @Test void tokenClaimsBindPlatformSessionAndCurrentPermissionVersion() {
        var store = mock(PlatformStore.class); var tokens = mock(JwtTokenService.class);
        var context = mock(JwtEncodingContext.class); var claims = JwtClaimsSet.builder();
        var principal = UsernamePasswordAuthenticationToken.authenticated("7", null, List.of());
        principal.setDetails("Bearer platform");
        when(context.getPrincipal()).thenReturn(principal); when(context.getClaims()).thenReturn(claims);
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
        when(tokens.requireUserId("Bearer platform")).thenReturn(7L);
        when(tokens.sessionId("Bearer platform")).thenReturn("session-7"); when(store.version(7L)).thenReturn(4L);
        config.tokenClaims(store, tokens).customize(context);
        assertEquals(4L, ((Number) claims.build().getClaim("permission_version")).longValue());
        assertEquals("session-7", claims.build().getClaim("sid"));
        principal.setDetails(null); when(context.getClaims()).thenReturn(JwtClaimsSet.builder());
        config.tokenClaims(store, tokens).customize(context);
    }
    @ParameterizedTest @ValueSource(strings = {"active", "disabled", "expired"})
    void refreshRevalidatesAccountWithoutReusingOldPlatformSession(String state) {
        var store = mock(PlatformStore.class); var tokens = mock(JwtTokenService.class);
        var context = mock(JwtEncodingContext.class); var claims = JwtClaimsSet.builder();
        when(context.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated("7", null, List.of()));
        when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.REFRESH_TOKEN);
        when(context.getClaims()).thenReturn(claims);
        when(store.count("select count(*) from sys_user where id=? and enabled=true", 7L)).thenReturn(state.equals("disabled") ? 0L : 1L);
        when(store.version(7L)).thenReturn(8L);
        if (state.equals("expired")) doThrow(new AuthenticationFailedException()).when(store).validAccount(7L);
        if (state.equals("active")) {
            config.tokenClaims(store, tokens).customize(context);
            assertEquals(8L, ((Number) claims.build().getClaim("permission_version")).longValue());
        } else {
            var exception = assertThrows(OAuth2AuthenticationException.class, () -> config.tokenClaims(store, tokens).customize(context));
            assertEquals("invalid_grant", exception.getError().getErrorCode());
        }
        verifyNoInteractions(tokens);
    }
}
