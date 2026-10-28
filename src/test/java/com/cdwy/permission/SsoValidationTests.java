package com.cdwy.permission;

import com.cdwy.permission.exception.*;
import com.cdwy.permission.platform.*;
import com.cdwy.permission.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用受控的令牌/撤销存储验证 SSO 边界；不把此单元测试声明为完整 OIDC 浏览器流程。 */
class SsoValidationTests {
    JwtTokenService tokens;
    PlatformStore store;
    RbacService rbac;
    JwtDecoder decoder;
    OAuth2AuthorizationService authorizations;
    RegisteredClientRepository clients;
    RegisteredClient client;
    SsoController controller;

    @BeforeEach void setup() {
        tokens = mock(JwtTokenService.class); store = mock(PlatformStore.class); rbac = mock(RbacService.class);
        decoder = mock(JwtDecoder.class); authorizations = mock(OAuth2AuthorizationService.class);
        clients = mock(RegisteredClientRepository.class);
        client = RegisteredClient.withId("test-id").clientId("business")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://example.test/callback").scope("openid").build();
        when(clients.findByClientId("business")).thenReturn(client);
        when(decoder.decode("access")).thenReturn(jwt("7", List.of("business"), 3L));
        when(authorizations.findByToken("access", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization(false, false));
        when(store.count("select count(*) from sys_user where id=? and enabled=true", 7L)).thenReturn(1L);
        when(store.version(7L)).thenReturn(3L);
        controller = new SsoController(tokens, store, rbac, decoder, authorizations, clients);
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    Jwt jwt(String subject, List<String> audience, Long version) {
        var builder = Jwt.withTokenValue("access").header("alg", "RS256").subject(subject).audience(audience)
            .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300));
        if (version != null) builder.claim("permission_version", version);
        return builder.build();
    }
    OAuth2Authorization authorization(boolean expired, boolean revoked) {
        var now = Instant.now();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now.minusSeconds(600),
            expired ? now.minusSeconds(1) : now.plusSeconds(300));
        return OAuth2Authorization.withRegisteredClient(client).principalName("7")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .token(access, metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, revoked)).build();
    }

    @Test void unknownClientIsRejectedBeforeTokenDecoding() {
        assertThrows(IllegalArgumentException.class, () -> controller.permissions("Bearer access", "unknown"));
        verifyNoInteractions(decoder);
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "Basic access", "access"})
    void missingBearerIsRejected(String bearer) {
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions(bearer, "business"));
        verifyNoInteractions(decoder);
    }
    @Test void wrongAudienceCannotCallAnotherClientDirectory() {
        when(decoder.decode("access")).thenReturn(jwt("7", List.of("other-client"), 3L));
        assertThrows(AuthenticationFailedException.class, () -> controller.departments("Bearer access", "business"));
        verifyNoInteractions(rbac);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "empty", "expired", "revoked"})
    void authorizationMustExistAndBeActive(String mode) {
        OAuth2Authorization auth = switch (mode) {
            case "missing" -> null;
            case "empty" -> OAuth2Authorization.withRegisteredClient(client).principalName("7")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build();
            case "expired" -> authorization(true, false);
            default -> authorization(false, true);
        };
        when(authorizations.findByToken("access", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(auth);
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
    }
    @ParameterizedTest @NullSource @ValueSource(longs = {2, 4})
    void missingOrStalePermissionVersionIsRejected(Long version) {
        when(decoder.decode("access")).thenReturn(jwt("7", List.of("business"), version));
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
    }
    @Test void invalidSignatureAndNonNumericSubjectAreAuthenticationFailures() {
        when(decoder.decode("access")).thenThrow(new JwtException("invalid signature"));
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
        reset(decoder);
        when(decoder.decode("access")).thenReturn(jwt("not-an-id", List.of("business"), 3L));
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
    }
    @Test void disabledOrExpiredAccountCannotUseValidOidcToken() {
        when(store.count("select count(*) from sys_user where id=? and enabled=true", 7L)).thenReturn(0L);
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
        when(store.count("select count(*) from sys_user where id=? and enabled=true", 7L)).thenReturn(1L);
        doThrow(new AuthenticationFailedException()).when(store).validAccount(7L);
        assertThrows(AuthenticationFailedException.class, () -> controller.permissions("Bearer access", "business"));
    }
    @Test void permissionCheckReturnsGrantBasisAndPropagatesDenial() {
        when(rbac.grantingRoles(7L, "expense:list")).thenReturn(List.of("accountant"));
        var result = controller.check("Bearer access", "expense:list", "business");
        assertEquals(true, result.get("allowed")); assertEquals(3L, result.get("permissionVersion"));
        assertEquals(List.of("accountant"), result.get("grantedBy"));
        verify(rbac).assertPermission(7L, "expense:list");
        doThrow(new ForbiddenException("无此权限")).when(rbac).assertPermission(7L, "expense:approve");
        assertThrows(ForbiddenException.class, () -> controller.check("Bearer access", "expense:approve", "business"));
        when(rbac.effectivePermissions(7L)).thenReturn(List.of());
        assertEquals(List.of(), controller.permissions("Bearer access", "business").get("permissions"));
    }
    @Test void directoryUsesBoundedPaginationAndOptionalDepartmentFilter() {
        assertEquals(List.of(), controller.departments("Bearer access", "business"));
        var all = controller.users("Bearer access", "business", null, 0, 100);
        assertEquals(0L, all.get("total")); assertEquals(100, all.get("size"));
        var scoped = controller.users("Bearer access", "business", 12L, 2, 10);
        assertEquals(2, scoped.get("page"));
        verify(store).count(contains("and e.department_id=12"));
    }
    @ParameterizedTest @CsvSource({"-1,100", "0,0", "0,501"})
    void invalidDirectoryPaginationIsRejected(int page, int size) {
        assertThrows(IllegalArgumentException.class, () -> controller.users("Bearer access", "business", null, page, size));
    }
    @Test void bridgeRotatesExistingSessionAndStoresAuthenticatedIdentity() {
        when(tokens.requireUserId("Bearer platform")).thenReturn(7L);
        when(tokens.issuedAt("Bearer platform")).thenReturn(Instant.now());
        var request = new MockHttpServletRequest();
        String previous = request.getSession().getId();
        controller.bridge("Bearer platform", request, new MockHttpServletResponse());
        assertNotEquals(previous, request.getSession().getId());
        var context = (SecurityContext) request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals("7", context.getAuthentication().getName());
        assertTrue(context.getAuthentication().isAuthenticated());
        assertEquals("Bearer platform", context.getAuthentication().getDetails());
        var fresh = new MockHttpServletRequest();
        controller.bridge("Bearer platform", fresh, new MockHttpServletResponse());
        assertNotNull(fresh.getSession(false));
    }
    @Test void initialPasswordMustBeChangedBeforeCreatingSsoSession() {
        when(tokens.requireUserId("Bearer platform")).thenReturn(7L);
        when(store.mustChange(7L)).thenReturn(true);
        var request = new MockHttpServletRequest();
        assertThrows(ForbiddenException.class, () -> controller.bridge("Bearer platform", request, new MockHttpServletResponse()));
        assertNull(request.getSession(false));
    }
}
