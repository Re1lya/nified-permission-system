package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.platform.ExpiryReminder;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class OrganizationAuditWorkflowIntegrationTests extends WorkflowTestSupport {
    private static final String ORG = "/api/platform/organization";
    @Autowired ExpiryReminder reminders;

    @Test void departmentTreeRejectsCyclesAndInvalidManagerWithoutPartialUpdates() throws Exception {
        long child = call(post(ORG + "/departments"), admin,
            Map.of("name", "child" + suffix, "parentId", department), 200).get("id").asLong();
        call(put(ORG + "/departments/" + department), admin,
            Map.of("name", "cycle" + suffix, "parentId", child), 400);
        assertNull(db.queryForObject("select parent_id from sys_department where id=?", Long.class, department));
        call(put(ORG + "/departments/" + department), admin,
            Map.of("name", "changed" + suffix, "managerId", employee("outside" + suffix, otherDepartment)), 400);
        assertEquals("dept" + suffix, db.queryForObject("select name from sys_department where id=?", String.class, department));
        call(post(ORG + "/departments"), admin, Map.of("name", "rollback" + suffix, "managerId", user), 400);
        assertEquals(0, count("select count(*) from sys_department where name=?", "rollback" + suffix), "flush 后经理校验失败，部门插入也必须回滚");
        call(put(ORG + "/departments/" + department), admin, Map.of("name", "valid" + suffix, "managerId", user), 200);
        assertEquals(user, db.queryForObject("select manager_id from sys_department where id=?", Long.class, department));
        call(put(ORG + "/departments/" + department), admin, Map.of("name", "valid" + suffix), 200);
        assertNull(db.queryForObject("select manager_id from sys_department where id=?", Long.class, department));
        call(post(ORG + "/departments"), admin, Map.of("name", "bad" + suffix, "parentId", 99999999), 400);
        call(put(ORG + "/departments/" + department), admin, Map.of("name", "bad", "parentId", 99999999), 400);
        call(put(ORG + "/departments/99999999"), admin, Map.of("name", "absent"), 404);
    }

    @Test void departmentDeletionProtectsReferencesAndRemovesDelegationForEmptyDepartment() throws Exception {
        call(delete(ORG + "/departments/" + department), admin, null, 409);
        long parent = department("parent" + suffix);
        long child = call(post(ORG + "/departments"), admin, Map.of("name", "child" + suffix, "parentId", parent), 200).get("id").asLong();
        call(delete(ORG + "/departments/" + parent), admin, null, 409);
        call(delete(ORG + "/departments/" + child), admin, null, 200);
        long permission = rbac.createPermission(new CreatePermissionRequest(system(), "p" + suffix, "查询", "/query")).id();
        call(put(ORG + "/grant-limits/" + parent + "/" + permission), admin, null, 200);
        call(put(ORG + "/grant-limits/" + parent + "/" + permission), admin, null, 200);
        assertEquals(1, count("select count(*) from department_permission where department_id=?", parent));
        call(delete(ORG + "/departments/" + parent), admin, null, 200);
        assertEquals(0, count("select count(*) from department_permission where department_id=?", parent));
        assertTrue(db.queryForObject("select deleted from sys_department where id=?", Boolean.class, parent));
    }

    @Test void emptyDepartmentWithManagementBindingCannotBeDeleted() throws Exception {
        db.update("insert into admin_department(user_id,department_id) values(?,?)", manager, otherDepartment);
        call(delete(ORG + "/departments/" + otherDepartment), admin, null, 409);
        db.update("delete from admin_department where department_id=?", otherDepartment);
        long extraRole = rbac.createRole(new CreateRoleRequest("extra" + suffix, "额外岗位" + suffix, null)).id();
        db.update("insert into managed_role(role_id,department_id) values(?,?)", extraRole, otherDepartment);
        call(delete(ORG + "/departments/" + otherDepartment), admin, null, 409);
        assertFalse(db.queryForObject("select deleted from sys_department where id=?", Boolean.class, otherDepartment));
    }

    @Test void postAndRoleNamesStayConsistentAndDeletionProtectsAssignments() throws Exception {
        var created = call(post(ORG + "/posts"), admin,
            Map.of("name", "new" + suffix, "code", "new" + suffix, "departmentId", department), 200);
        long id = created.get("id").asLong(), roleId = created.get("roleId").asLong();
        call(put(ORG + "/posts/" + id), admin, Map.of("name", "renamed" + suffix), 200);
        assertEquals("renamed" + suffix, db.queryForObject("select name from sys_post where id=?", String.class, id));
        assertEquals("renamed" + suffix, db.queryForObject("select name from sys_role where id=?", String.class, roleId));
        rbac.assignRole(user, roleId);
        call(delete(ORG + "/posts/" + id), admin, null, 409);
        rbac.removeRole(user, roleId);
        call(delete(ORG + "/posts/" + id), admin, null, 200);
        assertTrue(db.queryForObject("select deleted from sys_post where id=?", Boolean.class, id));
        admin = login("workflow_admin");
        call(delete(ORG + "/posts/" + post), admin, null, 409);
        call(delete(ORG + "/posts/99999999"), admin, null, 404);
        call(put(ORG + "/posts/99999999"), admin, Map.of("name", "missing"), 404);
        call(post(ORG + "/posts"), admin, Map.of("name", "shared" + suffix, "code", "shared" + suffix), 200);
    }

    @Test void dictionaryUsesSoftDeletionAndRejectsRepeatedChanges() throws Exception {
        db.update("insert into data_dictionary(category,code,name,deleted) values(?,?,?,false)", "test", suffix, "原值");
        long id = db.queryForObject("select id from data_dictionary where code=?", Long.class, suffix);
        var body = Map.of("category", "updated", "code", "u" + suffix, "name", "修改值");
        call(put(ORG + "/dictionary/" + id), admin, body, 200);
        assertEquals("修改值", db.queryForObject("select name from data_dictionary where id=?", String.class, id));
        call(delete(ORG + "/dictionary/" + id), admin, null, 200);
        assertTrue(db.queryForObject("select deleted from data_dictionary where id=?", Boolean.class, id));
        call(delete(ORG + "/dictionary/" + id), admin, null, 404);
        call(put(ORG + "/dictionary/" + id), admin, body, 404);
    }

    @Test void scopedRoleListingAndRevocationRespectDepartmentBoundary() throws Exception {
        long permission = rbac.createPermission(new CreatePermissionRequest(system(), "p" + suffix, "查询", "/query")).id();
        long shared = rbac.createRole(new CreateRoleRequest("shared" + suffix, "共享" + suffix, null)).id();
        call(put(ORG + "/roles/" + shared + "/permissions/" + permission), managerToken, null, 403);
        call(put(ORG + "/roles/" + role + "/permissions/" + permission), admin, null, 200);
        call(delete(ORG + "/roles/" + role + "/permissions/" + permission), managerToken, null, 200);
        assertEquals(0, count("select count(*) from sys_role_permission where role_id=?", role));
        assertTrue(call(get(ORG + "/roles"), admin, null, 200).toString().contains("shared" + suffix));
        String managed = call(get(ORG + "/roles"), managerToken, null, 200).toString();
        assertTrue(managed.contains("role" + suffix)); assertFalse(managed.contains("shared" + suffix));
        call(get(ORG + "/roles"), userToken, null, 403);
    }

    @Test void administratorMembershipRevokesSessionsAndProtectsBuiltInIdentity() throws Exception {
        call(put("/api/platform/super-administrators/" + user), admin, Map.of("administrator", true), 200);
        assertTrue(db.queryForObject("select administrator from sys_user where id=?", Boolean.class, user));
        call(get("/api/platform/self"), userToken, null, 401);
        String promoted = login("user" + suffix);
        long builtIn = db.queryForObject("select user_id from sys_account where username='workflow_admin'", Long.class);
        call(put("/api/platform/super-administrators/" + builtIn), admin, Map.of("administrator", false), 403);
        call(put("/api/platform/super-administrators/" + builtIn), promoted, Map.of("administrator", false), 403);
        call(put("/api/platform/super-administrators/99999999"), admin, Map.of("administrator", true), 404);
        call(put("/api/platform/super-administrators/" + user), admin, Map.of("administrator", false), 200);
        call(get("/api/platform/self"), promoted, null, 401);
        db.update("update sys_user set enabled=false where id=?", user);
        call(put("/api/platform/super-administrators/" + user), admin, Map.of("administrator", true), 409);
        long noPassword = rbac.createUser(new CreateUserRequest("nopassword" + suffix, "未初始化")).id();
        call(put("/api/platform/super-administrators/" + noPassword), admin, Map.of("administrator", true), 409);
        call(get("/api/platform/administrators"), managerToken, null, 403);
        assertTrue(call(get("/api/platform/administrators"), admin, null, 200).toString().contains("workflow_admin"));
    }

    @Test void profileAndOrganizationViewsAreScoped() throws Exception {
        call(put("/api/platform/users/" + user + "/profile"), managerToken, Map.of("displayName", "更名员工"), 200);
        assertEquals("更名员工", db.queryForObject("select display_name from sys_user where id=?", String.class, user));
        call(put("/api/platform/users/99999999/profile"), admin, Map.of("displayName", "不存在"), 404);
        assertTrue(call(get(ORG + "/read-only"), admin, null, 200).toString().contains("outside" + suffix));
        String scoped = call(get(ORG + "/read-only"), managerToken, null, 200).toString();
        assertTrue(scoped.contains("dept" + suffix)); assertFalse(scoped.contains("outside" + suffix));
        call(get(ORG + "/read-only"), userToken, null, 403);
    }

    @Test void reviewKeepAndRevokeUpdateEffectivePermissionsAndRejectStaleReview() throws Exception {
        long permission = rbac.createPermission(new CreatePermissionRequest(system(), "p" + suffix, "查询", "/query")).id();
        rbac.assignPermission(role, permission); rbac.assignRole(user, role);
        long outside = employee("outside" + suffix, otherDepartment); rbac.assignRole(outside, role);
        var body = new LinkedHashMap<String,Object>(Map.of("userId", user, "roleId", role, "decision", "KEEP", "reason", "职责仍需"));
        call(post("/api/platform/reviews"), managerToken, body, 200);
        assertTrue(rbac.hasPermission(user, "p" + suffix));
        var candidates = call(get("/api/platform/reviews/candidates"), managerToken, null, 200);
        assertEquals(1, candidates.get("total").asLong());
        assertEquals(user, candidates.get("items").get(0).get("user_id").asLong());
        body.put("decision", "REVOKE");
        call(post("/api/platform/reviews"), managerToken, body, 200);
        assertFalse(rbac.hasPermission(user, "p" + suffix));
        call(post("/api/platform/reviews"), managerToken, body, 409);
        assertEquals(2, count("select count(*) from permission_review where user_id=?", user));
        assertEquals(2, call(get("/api/platform/reviews"), managerToken, null, 200).get("total").asLong());
        body.put("userId", outside); call(post("/api/platform/reviews"), managerToken, body, 403);
        call(get("/api/platform/reviews"), login("user" + suffix), null, 403);
    }

    @ParameterizedTest @ValueSource(strings = {"/reviews?page=-1", "/reviews?page=100001",
        "/reviews/candidates?page=-1", "/reviews/candidates?page=100001", "/audit/search?page=-1",
        "/audit/search?page=100001", "/audit/search?size=0", "/audit/search?size=101",
        "/audit/search?from=2026-01-02T00:00:00Z&until=2026-01-01T00:00:00Z"})
    void invalidReviewAndAuditQueryIsRejected(String path) throws Exception {
        call(get("/api/platform" + path), admin, null, 400);
    }

    @Test void auditSearchFiltersActorTimeKindAndDepartment() throws Exception {
        Instant now = Instant.now();
        db.update("insert into audit_event(actor_id,department_id,kind,target,result,created_at) values(?,?,?,?,?,?)",
            user, department, "TEST_" + suffix, "inside", "SUCCESS", now);
        db.update("insert into audit_event(actor_id,department_id,kind,target,result,created_at) values(?,?,?,?,?,?)",
            user, otherDepartment, "TEST_" + suffix, "outside", "SUCCESS", now);
        var query = get("/api/platform/audit/search").param("kind", "TEST_" + suffix).param("actorId", "" + user)
            .param("from", now.minusSeconds(1).toString()).param("until", now.plusSeconds(1).toString());
        var result = call(query, managerToken, null, 200);
        assertEquals(1, result.get("total").asLong());
        assertEquals("inside", result.get("items").get(0).get("target").asText());
        assertEquals(2, call(get("/api/platform/audit/search").param("kind", "TEST_" + suffix), admin, null, 200).get("total").asLong());
        assertEquals(0, call(get("/api/platform/audit/search?archived=true").param("kind", "TEST_" + suffix), admin, null, 200).get("total").asLong());
    }

    @Test void expiryReminderDeduplicatesAndNotificationVisibilityIsScoped() throws Exception {
        db.update("update account_policy set person_type='EXTERNAL',expires_at=? where user_id=?", Instant.now().plusSeconds(86400), user);
        reminders.remind(); reminders.remind();
        assertEquals(1, count("select count(*) from platform_notification where user_id=? and kind='EXPIRY_REMINDER'", user));
        assertTrue(call(get("/api/platform/notifications"), managerToken, null, 200).toString().contains("EXPIRY_REMINDER"));
        db.update("insert into platform_notification(department_id,user_id,kind,message,created_at) values(?,?,'TEST',?,?)",
            otherDepartment, user, "outside" + suffix, Instant.now());
        assertFalse(call(get("/api/platform/notifications"), managerToken, null, 200).toString().contains("outside" + suffix));
        assertTrue(call(get("/api/platform/notifications"), admin, null, 200).toString().contains("outside" + suffix));
        call(get("/api/platform/notifications"), userToken, null, 403);
        db.update("update platform_notification set created_at=? where user_id=?", Instant.now().minusSeconds(8 * 86400), user);
        reminders.remind();
        assertEquals(2, count("select count(*) from platform_notification where user_id=? and kind='EXPIRY_REMINDER'", user));
    }
}
