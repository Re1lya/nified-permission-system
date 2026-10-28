package com.cdwy.permission;

import com.cdwy.permission.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompanyHttpWorkflowIntegrationTests extends WorkflowTestSupport {
    private MockMultipartFile workbook(String field, String[][] rows) throws Exception {
        return new MockMultipartFile(field, field + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            CompanyImportIntegrationTests.xlsx(rows));
    }

    @Test void multipartPreviewCommitAndCatalogCanBeReadThroughPublicContract() throws Exception {
        var preview = call(multipart("/api/company/imports/preview")
            .file(workbook("departments", new String[][]{{"部门名称", "部门经理", "上级部门"}, {"总公司", "", ""}, {"导入部门" + suffix, "", "总公司"}}))
            .file(workbook("employees", new String[][]{{"姓名", "用户名", "分公司", "部门", "岗位", "电话号码"},
                {"测试员工", "SG" + suffix, "总公司", "导入部门" + suffix, "导入岗位" + suffix, "13900000000"}}))
            .file(workbook("permissions", new String[][]{{"清单"}, {"软件系统", "功能模块", "功能点", "功能说明", "系统管理员", "公司领导", "部门经理", "项目经理", "普通员工"},
                {"导入系统" + suffix, "模块", "功能", "说明", "全部", "查看/审批", "查看/编辑", "无", "查看本人"}})), admin, null, 200);
        assertEquals(0, preview.get("errorCount").asInt());
        call(post("/api/company/imports/commit"), admin,
            Map.of("previewId", preview.get("previewId").asText(), "acknowledgeWarnings", true), 200);
        var catalog = call(get("/api/company/catalog").param("q", "导入系统" + suffix), admin, null, 200);
        assertEquals(1, catalog.get("total").asLong());
        assertEquals("导入系统" + suffix, catalog.get("items").get(0).get("system").asText());
        assertTrue(catalog.get("items").get(0).get("permissions").size() > 0);
        assertTrue(call(get("/api/company/imports"), admin, null, 200).isArray());
        assertTrue(call(get("/api/company/departments"), admin, null, 200).toString().contains("导入部门" + suffix));
        assertTrue(call(get("/api/company/posts"), admin, null, 200).toString().contains("导入岗位" + suffix));
        assertTrue(call(get("/api/company/templates"), admin, null, 200).size() > 0);
    }

    @Test void emptyWorkbookAndNonAdminImportAreRejected() throws Exception {
        for (String token : List.of(admin, userToken)) {
            mvc.perform(multipart("/api/company/imports/preview")
                .file(new MockMultipartFile("departments", new byte[0]))
                .file(new MockMultipartFile("employees", new byte[]{1}))
                .file(new MockMultipartFile("permissions", new byte[]{1}))
                .header("Authorization", token)).andExpect(status().is(token.equals(admin) ? 400 : 403));
        }
        call(post("/api/company/imports/commit"), admin, Map.of("previewId", ""), 400);
    }

    @Test void employeeSearchEscapesWildcardsAndSupportsDepartmentPagination() throws Exception {
        var result = call(get("/api/company/employees").param("q", "USER" + suffix)
            .param("departmentId", "" + department).param("size", "1"), admin, null, 200);
        assertEquals(1, result.get("total").asLong());
        assertEquals(user, result.get("items").get(0).get("id").asLong());
        assertEquals(0, call(get("/api/company/employees").param("q", "user" + suffix)
            .param("departmentId", "" + otherDepartment), admin, null, 200).get("total").asLong());
        assertEquals(0, call(get("/api/company/employees").param("q", "%_!"), admin, null, 200).get("total").asLong());
        assertEquals(0, call(get("/api/company/catalog").param("q", "%_!"), admin, null, 200).get("total").asLong());
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "page=100001", "size=0", "size=101"})
    void invalidPaginationCannotRunUnboundedQuery(String query) throws Exception {
        call(get("/api/company/employees?" + query), admin, null, 400);
        call(get("/api/company/catalog?" + query), admin, null, 400);
    }

    @Test void excessivelyLongSearchIsRejected() throws Exception {
        call(get("/api/company/employees").param("q", "x".repeat(129)), admin, null, 400);
        call(get("/api/company/catalog").param("q", "x".repeat(129)), admin, null, 400);
    }

    @Test void applyingTemplateIsIdempotentAndInvalidatesOnlyAffectedUsers() throws Exception {
        long permission = rbac.createPermission(new CreatePermissionRequest(system(), "p" + suffix, "查询", "/query")).id();
        db.update("insert into permission_template(name,permission_id) values(?,?)", "template" + suffix, permission);
        rbac.assignRole(user, role);
        long version = db.queryForObject("select version from account_policy where user_id=?", Long.class, user);
        var body = Map.of("name", "template" + suffix);
        assertEquals(1, call(post("/api/company/posts/" + post + "/template"), admin, body, 200).get("added").asInt());
        assertEquals(version + 1, db.queryForObject("select version from account_policy where user_id=?", Long.class, user));
        assertTrue(rbac.hasPermission(user, "p" + suffix));
        assertEquals(0, call(post("/api/company/posts/" + post + "/template"), admin, body, 200).get("added").asInt());
        assertEquals(version + 1, db.queryForObject("select version from account_policy where user_id=?", Long.class, user));
        assertEquals(0, count("select count(*) from sys_user_role where user_id=?", manager));
        call(post("/api/company/posts/99999999/template"), admin, body, 400);
        call(post("/api/company/posts/" + post + "/template"), admin, Map.of("name", "missing" + suffix), 400);
    }
}
