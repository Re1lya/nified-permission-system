package com.cdwy.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cdwy.permission.dto.LoginRequest;
import com.cdwy.permission.dto.SetPasswordRequest;
import com.cdwy.permission.exception.AuthenticationFailedException;
import com.cdwy.permission.service.JwtTokenService;
import com.cdwy.permission.service.RbacService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:authentication-http-tests;DB_CLOSE_DELAY=-1",
        "permission.jwt.secret=authentication-http-test-secret-at-least-32-bytes"
})
@AutoConfigureMockMvc
class AuthenticationHttpIntegrationTests {

    private static final String PASSWORD = "password-123";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            "authentication-http-test-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired private com.cdwy.permission.service.AdminBootstrapService bootstrap;
    private String adminToken;
    private long userId;
    private long systemId;
    private long roleId;

    @BeforeEach
    void createFixture() throws Exception {
        // Each HTTP call commits independently, as it would in Postman.
        for (var user : rbacService.listUsers()) {
            if (!user.isAdministrator()) rbacService.deleteUser(user.id());
        }
        for (var role : rbacService.listRoles()) {
            rbacService.deleteRole(role.id());
        }
        for (var system : rbacService.listSystems()) {
            rbacService.deleteSystem(system.id());
        }
        bootstrap.initialize("test-admin", PASSWORD);
        adminToken = login("test-admin", PASSWORD);
        systemId = create("/api/systems", """
                {"code":"finance","name":"财务系统"}
                """).path("id").asLong();
        roleId = create("/api/roles", """
                {"code":"viewer","name":"查看员"}
                """).path("id").asLong();
        long permissionId = create("/api/permissions", """
                {"systemId":%d,"code":"expense:list","name":"查看报销","menuPath":"/expense/list"}
                """.formatted(systemId)).path("id").asLong();
        mvc.perform(put("/api/roles/{roleId}/permissions/{permissionId}", roleId, permissionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        userId = createUser("alice");
    }

    @Test
    void passwordResetInvalidatesAllOldSessionsButKeepsOtherUsersLoggedIn() throws Exception {
        String first = login("alice", PASSWORD);
        String second = login("alice", PASSWORD);
        createUser("bob");
        String otherUser = login("bob", PASSWORD);
        expectExpense(first, 200);

        mvc.perform(put("/api/users/{id}/password", userId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"new-password-456\"}"))
                .andExpect(status().isNoContent());

        expectExpense(first, 401);
        expectExpense(second, 401);
        expectExpense(otherUser, 200);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-123\"}"))
                .andExpect(status().isUnauthorized());
        expectExpense(login("alice", "new-password-456"), 200);
    }

