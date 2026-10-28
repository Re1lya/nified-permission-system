package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.service.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 上走真实 HTTP、鉴权和事务；每个用例使用不同业务编号，不依赖执行顺序。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workflow-coverage;DB_CLOSE_DELAY=-1",
    "permission.integration.hr-secret=test-only-integration-secret-123456789",
    "permission.integration.oa-secret=test-only-integration-secret-123456789",
    "permission.application.http-demo-origin=http://demo.example.test:8080",
    "permission.audit.archive-cron=-"
})
@AutoConfigureMockMvc
public abstract class WorkflowTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate db;
    @Autowired protected RbacService rbac;
    @Autowired protected AdminBootstrapService bootstrap;
    @Autowired protected JsonMapper json;
    protected static final String PASSWORD = "Test-only-123";
    protected static final String SECRET = "test-only-integration-secret-123456789";
    protected String suffix, admin, userToken, managerToken;
    protected long user, manager, department, otherDepartment, role, post;

    @BeforeEach protected void prepareWorkflow() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        bootstrap.initialize("workflow_admin", PASSWORD);
        admin = login("workflow_admin");
        department = department("dept" + suffix);
        otherDepartment = department("outside" + suffix);
        role = rbac.createRole(new CreateRoleRequest("role" + suffix, "岗位" + suffix, null)).id();
        post = db.queryForObject("select id from sys_post where role_id=?", Long.class, role);
        user = employee("user" + suffix, department);
        manager = employee("manager" + suffix, department);
        db.update("insert into admin_department(user_id,department_id) values(?,?)", manager, department);
        db.update("insert into managed_role(role_id,department_id) values(?,?)", role, department);
        userToken = login("user" + suffix);
        managerToken = login("manager" + suffix);
    }

    protected String login(String username) {
        return "Bearer " + rbac.login(new LoginRequest(username, PASSWORD)).accessToken();
    }
    protected long department(String name) {
        db.update("insert into sys_department(name,deleted) values(?,false)", name);
        return db.queryForObject("select id from sys_department where name=?", Long.class, name);
    }
    protected long employee(String name, long dep) {
        long id = rbac.createUser(new CreateUserRequest(name, "测试员工")).id();
        rbac.setPassword(id, new SetPasswordRequest(PASSWORD));
        db.update("insert into employee_profile(user_id,department_id,post_id,phone,company) values(?,?,?,?,?)",
            id, dep, post, "139" + String.format("%08d", id), "测试公司");
        return id;
    }
    protected long system() {
        return rbac.createSystem(new CreateSystemRequest("sys" + suffix, "业务系统" + suffix, null)).id();
    }
    protected JsonNode call(AbstractMockHttpServletRequestBuilder<?> request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        String result = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return result.isBlank() ? json.nullNode() : json.readTree(result);
    }
    protected long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }
    protected JsonNode signed(String source, String id, Object payload, int expected) throws Exception {
        String body = json.writeValueAsString(payload);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(
            (source + "\n" + timestamp + "\n" + id + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        String response = mvc.perform(post("/api/integration/" + source)
            .header("X-Request-Id", id).header("X-Timestamp", timestamp).header("X-Signature", signature)
            .contentType("application/json").content(body))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
