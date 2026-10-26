package com.cdwy.permission;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cdwy.permission.dto.LoginRequest;
import com.cdwy.permission.service.AdminBootstrapService;
import com.cdwy.permission.service.RbacService;
import com.cdwy.permission.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AdminAccessIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AdminBootstrapService bootstrap;
    @Autowired RbacService rbac;
    @Autowired UserAccountRepository users;
    String admin, ordinary, suffix;
    long userId;
    static final String PASSWORD = "integration-only-123";

    @BeforeEach
    void setup() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        bootstrap.initialize("operator", PASSWORD);
        admin = login("operator", PASSWORD);
        userId = create("users", Map.of("username", "user-"+suffix, "displayName", "演示用户"));
        call(put("/api/users/{id}/password", userId), admin, Map.of("password", PASSWORD), 204);
        ordinary = login("user-"+suffix, PASSWORD);
    }

    @Test
    void allManagementRoutesRejectAnonymousAndOrdinaryUsersBeforeBinding() throws Exception {
        // Exercise actual supported operations, including password reset and grants.
        String[][] routes = {{"GET","/api/users"},{"POST","/api/users"},{"PUT","/api/users/1"},{"DELETE","/api/users/1"},
                {"PUT","/api/users/1/password"},{"PUT","/api/users/1/roles/1"},{"DELETE","/api/users/1/roles/1"},
                {"GET","/api/users/1/roles"},{"GET","/api/users/1/permissions"},{"GET","/api/users/1/permissions/check?code=x"},
                {"GET","/api/roles"},{"POST","/api/roles"},{"PUT","/api/roles/1"},{"DELETE","/api/roles/1"},
                {"GET","/api/roles/1/permissions"},{"PUT","/api/roles/1/permissions/1"},{"DELETE","/api/roles/1/permissions/1"},
                {"GET","/api/systems"},{"POST","/api/systems"},{"PUT","/api/systems/1"},{"DELETE","/api/systems/1"},
                {"GET","/api/permissions"},{"POST","/api/permissions"},{"PUT","/api/permissions/1"},{"DELETE","/api/permissions/1"}};
        for (var route : routes) {
            for (String identity : new String[]{"", ordinary}) {
                call(request(org.springframework.http.HttpMethod.valueOf(route[0]), route[1]), identity, Map.of(), identity.isEmpty()?401:403);
            }
        }
        assertFalse(users.findById(userId).orElseThrow().isAdministrator());
    }

    @Test
    void overlappingRolesUnionRevocationAndLogoutRespectPermissionVersions() throws Exception {
        long system = create("systems", Map.of("code","sys-"+suffix,"name","财务"));
        long viewer = create("roles", Map.of("code","viewer-"+suffix,"name","查看员"));
        long reviewer = create("roles", Map.of("code","reviewer-"+suffix,"name","复核员"));
        // Use a unique namespace for the union test and a shared real expense permission for the protected endpoint.
        long expense = rbac.listPermissions(null).stream().filter(p -> p.code().equals("expense:list"))
                .map(p -> p.id()).findFirst().orElseGet(() -> rbac.createPermission(
                        new com.cdwy.permission.dto.CreatePermissionRequest(system,"expense:list","报销列表","/expenses")).id());
        long extra = create("permissions", Map.of("systemId",system,"code","extra:"+suffix,"name","额外功能","menuPath","/extra"));
        call(put("/api/roles/{r}/permissions/{p}",viewer,expense),admin,null,204);
        call(put("/api/roles/{r}/permissions/{p}",reviewer,expense),admin,null,204);
        call(put("/api/roles/{r}/permissions/{p}",reviewer,extra),admin,null,204);
        call(put("/api/users/{u}/roles/{r}",userId,viewer),admin,null,204);
        call(put("/api/users/{u}/roles/{r}",userId,reviewer),admin,null,204);
        call(put("/api/users/{u}/roles/{r}",userId,reviewer),admin,null,204); // idempotent
        call(get("/api/auth/me"),ordinary,null,401);
        ordinary = refresh(ordinary);
        assertEquals(2, call(get("/api/auth/me"),ordinary,null,200).path("permissions").size());
        call(get("/api/demo/expenses"),ordinary,null,200);
        call(delete("/api/users/{u}/roles/{r}",userId,viewer),admin,null,204);
        call(get("/api/demo/expenses"),ordinary,null,401);
        ordinary = refresh(ordinary);
        call(get("/api/demo/expenses"),ordinary,null,200); // overlap must survive
        call(delete("/api/users/{u}/roles/{r}",userId,reviewer),admin,null,204);
        call(get("/api/demo/expenses"),ordinary,null,401);
        ordinary = refresh(ordinary);
        call(get("/api/demo/expenses"),ordinary,null,403);
        call(get("/api/auth/me"),ordinary,null,200); // forbidden is not logged out
        call(post("/api/auth/logout"),ordinary,null,204);
        call(get("/api/demo/expenses"),ordinary,null,401);
        call(get("/api/auth/me"),ordinary,null,401);
    }

    @Test
    void administratorFlagCannotBeMassAssignedAndOwnIdentityIsServerDerived() throws Exception {
        long id = create("users", Map.of("username","forged-"+suffix,"displayName","伪造标记","administrator",true));
        assertFalse(users.findById(id).orElseThrow().isAdministrator());
        call(put("/api/users/{id}",id),admin,Map.of("displayName","更新","enabled",true,"administrator",true),200);
        assertFalse(users.findById(id).orElseThrow().isAdministrator());
        assertEquals(userId,call(get("/api/auth/me?userId=1"),ordinary,null,200).path("userId").asLong());
        call(post("/api/users"),ordinary,Map.of("username","evil","displayName","evil","administrator",true),403);
        call(put("/api/users/{id}/password",users.findByUsername("operator").orElseThrow().getId()),ordinary,Map.of("password",PASSWORD),403);
        call(put("/api/users/{id}/roles/1",userId),ordinary,null,403);
    }

    @Test
    void bootstrapIsIdempotentAndDoesNotPromoteOrResetExistingAccounts() {
        bootstrap.initialize("", "");
        bootstrap.initialize("operator", "different-password-123");
        assertNotNull(rbac.login(new LoginRequest("operator", PASSWORD)).accessToken());
        assertThrows(IllegalStateException.class,()->bootstrap.initialize("user-"+suffix,PASSWORD));
        assertThrows(IllegalStateException.class,()->bootstrap.initialize("second-admin",PASSWORD));
        assertThrows(IllegalArgumentException.class,()->bootstrap.initialize("blank-password",""));
        assertThrows(IllegalArgumentException.class,()->bootstrap.initialize("",PASSWORD));
        assertThrows(IllegalArgumentException.class,()->bootstrap.initialize(" space ",PASSWORD));
    }

    @Test
    void administratorCannotBeDisabledOrDeletedAndHasNoImplicitBusinessGrant() throws Exception {
        long id=users.findByUsername("operator").orElseThrow().getId();
        call(put("/api/users/{id}",id),admin,Map.of("displayName","管理员","enabled",false),409);
        call(delete("/api/users/{id}",id),admin,null,409);
        call(get("/api/demo/expenses"),admin,null,403);
        call(post("/api/auth/logout"),admin,null,204);
        call(get("/api/users"),admin,null,401);
    }

    @Test
    void badInputAndDuplicateCodesProduceActionableErrors() throws Exception {
        call(post("/api/users"),admin,Map.of("username"," ","displayName","test"),400);
        call(post("/api/users"),admin,Map.of("username","user-"+suffix,"displayName","重复"),409);
        call(put("/api/users/{id}/password",userId),admin,Map.of("password","short"),400);
        call(put("/api/users/{id}/password",userId),admin,Map.of("password","密".repeat(25)),400);
        call(get("/api/users/not-a-number/permissions"),admin,null,400);
        mvc.perform(post("/api/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        call(get("/api/users/999999/permissions"),admin,null,404);
        call(get("/api/auth/me"),"",null,401);
    }

    @Test
    void administratorCanEditAndRemoveCatalogAndRelationships() throws Exception {
        long s=create("systems",Map.of("code","catalog-"+suffix,"name","原系统"));
        long r=create("roles",Map.of("code","catalog-"+suffix,"name","原角色"));
        long p=create("permissions",Map.of("systemId",s,"code","catalog:"+suffix,"name","原权限","menuPath","/old"));
        call(put("/api/systems/{id}",s),admin,Map.of("name","新系统","enabled",true,"baseUrl","/demo"),200);
        admin = refresh(admin);
        call(put("/api/roles/{id}",r),admin,Map.of("name","新角色","enabled",false,"description","已停用"),200);
        admin = refresh(admin);
        call(put("/api/permissions/{id}",p),admin,Map.of("name","新权限","menuPath","/new"),200);
        call(put("/api/roles/{r}/permissions/{p}",r,p),admin,null,204);
        call(put("/api/users/{u}/roles/{r}",userId,r),admin,null,204);
        assertEquals(0,call(get("/api/users/{id}/permissions",userId),admin,null,200).size());
        assertEquals(1,call(get("/api/roles/{id}/permissions",r),admin,null,200).size());
        call(put("/api/roles/{id}",r),admin,Map.of("name","新角色","enabled",true),200);
        admin = refresh(admin);
        assertEquals(1,call(get("/api/users/{id}/permissions",userId),admin,null,200).size());
        call(delete("/api/roles/{r}/permissions/{p}",r,p),admin,null,204);
        assertEquals(0,call(get("/api/users/{id}/permissions",userId),admin,null,200).size());
        call(delete("/api/permissions/{id}",p),admin,null,204);
        call(delete("/api/roles/{id}",r),admin,null,409);
        call(delete("/api/users/{u}/roles/{r}",userId,r),admin,null,204);
        call(delete("/api/roles/{id}",r),admin,null,204);
        admin = refresh(admin);
        call(delete("/api/systems/{id}",s),admin,null,204);
        admin = refresh(admin);
        assertEquals(0,call(get("/api/permissions?systemId="+s),admin,null,200).size());
        var catalog=call(get("/api/permissions"),admin,null,200);
        for(var item:catalog)assertNotEquals(p,item.path("id").asLong(),"已删除应用的历史权限不能使当前目录报错或重新出现");
    }

    private String refresh(String token) throws Exception {
        return call(post("/api/auth/refresh"),token,null,200).path("accessToken").asText();
    }
    private String login(String name,String password) throws Exception {
        return call(post("/api/auth/login"),"",Map.of("username",name,"password",password),200).path("accessToken").asText();
    }
    private long create(String resource,Object body) throws Exception {
        return call(post("/api/"+resource),admin,body,201).path("id").asLong();
    }
    private JsonNode call(MockHttpServletRequestBuilder request,String token,Object body,int expected) throws Exception {
        if(token!=null&&!token.isEmpty())request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String result=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return result.isBlank()?json.nullNode():json.readTree(result);
    }
}