    @Test
    void enablingAnAccountDoesNotReviveSessionsFromBeforeDisablingIt() throws Exception {
        String token = login("alice", PASSWORD);
        updateUser(false);
        expectExpense(token, 401);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-123\"}"))
                .andExpect(status().isUnauthorized());

        updateUser(true);
        expectExpense(token, 401);
        expectExpense(login("alice", PASSWORD), 200);
    }

    @Test
    void deletingAnAccountReturns401ForItsOldToken() throws Exception {
        String token = login("alice", PASSWORD);
        mvc.perform(delete("/api/users/{id}", userId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)).andExpect(status().isNoContent());
        expectExpense(token, 401);
        createUser("alice");
        expectExpense(login("alice", PASSWORD), 200);
        expectExpense(token, 401);
    }

    @Test
    void logoutIsSharedByIndependentValidatorsAndOnlyEndsTheCurrentSession() throws Exception {
        String token = login("alice", PASSWORD);
        String otherSession = login("alice", PASSWORD);
        JwtTokenService otherValidator = context.getAutowireCapableBeanFactory()
                .createBean(JwtTokenService.class);
        assertEquals(userId, otherValidator.requireUserId("Bearer " + token));

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        expectExpense(token, 401);
        assertThrows(AuthenticationFailedException.class,
                () -> otherValidator.requireUserId("Bearer " + token));
        JwtTokenService recreatedValidator = context.getAutowireCapableBeanFactory()
                .createBean(JwtTokenService.class);
        assertThrows(AuthenticationFailedException.class,
                () -> recreatedValidator.requireUserId("Bearer " + token));
        expectExpense(otherSession, 200);
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isNoContent());
    }

    @Test
    void disabledBusinessSystemsAreExcludedFromPermissionsAndProtectedRequests() throws Exception {
        String token = login("alice", PASSWORD);
        updateSystem(false);
        mvc.perform(get("/api/users/{id}/permissions", userId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        expectExpense(token, 403);
        String loginResponse = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        expectExpense(json.readTree(loginResponse).path("accessToken").asText(), 403);

        updateSystem(true);
        expectExpense(token, 200);
    }

    @Test
    void roleRemovalAffectsTheNextRequestWithoutReissuingTheToken() throws Exception {
        String token = login("alice", PASSWORD);
        expectExpense(token, 200);
        mvc.perform(delete("/api/users/{id}/roles/{roleId}", userId, roleId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        expectExpense(token, 403);
        mvc.perform(put("/api/users/{id}/roles/{roleId}", userId, roleId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        expectExpense(token, 200);
    }

    @Test
    void signedTokensWithoutARegisteredSessionAreRejected() throws Exception {
        String unregistered = Jwts.builder().id(UUID.randomUUID().toString())
                .subject(Long.toString(userId)).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(SIGNING_KEY).compact();
        expectExpense(unregistered, 401);
    }

    @Test
    void malformedExpiredAndIncompleteTokensReturn401() throws Exception {
        mvc.perform(get("/api/demo/expenses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/demo/expenses").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
        expectExpense("not-a-jwt", 401);
        String valid = login("alice", PASSWORD);
        var claims = Jwts.parser().verifyWith(SIGNING_KEY).build().parseSignedClaims(valid).getPayload();
        for (String requiredClaim : new String[]{"jti", "sub", "exp", "iat"}) {
            var incomplete = new HashMap<String, Object>(claims);
            incomplete.remove(requiredClaim);
            expectExpense(Jwts.builder().claims(incomplete).signWith(SIGNING_KEY).compact(), 401);
        }
        expectExpense(Jwts.builder().claims(claims)
                .expiration(Date.from(Instant.now().minusSeconds(60))).signWith(SIGNING_KEY).compact(), 401);
        SecretKey wrongKey = Keys.hmacShaKeyFor("a-different-test-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        expectExpense(Jwts.builder().claims(claims).signWith(wrongKey).compact(), 401);
    }

    @Test
    void rollingBackPasswordResetAlsoRollsBackSessionRevocation() throws Exception {
        String token = login("alice", PASSWORD);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            rbacService.setPassword(userId, new SetPasswordRequest("new-password-456"));
            throw new IllegalStateException("simulate a failed account command");
        }));
        expectExpense(token, 200);
        expectExpense(login("alice", PASSWORD), 200);
    }

    @Test
    void loginWaitingForPasswordResetCannotUseTheOldPassword() throws Exception {
        var resetApplied = new CountDownLatch(1);
        var commitReset = new CountDownLatch(1);
        var loginStarted = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactionManager);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var reset = workers.submit(() -> transaction.executeWithoutResult(state -> {
                    rbacService.setPassword(userId, new SetPasswordRequest("new-password-456"));
                    resetApplied.countDown();
                    await(commitReset);
                }));
                await(resetApplied);
                var login = workers.submit(() -> {
                    loginStarted.countDown();
                    return rbacService.login(new LoginRequest("alice", PASSWORD));
                });
                await(loginStarted);
                assertThrows(TimeoutException.class, () -> login.get(200, TimeUnit.MILLISECONDS));
                commitReset.countDown();
                reset.get(10, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> login.get(10, TimeUnit.SECONDS));
                assertInstanceOf(AuthenticationFailedException.class, failure.getCause());
            } finally {
                commitReset.countDown();
            }
        }
        expectExpense(login("alice", "new-password-456"), 200);
    }

    @Test
    void passwordResetWaitingForLoginRevokesTheSessionThatLoginCreated() throws Exception {
        var sessionCreated = new CountDownLatch(1);
        var commitLogin = new CountDownLatch(1);
        var resetStarted = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactionManager);
        String token;
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var login = workers.submit(() -> transaction.execute(state -> {
                    var response = rbacService.login(new LoginRequest("alice", PASSWORD));
                    sessionCreated.countDown();
                    await(commitLogin);
                    return response.accessToken();
                }));
                await(sessionCreated);
                var reset = workers.submit(() -> {
                    resetStarted.countDown();
                    rbacService.setPassword(userId, new SetPasswordRequest("new-password-456"));
                });
                await(resetStarted);
                assertThrows(TimeoutException.class, () -> reset.get(200, TimeUnit.MILLISECONDS));
                commitLogin.countDown();
                token = login.get(10, TimeUnit.SECONDS);
                reset.get(10, TimeUnit.SECONDS);
            } finally {
                commitLogin.countDown();
            }
        }
        expectExpense(token, 401);
        expectExpense(login("alice", "new-password-456"), 200);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "concurrent operation did not reach its checkpoint");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long createUser(String username) throws Exception {
        long id = create("/api/users", """
                {"username":"%s","displayName":"测试用户"}
                """.formatted(username)).path("id").asLong();
        mvc.perform(put("/api/users/{id}/password", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/users/{id}/roles/{roleId}", id, roleId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)).andExpect(status().isNoContent());
        return id;
    }

    private JsonNode create(String path, String body) throws Exception {
        return json.readTree(mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String login(String username, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("accessToken").asText();
    }

    private void expectExpense(String token, int expectedStatus) throws Exception {
        mvc.perform(get("/api/demo/expenses").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    private void updateUser(boolean enabled) throws Exception {
        mvc.perform(put("/api/users/{id}", userId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"测试用户\",\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk());
    }

    private void updateSystem(boolean enabled) throws Exception {
        mvc.perform(put("/api/systems/{id}", systemId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"财务系统\",\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk());
    }
}
