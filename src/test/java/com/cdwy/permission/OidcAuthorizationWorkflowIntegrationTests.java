package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OidcAuthorizationWorkflowIntegrationTests extends WorkflowTestSupport {
    @Test void authorizationCodePkceUserinfoAndVersionRevocationWorkTogether() throws Exception {
        long system = system(); String clientId = "oidc" + suffix;
        long permission = rbac.createPermission(new CreatePermissionRequest(system, "p" + suffix, "查询", "/query")).id();
        rbac.assignPermission(role, permission); rbac.assignRole(user, role);
        userToken = login("user" + suffix);
        call(put("/api/platform/applications/" + system + "/config"), admin,
            Map.of("clientId", clientId, "entryUrl", "https://client.test/app", "protocol", "OIDC",
                "redirectUris", List.of("https://client.test/callback"), "clientSecret", SECRET), 200);
        call(post("/api/platform/applications/" + system + "/acceptance"), admin,
            Map.of("login", true, "callback", true, "permission", true, "revocation", true, "isolation", true, "evidence", "测试接入"), 200);
        // 此协议测试预先允许 consent；同意页的 HTML/CSRF/转义由专门的页面测试覆盖。
        var registry = new JdbcRegisteredClientRepository(db);
        registry.save(RegisteredClient.from(registry.findByClientId(clientId))
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build()).build());
        var bridge = mvc.perform(post("/api/platform/self/sso").header("Authorization", userToken))
            .andExpect(status().isOk()).andReturn();
        var session = bridge.getResponse().getCookie("SESSION");
        assertNotNull(session, "JDBC Spring Session 必须向客户端返回会话 cookie");
        String verifier = "a".repeat(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorize = mvc.perform(get("/oauth2/authorize").cookie(session).queryParam("client_id", clientId)
            .queryParam("response_type", "code").queryParam("redirect_uri", "https://client.test/callback")
            .queryParam("scope", "openid profile").queryParam("state", suffix).queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")).andExpect(status().is3xxRedirection()).andReturn();
        String location = authorize.getResponse().getHeader("Location");
        assertNotNull(location); assertTrue(location.startsWith("https://client.test/callback?"), location);
        String code = Arrays.stream(URI.create(location).getRawQuery().split("&")).filter(p -> p.startsWith("code="))
            .map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        String basic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
        var tokenResult = mvc.perform(post("/oauth2/token").header("Authorization", basic)
            .contentType("application/x-www-form-urlencoded").param("grant_type", "authorization_code")
            .param("code", code).param("redirect_uri", "https://client.test/callback").param("code_verifier", verifier))
            .andExpect(status().isOk()).andReturn();
        var token = json.readTree(tokenResult.getResponse().getContentAsString());
        assertTrue(token.has("id_token")); assertTrue(token.has("refresh_token"));
        String bearer = "Bearer " + token.get("access_token").asText();
        var infoResult = mvc.perform(get("/userinfo").header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
        var info = json.readTree(infoResult.getResponse().getContentAsString());
        assertEquals(Long.toString(user), info.get("sub").asText());
        assertTrue(info.get("permissions").toString().contains("p" + suffix));
        assertTrue(call(get("/api/oidc/permissions/check").param("clientId", clientId).param("code", "p" + suffix), bearer, null, 200).get("allowed").asBoolean());
        var directory = call(get("/api/oidc/directory/users").param("clientId", clientId).param("departmentId", "" + department), bearer, null, 200);
        assertEquals(2, directory.get("total").asLong());
        assertFalse(directory.get("items").get(0).has("phone"));
        call(get("/api/oidc/directory/departments").param("clientId", clientId), bearer, null, 200);
        rbac.removeRole(user, role);
        call(get("/api/oidc/permissions").param("clientId", clientId), bearer, null, 401);
        mvc.perform(get("/userinfo").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        var invalid = mvc.perform(get("/oauth2/authorize").cookie(session).accept("text/html").queryParam("client_id", clientId)
            .queryParam("response_type", "code").queryParam("redirect_uri", "https://client.test/callback")
            .queryParam("scope", "openid").queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection()).andReturn();
        assertTrue(invalid.getResponse().getHeader("Location").contains("/login"), "权限版本失效后不能复用旧的浏览器 SSO 会话");
    }
}
