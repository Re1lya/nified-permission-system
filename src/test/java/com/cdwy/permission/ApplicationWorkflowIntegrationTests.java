package com.cdwy.permission;

import com.cdwy.permission.dto.CreatePermissionRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ApplicationWorkflowIntegrationTests extends WorkflowTestSupport {
    @Autowired PasswordEncoder passwords;

    private Map<String,Object> configuration(String protocol) {
        var body = new LinkedHashMap<String,Object>();
        body.put("clientId", "client" + suffix);
        body.put("entryUrl", "https://example.test/app");
        body.put("protocol", protocol);
        body.put("redirectUris", List.of("https://example.test/callback"));
        return body;
    }
    private Map<String,Object> acceptance() {
        return new LinkedHashMap<>(Map.of("login", true, "callback", true, "permission", true,
            "revocation", true, "isolation", true, "evidence", "HTTP 集成测试验收"));
    }
    private String path(long id) { return "/api/platform/applications/" + id; }

    @Test void publicClientRequiresPkceAndAcceptanceBeforePortalVisibility() throws Exception {
        long system = system();
        call(put(path(system) + "/config"), admin, configuration("OIDC"), 200);
        var client = new JdbcRegisteredClientRepository(db).findByClientId("client" + suffix);
        assertNotNull(client);
        assertTrue(client.getClientSettings().isRequireProofKey());
        assertTrue(client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE));
        assertFalse(client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
        assertEquals("DRAFT", db.queryForObject("select status from application_access where system_id=?", String.class, system));
        assertFalse(call(get("/api/platform/self/portal"), admin, null, 200).toString().contains("sys" + suffix));
        call(post(path(system) + "/acceptance"), admin, acceptance(), 200);
        assertTrue(call(get("/api/platform/self/portal"), admin, null, 200).toString().contains("sys" + suffix));
        assertFalse(call(get("/api/platform/self/portal"), userToken, null, 200).toString().contains("sys" + suffix));
        long permission = rbac.createPermission(new CreatePermissionRequest(system, "p" + suffix, "查询", "/query")).id();
        rbac.assignPermission(role, permission);
        rbac.assignRole(user, role);
        call(get("/api/platform/self/portal"), userToken, null, 401);
        userToken = login("user" + suffix);
        assertTrue(call(get("/api/platform/self/portal"), userToken, null, 200).toString().contains("sys" + suffix));
        rbac.removeRole(user, role);
        call(get("/api/platform/self/portal"), userToken, null, 401);
        userToken = login("user" + suffix);
        assertFalse(call(get("/api/platform/self/portal"), userToken, null, 200).toString().contains("sys" + suffix));
    }

    @Test void confidentialClientRetainsHashedSecretAndReconfigurationResetsAcceptance() throws Exception {
        long system = system();
        var body = configuration("OIDC");
        body.put("clientSecret", SECRET);
        call(put(path(system) + "/config"), admin, body, 200);
        var registry = new JdbcRegisteredClientRepository(db);
        String hash = registry.findByClientId("client" + suffix).getClientSecret();
        assertNotEquals(SECRET, hash);
        assertTrue(passwords.matches(SECRET, hash));
        assertTrue(registry.findByClientId("client" + suffix).getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
        call(post(path(system) + "/acceptance"), admin, acceptance(), 200);
        body.remove("clientSecret");
        call(put(path(system) + "/config"), admin, body, 200);
        assertEquals(hash, registry.findByClientId("client" + suffix).getClientSecret());
        var config = call(get(path(system) + "/config"), admin, null, 200);
        assertTrue(config.get("confidential").asBoolean());
        assertEquals("DRAFT", config.get("status").asText());
        assertTrue(config.get("acceptance").isNull());
        assertFalse(config.toString().contains(hash), "配置查询不能泄露客户端密钥散列");
        assertTrue(call(get("/api/platform/applications"), admin, null, 200).toString().contains("client" + suffix));
    }

    @Test void legacyClientCanOnlyBeAcceptedAsIsolated() throws Exception {
        long system = system();
        var body = configuration("LEGACY");
        call(put(path(system) + "/config"), admin, body, 400);
        body.put("compatibilityPlan", "保留独立登录，隔离业务权限");
        call(put(path(system) + "/config"), admin, body, 200);
        var acceptance = acceptance();
        acceptance.put("login", false);
        call(post(path(system) + "/acceptance"), admin, acceptance, 200);
        var config = call(get(path(system) + "/config"), admin, null, 200);
        assertEquals("ISOLATED", config.get("status").asText());
        assertFalse(config.get("confidential").asBoolean());
        assertEquals(0, config.get("redirectUris").size());
    }

    @ParameterizedTest @ValueSource(strings = {"login", "callback", "permission", "revocation", "isolation"})
    void incompleteAcceptanceCannotActivateClient(String missing) throws Exception {
        long system = system();
        call(put(path(system) + "/config"), admin, configuration("OIDC"), 200);
        var check = acceptance(); check.put(missing, false);
        call(post(path(system) + "/acceptance"), admin, check, 400);
        assertEquals("DRAFT", db.queryForObject("select status from application_access where system_id=?", String.class, system));
    }

    @ParameterizedTest @ValueSource(strings = {"http://outside.test/app", "https://user:pass@example.test/app",
        "https://example.test/app#fragment", "file:///tmp/app", "not a url", "http://demo.example.test:8081/app"})
    void unsafeEntryUrlLeavesNoRegistration(String url) throws Exception {
        long system = system();
        var body = configuration("OIDC"); body.put("entryUrl", url);
        call(put(path(system) + "/config"), admin, body, 400);
        assertEquals(0, count("select count(*) from application_access where system_id=?", system));
        assertNull(new JdbcRegisteredClientRepository(db).findByClientId("client" + suffix));
    }

    @ParameterizedTest @ValueSource(strings = {"http://localhost:9000/callback", "http://127.0.0.1/callback",
        "http://[::1]/callback", "http://demo.example.test:8080/callback"})
    void allowedHttpCallbacksCanBeRegistered(String url) throws Exception {
        long system = system();
        var body = configuration("OIDC"); body.put("entryUrl", url); body.put("redirectUris", List.of(url));
        call(put(path(system) + "/config"), admin, body, 200);
        assertTrue(new JdbcRegisteredClientRepository(db).findByClientId("client" + suffix).getRedirectUris().contains(url));
    }

    @Test void invalidCallbackAndShortSecretAreRejectedAtomically() throws Exception {
        long system = system(); var body = configuration("OIDC");
        body.remove("redirectUris"); call(put(path(system) + "/config"), admin, body, 400);
        body.put("redirectUris", List.of()); call(put(path(system) + "/config"), admin, body, 400);
        body.put("redirectUris", List.of("http://outside.test/callback")); call(put(path(system) + "/config"), admin, body, 400);
        body.put("redirectUris", List.of("https://example.test/callback")); body.put("clientSecret", "short");
        call(put(path(system) + "/config"), admin, body, 400);
        assertEquals(0, count("select count(*) from application_access where system_id=?", system));
    }

    @Test void clientIdentifierCannotBeReassignedAndOrdinaryUserCannotConfigure() throws Exception {
        long system = system(); var body = configuration("OIDC");
        call(get(path(system) + "/config"), admin, null, 404);
        call(post(path(system) + "/acceptance"), admin, acceptance(), 404);
        call(put(path(system) + "/config"), userToken, body, 403);
        call(put(path(system) + "/config"), admin, body, 200);
        long other = rbac.createSystem(new com.cdwy.permission.dto.CreateSystemRequest("other" + suffix, "其他", null)).id();
        call(put(path(other) + "/config"), admin, body, 409);
        body.put("clientId", "replacement" + suffix);
        call(put(path(system) + "/config"), admin, body, 409);
        assertEquals("client" + suffix, db.queryForObject("select client_id from application_access where system_id=?", String.class, system));
    }
}
