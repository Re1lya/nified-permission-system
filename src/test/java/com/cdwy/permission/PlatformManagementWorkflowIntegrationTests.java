package com.cdwy.permission;

import com.cdwy.permission.dto.CreateRoleRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PlatformManagementWorkflowIntegrationTests extends WorkflowTestSupport {
    @Test void accountStateAndDeletionRevokeAccessAndProtectAdministrators() throws Exception {
        long builtIn = db.queryForObject("select user_id from sys_account where username='workflow_admin'", Long.class);
        call(put("/api/platform/users/" + manager + "/state"), managerToken, Map.of("enabled", false), 403);
        call(delete("/api/platform/users/" + manager), managerToken, null, 403);
        call(put("/api/platform/users/" + builtIn + "/state"), admin, Map.of("enabled", false), 403);
        call(delete("/api/platform/users/" + builtIn), admin, null, 403);
        call(put("/api/platform/users/" + user + "/state"), managerToken, Map.of("enabled", false), 200);
        call(get("/api/platform/self"), userToken, null, 401);
        call(put("/api/platform/users/" + user + "/state"), managerToken, Map.of("enabled", true), 200);
        String enabled = login("user" + suffix);
        call(delete("/api/platform/users/" + user), managerToken, null, 200);
        call(get("/api/platform/self"), enabled, null, 401);
        assertTrue(db.queryForObject("select deleted from account_policy where user_id=?", Boolean.class, user));
        call(put("/api/platform/users/99999999/state"), admin, Map.of("enabled", false), 404);
    }

    @Test void passwordChangeRejectsWrongOldSameAndWeakPasswordWithoutRevokingSession() throws Exception {
        call(post("/api/platform/self/password"), userToken, Map.of("oldPassword", "Wrong-pass-1", "newPassword", "New-pass-123"), 403);
        call(post("/api/platform/self/password"), userToken, Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD), 400);
        call(post("/api/platform/self/password"), userToken, Map.of("oldPassword", PASSWORD, "newPassword", "weak"), 400);
        call(get("/api/platform/self"), userToken, null, 200);
        call(post("/api/platform/users/" + manager + "/password"), managerToken, Map.of("password", "New-pass-123"), 403);
    }

    @Test void departmentAdministratorBindingIsIdempotentAndRevocable() throws Exception {
        var body = Map.of("departmentId", department);
        call(put("/api/platform/administrators/" + user), admin, body, 200);
        call(put("/api/platform/administrators/" + user), admin, body, 200);
        assertEquals(1, count("select count(*) from admin_department where user_id=?", user));
        assertTrue(call(get("/api/platform/self"), userToken, null, 200).get("departmentAdmin").asBoolean());
        call(delete("/api/platform/administrators/" + user + "/" + department), admin, null, 200);
        call(get("/api/platform/users"), userToken, null, 403);
        long builtIn = db.queryForObject("select user_id from sys_account where username='workflow_admin'", Long.class);
        call(put("/api/platform/administrators/" + builtIn), admin, body, 400);
        db.update("update sys_user set enabled=false where id=?", user);
        call(put("/api/platform/administrators/" + user), admin, body, 400);
    }

    @Test void roleAndSessionManagementRespectOwnershipAndDepartmentScope() throws Exception {
        long shared = rbac.createRole(new CreateRoleRequest("shared" + suffix, "共享" + suffix, null)).id();
        call(put("/api/platform/users/" + user + "/roles/" + shared), managerToken, null, 403);
        call(put("/api/platform/users/" + user + "/roles/" + role), admin, null, 200);
        call(delete("/api/platform/users/" + user + "/roles/" + role), managerToken, null, 200);
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", user));
        userToken = login("user" + suffix);
        String own = call(get("/api/platform/sessions"), userToken, null, 200).get(0).get("id").asText();
        call(delete("/api/platform/sessions/" + own), userToken, null, 200);
        assertEquals(0, count("select count(*) from sys_login_session where id=?", own));
        call(delete("/api/platform/sessions/missing"), admin, null, 200);
        userToken = login("user" + suffix);
        String managed = call(get("/api/platform/sessions"), userToken, null, 200).get(0).get("id").asText();
        call(delete("/api/platform/sessions/" + managed), managerToken, null, 200);
        assertEquals(0, count("select count(*) from sys_login_session where id=?", managed));
        call(get("/api/platform/sessions?scope=invalid"), admin, null, 400);
    }

    @Test void managementListsAndDictionaryCreationUseCurrentContract() throws Exception {
        assertTrue(call(get("/api/platform/users"), admin, null, 200).get("total").asLong() >= 3);
        assertEquals(2, call(get("/api/platform/users"), managerToken, null, 200).get("total").asLong());
        call(post("/api/platform/dictionary"), admin, Map.of("category", "test", "code", suffix, "name", "字典" + suffix), 200);
        assertTrue(call(get("/api/platform/dictionary"), managerToken, null, 200).toString().contains("字典" + suffix));
        assertTrue(call(get("/api/platform/integrations"), admin, null, 200).isArray());
        assertEquals(0, call(get("/api/platform/self/applications"), userToken, null, 200).size());
        assertTrue(call(get("/api/platform/approvals"), admin, null, 200).isArray());
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "page=100001", "size=0", "size=101"})
    void managementPaginationHasUpperAndLowerBounds(String query) throws Exception {
        call(get("/api/platform/users?" + query), admin, null, 400);
        call(get("/api/platform/audit?" + query), admin, null, 400);
    }
}
