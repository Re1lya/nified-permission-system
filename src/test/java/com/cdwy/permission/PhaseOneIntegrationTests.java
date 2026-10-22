package com.cdwy.permission;
import com.cdwy.permission.service.*;
import com.cdwy.permission.dto.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:phase-one;DB_CLOSE_DELAY=-1","permission.integration.hr-secret=test-only-integration-secret-123456789","permission.integration.oa-secret=test-only-integration-secret-123456789"})
@AutoConfigureMockMvc
class PhaseOneIntegrationTests {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired RbacService rbac;@Autowired AdminBootstrapService bootstrap;@Autowired JsonMapper json;
 @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
 static final String PASSWORD="Test-only-123", SECRET="test-only-integration-secret-123456789";
 String admin,userToken,managerToken,suffix;long user,manager,department,otherDepartment,role,post;
 @BeforeEach void setup(){
  suffix=UUID.randomUUID().toString().substring(0,8);bootstrap.initialize("phase_admin",PASSWORD);admin=login("phase_admin");
  department=department("dept"+suffix);otherDepartment=department("other"+suffix);
  role=rbac.createRole(new CreateRoleRequest("role"+suffix,"岗位"+suffix,null)).id();db.update("insert into sys_post(name,role_id) values(?,?)","岗位"+suffix,role);post=db.queryForObject("select id from sys_post where role_id=?",Long.class,role);
  user=employee("user"+suffix,department);manager=employee("manager"+suffix,department);
  db.update("insert into admin_department(user_id,department_id) values(?,?)",manager,department);db.update("insert into managed_role(role_id,department_id) values(?,?)",role,department);
  userToken=login("user"+suffix);managerToken=login("manager"+suffix);
 }
 String login(String name){return "Bearer "+rbac.login(new LoginRequest(name,PASSWORD)).accessToken();}
 long department(String name){db.update("insert into sys_department(name) values(?)",name);return db.queryForObject("select id from sys_department where name=?",Long.class,name);}
 long employee(String name,long dep){long id=rbac.createUser(new CreateUserRequest(name,"测试员工")).id();rbac.setPassword(id,new SetPasswordRequest(PASSWORD));db.update("insert into employee_profile(user_id,department_id,post_id,phone,company) values(?,?,?,?,?)",id,dep,post,"139"+String.format("%08d",id),"测试公司");return id;}
 void request(String path,String body,String auth,int status) throws Exception {mvc.perform(post(path).header("Authorization",auth).contentType("application/json").content(body)).andExpect(status().is(status));}
 @Test void departmentAdminCannotResetOutsideDepartmentOrGrantAdmin() throws Exception {
  long outside=employee("outside"+suffix,otherDepartment);
  request("/api/platform/users/"+outside+"/password","{\"password\":\"Other-pass-123\"}",managerToken,403);
  mvc.perform(put("/api/platform/administrators/"+user).header("Authorization",managerToken).contentType("application/json").content("{\"departmentId\":"+department+"}")).andExpect(status().isForbidden());
  mvc.perform(put("/api/platform/users/"+user+"/roles/"+role).header("Authorization",managerToken)).andExpect(status().isOk());
  mvc.perform(get("/api/users").header("Authorization",managerToken)).andExpect(status().isForbidden());
 }
 @Test void resetRequiresFirstPasswordChangeAndRevokesOldSession() throws Exception {
  request("/api/platform/users/"+user+"/password","{\"password\":\"Reset-pass-123\"}",managerToken,200);
  mvc.perform(get("/api/platform/self").header("Authorization",userToken)).andExpect(status().isUnauthorized());
  String fresh="Bearer "+rbac.login(new LoginRequest("user"+suffix,"Reset-pass-123")).accessToken();
  mvc.perform(get("/api/demo/expenses").header("Authorization",fresh)).andExpect(status().isForbidden());
  request("/api/platform/self/password","{\"oldPassword\":\"Reset-pass-123\",\"newPassword\":\"Final-pass-123\"}",fresh,200);
  mvc.perform(get("/api/platform/self").header("Authorization",fresh)).andExpect(status().isUnauthorized());
  assertNotNull(rbac.login(new LoginRequest("user"+suffix,"Final-pass-123")).accessToken());
 }
 @Test void ordinaryUserCannotReadAuditOrModifyOthers() throws Exception {
  mvc.perform(get("/api/platform/audit").header("Authorization",userToken)).andExpect(status().isForbidden());
  request("/api/platform/users/"+manager+"/offline","{}",userToken,403);
 }
 @Test void signedHrIsIdempotentAndLeaveRevokesRolesAndSessions() throws Exception {
  rbac.assignRole(user,role);String body=json.writeValueAsString(Map.of("action","LEAVE","userId",user));String id="leave"+suffix;
  signed("hr",id,body,200);signed("hr",id,body,200);signed("hr",id,json.writeValueAsString(Map.of("action","LEAVE","userId",manager)),409);
  assertEquals(0,db.queryForObject("select count(*) from sys_user_role where user_id=?",Integer.class,user));
  mvc.perform(get("/api/platform/self").header("Authorization",userToken)).andExpect(status().isUnauthorized());
 }
 @Test void invalidSignatureNeverMutatesData() throws Exception {
  mvc.perform(post("/api/integration/hr").header("X-Request-Id",suffix).header("X-Timestamp",Instant.now().getEpochSecond()).header("X-Signature","00").contentType("application/json").content("{\"action\":\"LEAVE\",\"userId\":"+user+"}")).andExpect(status().isForbidden());
  assertEquals(true,db.queryForObject("select enabled from sys_user where id=?",Boolean.class,user));
 }
 @Test void oaRequiresExistingApplicationAndDoesNotGrantOnRejection() throws Exception {
  String response=mvc.perform(post("/api/platform/self/applications").header("Authorization",userToken).contentType("application/json").content("{\"roleId\":"+role+"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  long application=json.readTree(response).get("id").asLong();
  signed("oa","reject"+suffix,json.writeValueAsString(Map.of("approvalId",application,"decision","REJECTED")),200);
  assertEquals(0,db.queryForObject("select count(*) from sys_user_role where user_id=?",Integer.class,user));
  signed("oa","approve"+suffix,json.writeValueAsString(Map.of("approvalId",application,"decision","APPROVED")),409);
 }
 @Test void scopedRoleCannotReceiveNonDelegatedPermission() throws Exception {
  long system=rbac.createSystem(new CreateSystemRequest("sys"+suffix,"系统",null)).id();long permission=rbac.createPermission(new CreatePermissionRequest(system,"p"+suffix,"权限","/test")).id();
  mvc.perform(put("/api/platform/organization/roles/"+role+"/permissions/"+permission).header("Authorization",managerToken)).andExpect(status().isForbidden());
  mvc.perform(put("/api/platform/organization/grant-limits/"+department+"/"+permission).header("Authorization",admin)).andExpect(status().isOk());
  mvc.perform(put("/api/platform/organization/roles/"+role+"/permissions/"+permission).header("Authorization",managerToken)).andExpect(status().isOk());
 }
 @Test void idleSessionExpires() throws Exception {
  mvc.perform(get("/api/platform/self").header("Authorization",userToken)).andExpect(status().isOk());
  db.update("update session_activity set last_seen=? where session_id in(select id from sys_login_session where user_id=?)",Instant.now().minusSeconds(1801),user);
  mvc.perform(get("/api/platform/self").header("Authorization",userToken)).andExpect(status().isUnauthorized());
 }
 @Test void oidcDiscoveryAndPublicUiAreAvailable() throws Exception {
  mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk()).andExpect(jsonPath("$.authorization_endpoint").exists());
  mvc.perform(get("/ui/index.html")).andExpect(status().isOk());mvc.perform(get("/app.css")).andExpect(status().isOk());
 }
 @Test void databaseHasEmployeeAndAuthorizationConstraints() throws Exception {
  var tables=db.queryForList("select table_name from information_schema.tables where table_schema='PUBLIC' and table_type='BASE TABLE' order by table_name",String.class);
  assertTrue(tables.containsAll(List.of("EMPLOYEE_PROFILE","SYS_POST","ADMIN_DEPARTMENT","INTEGRATION_EVENT")));
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into sys_post(name,role_id) values(?,?)","duplicate"+suffix,role));
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into admin_department(user_id,department_id) values(?,?)",user,99999999));
  var schema=new LinkedHashMap<String,Object>();
  for(String table:tables)schema.put(table.toLowerCase(Locale.ROOT),db.queryForList("select column_name,data_type,character_maximum_length,is_nullable,column_default from information_schema.columns where table_schema='PUBLIC' and table_name=? order by ordinal_position",table));
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("output"));
  java.nio.file.Files.writeString(java.nio.file.Path.of("output/phase-one-schema.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
  var ddl=db.queryForList("SCRIPT NODATA",String.class);java.nio.file.Files.write(java.nio.file.Path.of("output/phase-one-h2.sql"),ddl);
  var endpoints=new ArrayList<Map<String,Object>>();
  mappings.getHandlerMethods().forEach((mapping,handler)->{
   if(!handler.getBeanType().getName().startsWith("com.cdwy.permission"))return;
   var params=new ArrayList<Map<String,Object>>();
   for(var parameter:handler.getMethod().getParameters()){
    var body=parameter.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
    if(body!=null){var fields=new ArrayList<Map<String,String>>();for(var field:parameter.getType().getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers()))fields.add(Map.of("name",field.getName(),"type",field.getType().getSimpleName()));params.add(Map.of("kind","body","type",parameter.getType().getSimpleName(),"fields",fields));}
    var query=parameter.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);if(query!=null)params.add(Map.of("kind","query","name",parameter.getName(),"type",parameter.getType().getSimpleName(),"required",query.required(),"default",query.defaultValue().contains("\n")?"":query.defaultValue()));
   }
   for(String path:mapping.getPatternValues())endpoints.add(Map.of("path",path,"methods",mapping.getMethodsCondition().getMethods().stream().map(Enum::name).toList(),"handler",handler.getMethod().getName(),"parameters",params));
  });endpoints.sort(Comparator.comparing(e->e.get("path").toString()));
  java.nio.file.Files.writeString(java.nio.file.Path.of("output/phase-one-endpoints.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(endpoints));
 }
 String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {return result.andReturn().getResponse().getContentAsString();}
 @Test void departmentAdminSeesManagedSessionsApprovalsAndTargetAudit() throws Exception {
  long outside=employee("outside"+suffix,otherDepartment);login("outside"+suffix);
  String sessions=body(mvc.perform(get("/api/platform/sessions?scope=managed").header("Authorization",managerToken)).andExpect(status().isOk()));
  assertTrue(sessions.contains("\"user_id\":"+user+","));assertFalse(sessions.contains("\"user_id\":"+outside+","));
  mvc.perform(get("/api/platform/sessions?scope=managed").header("Authorization",userToken)).andExpect(status().isForbidden());
  request("/api/platform/self/applications","{\"roleId\":"+role+"}",userToken,200);
  request("/api/platform/self/applications","{\"roleId\":"+role+"}",login("outside"+suffix),200);
  String approvals=body(mvc.perform(get("/api/platform/approvals").header("Authorization",managerToken)).andExpect(status().isOk()));
  assertTrue(approvals.contains("\"user_id\":"+user+","));assertFalse(approvals.contains("\"user_id\":"+outside+","));
  request("/api/platform/users/"+user+"/password","{\"password\":\"Reset-pass-123\"}",admin,200);
  String audit=body(mvc.perform(get("/api/platform/audit?kind=PASSWORD_RESET&size=100").header("Authorization",managerToken)).andExpect(status().isOk()));
  assertTrue(audit.contains("\"target\":\""+user+"\""),"超级管理员对本部门员工的操作应对部门管理员可见");
 }
 @Test void lockoutCountsUsernameAndPhoneTogetherAndLogoutIsAudited() throws Exception {
  String phone="139"+String.format("%08d",user);
  for(int i=0;i<5;i++){String name=i%2==0?"user"+suffix:phone;assertThrows(com.cdwy.permission.exception.AuthenticationFailedException.class,()->rbac.login(new LoginRequest(name,"Wrong-pass-1")));}
  assertThrows(com.cdwy.permission.exception.AuthenticationFailedException.class,()->login("user"+suffix),"第 5 次失败后正确密码也应被锁定");
  mvc.perform(post("/api/auth/logout").header("Authorization",managerToken)).andExpect(status().isNoContent());
  assertEquals(1,db.queryForObject("select count(*) from audit_event where kind='LOGOUT' and actor_id=?",Integer.class,manager));
 }
 @Test void refreshRotatesTokenWithinIdleWindow() throws Exception {
  String fresh="Bearer "+json.readTree(body(mvc.perform(post("/api/auth/refresh").header("Authorization",userToken)).andExpect(status().isOk()))).get("accessToken").asText();
  mvc.perform(get("/api/platform/self").header("Authorization",userToken)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/platform/self").header("Authorization",fresh)).andExpect(status().isOk());
  db.update("update session_activity set last_seen=? where session_id in(select id from sys_login_session where user_id=?)",Instant.now().minusSeconds(1801),user);
  mvc.perform(post("/api/auth/refresh").header("Authorization",fresh)).andExpect(status().isUnauthorized());
 }
 @Test void denialIsAuditedAndAllowHasBasis() throws Exception {
  mvc.perform(get("/api/demo/expenses").header("Authorization",userToken)).andExpect(status().isForbidden());
  assertEquals(1,db.queryForObject("select count(*) from audit_event where kind='ACCESS_DENIED' and actor_id=? and target='expense:list'",Integer.class,user));
  long system=rbac.createSystem(new CreateSystemRequest("sys"+suffix,"系统",null)).id();long permission=rbac.createPermission(new CreatePermissionRequest(system,"p"+suffix,"权限","/test")).id();
  rbac.assignPermission(role,permission);rbac.assignRole(user,role);
  assertEquals(List.of("role"+suffix),rbac.grantingRoles(user,"p"+suffix));
  long before=db.queryForObject("select version from account_policy where user_id=?",Long.class,user);
  request("/api/platform/users/"+user+"/offline","{}",managerToken,200);
  assertTrue(db.queryForObject("select version from account_policy where user_id=?",Long.class,user)>before,"强制下线应使 OIDC 令牌的权限版本失效");
 }
 String external(String name,String type,java.time.LocalDate expiresOn,long dep){return "{\"username\":\""+name+"\",\"displayName\":\"外包人员\",\"personType\":\""+type+"\",\"expiresOn\":\""+expiresOn+"\",\"departmentId\":"+dep+",\"postId\":"+post+",\"company\":\"演示外包公司\",\"phone\":\"13700000000\",\"password\":\""+PASSWORD+"\"}";}
 long externalId(String name){return db.queryForObject("select id from sys_user where username=?",Long.class,name);}
 @Test void externalPersonRequiresBoundedExpiryAndDepartmentScope() throws Exception {
  var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
  request("/api/platform/external-users",external("ext"+suffix,"EXTERNAL",today.minusDays(1),department),admin,400);
  request("/api/platform/external-users",external("ext"+suffix,"EXTERNAL",today.plusDays(366),department),admin,400);
  request("/api/platform/external-users",external("tmp"+suffix,"TEMPORARY",today.plusDays(91),department),admin,400);
  request("/api/platform/external-users",external("ext"+suffix,"EXTERNAL",today.plusDays(30),otherDepartment),managerToken,403);
  request("/api/platform/external-users",external("ext"+suffix,"EXTERNAL",today.plusDays(30),department),managerToken,200);
  request("/api/platform/external-users",external("tmp"+suffix,"TEMPORARY",today.plusDays(5),department),userToken,403);
  long id=externalId("ext"+suffix);
  assertEquals("EXTERNAL",db.queryForObject("select person_type from account_policy where user_id=?",String.class,id));
  assertEquals(true,db.queryForObject("select must_change_password from account_policy where user_id=?",Boolean.class,id));
  assertEquals(0,db.queryForObject("select count(*) from sys_user_role where user_id=?",Integer.class,id));
 }
 @Test void expiredExternalPersonIsRejectedUntilRenewed() throws Exception {
  var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
  request("/api/platform/external-users",external("tmp"+suffix,"TEMPORARY",today.plusDays(3),department),admin,200);
  long id=externalId("tmp"+suffix);String token=login("tmp"+suffix);
  mvc.perform(get("/api/platform/self").header("Authorization",token)).andExpect(status().isOk());
  String list=mvc.perform(get("/api/platform/external-users").header("Authorization",managerToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var row=java.util.stream.StreamSupport.stream(json.readTree(list).spliterator(),false).filter(r->r.get("id").asLong()==id).findFirst().orElseThrow();
  assertEquals("EXPIRING",row.get("status").asText());assertEquals(3,row.get("days_left").asInt());
  db.update("update account_policy set expires_at=? where user_id=?",Instant.now().minusSeconds(1),id);
  mvc.perform(get("/api/platform/self").header("Authorization",token)).andExpect(status().isUnauthorized());
  assertThrows(com.cdwy.permission.exception.AuthenticationFailedException.class,()->login("tmp"+suffix));
  mvc.perform(put("/api/platform/external-users/"+id+"/expiry").header("Authorization",managerToken).contentType("application/json").content("{\"expiresOn\":\""+today.plusDays(91)+"\"}")).andExpect(status().isBadRequest());
  mvc.perform(put("/api/platform/external-users/"+id+"/expiry").header("Authorization",managerToken).contentType("application/json").content("{\"expiresOn\":\""+today.plusDays(30)+"\"}")).andExpect(status().isOk());
  assertNotNull(login("tmp"+suffix));
  mvc.perform(put("/api/platform/external-users/"+user+"/expiry").header("Authorization",admin).contentType("application/json").content("{\"expiresOn\":\""+today.plusDays(30)+"\"}")).andExpect(status().isBadRequest());
 }
 void signed(String source,String id,String body,int status) throws Exception {
  String timestamp=Long.toString(Instant.now().getEpochSecond());Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature=HexFormat.of().formatHex(mac.doFinal((source+"\n"+timestamp+"\n"+id+"\n"+body).getBytes(StandardCharsets.UTF_8)));
  mvc.perform(post("/api/integration/"+source).header("X-Request-Id",id).header("X-Timestamp",timestamp).header("X-Signature",signature).contentType("application/json").content(body)).andExpect(status().is(status));
 }
}
