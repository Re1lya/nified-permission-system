package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HrApprovalWorkflowIntegrationTests extends WorkflowTestSupport {
    private Map<String,Object> profile(String action) {
        return new LinkedHashMap<>(Map.of("action", action, "userId", user, "displayName", "同步员工",
            "departmentId", department, "postId", post, "phone", "13800000000", "company", "测试公司"));
    }
    private Map<String,Object> application(String request) {
        return new LinkedHashMap<>(Map.of("action", "APPLICATION", "requestId", request, "userId", user, "roleIds", List.of(role)));
    }

    @Test void batchSyncCreatesExternalIdentitiesAndRepeatedJoinUpdatesWithoutDuplicates() throws Exception {
        String dep = "dep" + suffix, position = "post" + suffix, employee = "emp" + suffix;
        var join = profile("JOIN"); join.put("externalId", employee);
        join.put("departmentExternalId", dep); join.put("postExternalId", position);
        var records = List.of(Map.of("action", "DEPARTMENT", "externalId", dep, "name", "HR部门" + suffix),
            Map.of("action", "POST", "externalId", position, "name", "HR岗位" + suffix), join);
        var batch = Map.of("action", "SYNC", "mode", "FULL", "records", records);
        var result = signed("hr", "batch" + suffix, batch, 200);
        assertEquals(3, result.get("detail").get("processed").asInt());
        assertTrue(signed("hr", "batch" + suffix, batch, 200).get("reused").asBoolean());
        long id = db.queryForObject("select local_id from hr_identity where kind='EMPLOYEE' and external_id=?", Long.class, employee);
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", id));
        assertTrue(db.queryForObject("select must_change_password from account_policy where user_id=?", Boolean.class, id));
        join.put("displayName", "更名员工");
        signed("hr", "joinagain" + suffix, join, 200);
        assertEquals("更名员工", db.queryForObject("select display_name from sys_user where id=?", String.class, id));
        assertEquals(1, count("select count(*) from hr_identity where kind='EMPLOYEE' and external_id=?", employee));
        join.remove("departmentExternalId"); join.put("departmentId", otherDepartment);
        signed("hr", "joinconflict" + suffix, join, 409);
        var reconcile = signed("hr", "reconcile" + suffix,
            Map.of("action", "RECONCILE", "records", List.of(Map.of("externalId", employee), Map.of("externalId", "missing" + suffix))), 200).get("detail");
        assertEquals(1, reconcile.get("matched").asInt());
        assertTrue(reconcile.get("missing").toString().contains("missing" + suffix));
        signed("hr", "emptyreconcile" + suffix, Map.of("action", "RECONCILE", "records", List.of()), 200);
        assertTrue(db.queryForObject("select enabled from sys_user where id=?", Boolean.class, id), "对账缺失不能自动停用");
    }

    @Test void hrMatchingAndRenamingPreserveLocalDepartmentAndPostIdentity() throws Exception {
        signed("hr", "matchdep" + suffix, Map.of("action", "DEPARTMENT", "externalId", "d" + suffix, "name", "dept" + suffix), 200);
        signed("hr", "matchpost" + suffix, Map.of("action", "POST", "externalId", "p" + suffix, "name", "岗位" + suffix), 200);
        signed("hr", "renamedep" + suffix, Map.of("action", "DEPARTMENT", "externalId", "d" + suffix, "name", "新部门" + suffix), 200);
        signed("hr", "renamepost" + suffix, Map.of("action", "POST", "externalId", "p" + suffix, "name", "新岗位" + suffix), 200);
        assertEquals(department, db.queryForObject("select local_id from hr_identity where kind='DEPARTMENT' and external_id=?", Long.class, "d" + suffix));
        assertEquals("新部门" + suffix, db.queryForObject("select name from sys_department where id=?", String.class, department));
        assertEquals("新岗位" + suffix, db.queryForObject("select name from sys_role where id=?", String.class, role));
        signed("hr", "cycle" + suffix, Map.of("action", "DEPARTMENT", "externalId", "d" + suffix,
            "name", "循环部门", "parentExternalId", "d" + suffix), 400);
        assertEquals("新部门" + suffix, db.queryForObject("select name from sys_department where id=?", String.class, department));
        signed("hr", "parentmissing" + suffix, Map.of("action", "DEPARTMENT", "externalId", "child" + suffix,
            "name", "子部门", "parentExternalId", "missing" + suffix), 400);
    }

    @Test void failedBatchRollsBackEarlierRecordsButKeepsFailureEvidence() throws Exception {
        var body = Map.of("action", "SYNC", "mode", "INCREMENTAL", "records", List.of(
            Map.of("action", "DEPARTMENT", "externalId", "rollback" + suffix, "name", "回滚部门" + suffix),
            Map.of("action", "POST", "externalId", "bad" + suffix, "name", "")));
        String id = "failed" + suffix;
        signed("hr", id, body, 400); signed("hr", id, body, 400);
        assertEquals(0, count("select count(*) from sys_department where name=?", "回滚部门" + suffix));
        assertEquals(0, count("select count(*) from hr_identity where external_id=?", "rollback" + suffix));
        assertEquals(0, count("select count(*) from integration_event where request_id=?", id));
        assertEquals(1, count("select count(*) from sync_failure where request_id=? and status='OPEN'", id));
        signed("hr", id, Map.of("action", "SYNC", "mode", "FULL", "records", List.of()), 409);
    }

    @Test void unchangedFailedEventCanSucceedAfterReferenceIsSynchronized() throws Exception {
        var join = profile("JOIN"); join.put("externalId", "late" + suffix); join.put("departmentExternalId", "later" + suffix);
        signed("hr", "retry" + suffix, join, 400);
        signed("hr", "reference" + suffix, Map.of("action", "DEPARTMENT", "externalId", "later" + suffix, "name", "dept" + suffix), 200);
        signed("hr", "retry" + suffix, join, 200);
        assertEquals("RESOLVED", db.queryForObject("select status from sync_failure where request_id=?", String.class, "retry" + suffix));
    }

    @Test void transferAtomicallyReplacesRolesAndNotifiesBothDepartments() throws Exception {
        rbac.assignRole(user, role);
        long newRole = rbac.createRole(new CreateRoleRequest("next" + suffix, "新岗" + suffix, null)).id();
        long newPost = db.queryForObject("select id from sys_post where role_id=?", Long.class, newRole);
        var change = profile("TRANSFER"); change.put("departmentId", otherDepartment); change.put("postId", newPost);
        change.put("roleIds", List.of(role));
        signed("hr", "invalidtransfer" + suffix, change, 400);
        assertEquals(department, db.queryForObject("select department_id from employee_profile where user_id=?", Long.class, user));
        assertEquals(role, db.queryForObject("select role_id from sys_user_role where user_id=?", Long.class, user));
        long before = db.queryForObject("select version from account_policy where user_id=?", Long.class, user);
        change.put("roleIds", List.of(newRole, newRole));
        signed("hr", "transfer" + suffix, change, 200);
        assertEquals(otherDepartment, db.queryForObject("select department_id from employee_profile where user_id=?", Long.class, user));
        assertEquals(newRole, db.queryForObject("select role_id from sys_user_role where user_id=?", Long.class, user));
        assertEquals(2, count("select count(*) from platform_notification where user_id=? and kind in('TRANSFER_IN','TRANSFER_OUT')", user));
        assertTrue(db.queryForObject("select version from account_policy where user_id=?", Long.class, user) > before);
        change.put("roleIds", List.of()); signed("hr", "clear" + suffix, change, 200);
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", user));
    }

    @Test void ordinaryUpdateCannotMoveOrganizationOrModifyAdministrator() throws Exception {
        var update = profile("UPDATE");
        signed("hr", "update" + suffix, update, 200);
        assertEquals("同步员工", db.queryForObject("select display_name from sys_user where id=?", String.class, user));
        update.put("departmentId", otherDepartment); signed("hr", "move" + suffix, update, 409);
        update.put("departmentId", department); update.put("displayName", ""); signed("hr", "blank" + suffix, update, 400);
        update.put("userId", manager); signed("hr", "admin" + suffix, update, 403);
        update.put("userId", user); update.put("action", "UNSUPPORTED"); signed("hr", "unknown" + suffix, update, 400);
        update.put("action", "TRANSFER"); signed("hr", "noroles" + suffix, update, 400);
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "company", "departmentId", "postId"})
    void incompleteProfileCannotCreateEmployee(String missing) throws Exception {
        var join = profile("JOIN"); join.put("externalId", "invalid" + suffix); join.remove(missing);
        signed("hr", "invalid" + suffix, join, 400);
        assertEquals(0, count("select count(*) from hr_identity where external_id=?", "invalid" + suffix));
    }

    @Test void invalidBatchReconciliationAndExternalIdentityAreRejected() throws Exception {
        signed("hr", "badmode" + suffix, Map.of("action", "SYNC", "mode", "BAD", "records", List.of()), 400);
        signed("hr", "norecords" + suffix, Map.of("action", "SYNC", "mode", "FULL"), 400);
        signed("hr", "nullitem" + suffix, Map.of("action", "SYNC", "mode", "FULL", "records", Arrays.asList((Object)null)), 400);
        signed("hr", "reconcilemissing" + suffix, Map.of("action", "RECONCILE"), 400);
        signed("hr", "reconcileduplicate" + suffix, Map.of("action", "RECONCILE", "records", List.of(Map.of("externalId", "same"), Map.of("externalId", "same"))), 400);
        signed("hr", "noidentity" + suffix, Map.of("action", "POST", "name", "岗位"), 400);
        signed("hr", "noaction" + suffix, Map.of(), 400);
        signed("hr", "nouser" + suffix, Map.of("action", "LEAVE", "externalId", "missing" + suffix), 400);
    }

    @Test void approvalGrantAndQueriesAreIdempotentAndConflictingDecisionIsRejected() throws Exception {
        String request = "application" + suffix;
        var application = application(request);
        signed("oa", "register" + suffix, application, 200);
        signed("oa", "duplicate" + suffix, application, 200);
        assertEquals(1, count("select count(*) from approval_record where request_id=?", request));
        application.put("userId", manager); signed("oa", "conflict" + suffix, application, 409);
        var decision = Map.of("action", "RESULT", "requestId", request, "decision", "APPROVED");
        signed("oa", "approve" + suffix, decision, 200);
        signed("oa", "sameapproval" + suffix, decision, 200);
        assertEquals(1, count("select count(*) from sys_user_role where user_id=? and role_id=?", user, role));
        var status = signed("oa", "status" + suffix, Map.of("action", "QUERY_APPLICATION_STATUS", "requestId", request), 200);
        assertEquals("APPROVED", status.get("detail").get("application").get("decision").asText());
        var permissions = signed("oa", "permissions" + suffix, Map.of("action", "QUERY_PERMISSIONS", "userId", user), 200);
        assertNotNull(permissions.get("detail").get("permissionVersion"));
        signed("oa", "reject" + suffix, Map.of("action", "RESULT", "requestId", request, "decision", "REJECTED"), 409);
    }

    @Test void cancelledAndDisabledApplicantNeverReceiveRole() throws Exception {
        String request = "cancel" + suffix;
        signed("oa", "register" + suffix, application(request), 200);
        long id = db.queryForObject("select id from approval_record where request_id=?", Long.class, request);
        signed("oa", "cancelled" + suffix, Map.of("action", "RESULT", "approvalId", id, "decision", "CANCELLED"), 200);
        String second = "disabled" + suffix;
        signed("oa", "register2" + suffix, application(second), 200);
        db.update("update sys_user set enabled=false where id=?", user);
        signed("oa", "approve" + suffix, Map.of("action", "RESULT", "requestId", second, "decision", "APPROVED"), 409);
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", user));
        assertEquals("PENDING", db.queryForObject("select decision from approval_record where request_id=?", String.class, second));
    }

    @Test void malformedApprovalAndQueriesDoNotMutateAssignments() throws Exception {
        signed("oa", "nouser" + suffix, Map.of("action", "QUERY_PERMISSIONS"), 400);
        signed("oa", "norequest" + suffix, Map.of("action", "QUERY_APPLICATION_STATUS"), 400);
        signed("oa", "missingrequest" + suffix, Map.of("action", "QUERY_APPLICATION_STATUS", "requestId", suffix), 404);
        signed("oa", "baddecision" + suffix, Map.of("action", "RESULT", "requestId", suffix, "decision", "UNKNOWN"), 400);
        signed("oa", "missingapplication" + suffix, Map.of("action", "RESULT", "requestId", suffix, "decision", "APPROVED"), 400);
        var invalid = application(suffix); invalid.put("roleIds", List.of()); signed("oa", "norole" + suffix, invalid, 400);
        invalid.put("roleIds", List.of(99999999)); signed("oa", "missingrole" + suffix, invalid, 400);
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", user));
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "timestamp", "hex"})
    void malformedOrExpiredSignatureNeverStartsBusinessTransaction(String mode) throws Exception {
        String timestamp = mode.equals("expired") ? Long.toString(Instant.now().minusSeconds(600).getEpochSecond())
            : mode.equals("timestamp") ? "invalid" : Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post("/api/integration/hr").header("X-Request-Id", suffix).header("X-Timestamp", timestamp)
            .header("X-Signature", "not-hex").contentType("application/json")
            .content(json.writeValueAsString(Map.of("action", "LEAVE", "userId", user)))).andExpect(status().isForbidden());
        assertTrue(db.queryForObject("select enabled from sys_user where id=?", Boolean.class, user));
        assertEquals(0, count("select count(*) from integration_event where request_id=?", suffix));
    }

    @Test void failureResolutionAndIdentityListingRequireSuperAdministrator() throws Exception {
        signed("hr", "failure" + suffix, Map.of("action", "POST", "name", "缺少外部编号"), 400);
        long id = db.queryForObject("select id from sync_failure where request_id=?", Long.class, "failure" + suffix);
        call(get("/api/platform/sync/failures"), managerToken, null, 403);
        assertTrue(call(get("/api/platform/sync/failures"), admin, null, 200).toString().contains("failure" + suffix));
        call(post("/api/platform/sync/failures/" + id + "/resolve"), admin, Map.of("reason", "人工核对后废弃此事件"), 200);
        assertEquals("RESOLVED", db.queryForObject("select status from sync_failure where id=?", String.class, id));
        call(post("/api/platform/sync/failures/" + id + "/resolve"), admin, Map.of("reason", "重复处理"), 400);
        signed("hr", "identity" + suffix, Map.of("action", "DEPARTMENT", "externalId", "d" + suffix, "name", "dept" + suffix), 200);
        assertTrue(call(get("/api/platform/sync/identities?kind=DEPARTMENT"), admin, null, 200).toString().contains("d" + suffix));
        for (String query : List.of("kind=INVALID", "page=-1", "page=100001"))
            call(get("/api/platform/sync/identities?" + query), admin, null, 400);
    }
}
