package com.cdwy.permission.platform;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.service.*;
import com.cdwy.permission.repository.UserAccountRepository;
import com.cdwy.permission.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Scoped administration and self service. Every route authorizes its actor explicitly. */
@RestController @RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformStore s; private final JwtTokenService tokens; private final RbacService rbac;
    private final UserAccountRepository users; private final PasswordEncoder passwords;
    public PlatformController(PlatformStore s,JwtTokenService tokens,RbacService rbac,UserAccountRepository users,PasswordEncoder passwords){this.s=s;this.tokens=tokens;this.rbac=rbac;this.users=users;this.passwords=passwords;}
    private long actor(String auth){return tokens.requireUserId(auth);}
    private long admin(String auth){long id=actor(auth);if(!s.superAdmin(id)&&!s.departmentAdmin(id))throw new ForbiddenException("需要管理权限");return id;}
    @GetMapping("/self") public Map<String,Object> self(@RequestHeader("Authorization") String auth){long id=actor(auth);return Map.of("id",id,"superAdmin",s.superAdmin(id),"departmentAdmin",s.departmentAdmin(id),"mustChangePassword",s.mustChange(id),"departments",s.rows("select d.id,d.name from admin_department a join sys_department d on d.id=a.department_id where a.user_id=?",id),"permissions",rbac.effectivePermissions(id),"version",s.version(id),"capabilities",PlatformCapabilities.forUser(s,id));}
    public record PasswordChange(@NotBlank String oldPassword,@NotBlank String newPassword) {}
    @PostMapping("/self/password") @Transactional public Map<String,String> changePassword(@RequestHeader("Authorization") String auth,@Valid @RequestBody PasswordChange body){
        long id=actor(auth);var user=users.findByIdForUpdate(id).orElseThrow();
        if(!passwords.matches(body.oldPassword(),user.getPasswordHash()))throw new ForbiddenException("原密码不正确");
        if(body.oldPassword().equals(body.newPassword()))throw new IllegalArgumentException("新密码不能与原密码相同");
        rbac.setPassword(id,new SetPasswordRequest(body.newPassword()));s.ensurePolicy(id);s.db().update("update account_policy set must_change_password=false where user_id=?",id);s.audit(id,"PASSWORD_CHANGE",Long.toString(id),"SUCCESS");return Map.of("message","密码已更新，请重新登录");
    }
    @GetMapping("/users") public Map<String,Object> list(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        long id=admin(auth);page(page,size);String scope=s.superAdmin(id)?"":" and e.department_id in(select department_id from admin_department where user_id="+id+") and u.administrator=false";
        String from=" from sys_user u join sys_account ac on ac.user_id=u.id left join employee_profile e on e.user_id=u.id left join account_policy p on p.user_id=u.id where (p.deleted is null or p.deleted=false)"+scope;
        return Map.of("items",s.rows("select u.id,ac.username,u.display_name,u.enabled,e.department_id,e.post_id,p.person_type,p.expires_at"+from+" order by u.id limit ? offset ?",size,(long)page*size),"total",s.count("select count(*)"+from));
    }
    public record Reset(@NotBlank String password) {}
    @PostMapping("/users/{id}/password") @Transactional public void reset(@RequestHeader("Authorization") String auth,@PathVariable long id,@Valid @RequestBody Reset body){long a=admin(auth);s.requireUser(a,id);if(a==id)throw new ForbiddenException("请使用修改本人密码功能");rbac.setPassword(id,new SetPasswordRequest(body.password()));s.ensurePolicy(id);s.db().update("update account_policy set must_change_password=true where user_id=?",id);s.audit(a,"PASSWORD_RESET",Long.toString(id),"SUCCESS",id);}
    public record State(boolean enabled) {}
    @PutMapping("/users/{id}/state") @Transactional public void state(@RequestHeader("Authorization") String auth,@PathVariable long id,@RequestBody State body){long a=admin(auth);s.requireUser(a,id);if(a==id||s.superAdmin(id))throw new ForbiddenException("不能停用本人或超级管理员");var u=users.findById(id).orElseThrow(()->new ResourceNotFoundException("账号不存在"));rbac.updateUser(id,new UpdateUserRequest(u.getDisplayName(),body.enabled()));s.audit(a,"ACCOUNT_STATE",Long.toString(id),"SUCCESS",id);}
    @DeleteMapping("/users/{id}") @Transactional public void delete(@RequestHeader("Authorization") String auth,@PathVariable long id){long a=admin(auth);s.requireUser(a,id);if(a==id||s.superAdmin(id))throw new ForbiddenException("不能删除本人或超级管理员");rbac.deleteUser(id);s.audit(a,"ACCOUNT_DELETE",Long.toString(id),"SUCCESS",id);}
    private void grantable(long actor,long user,long role){s.requireUser(actor,user);if(s.superAdmin(actor))return;if(s.count("select count(*) from managed_role r join employee_profile e on e.department_id=r.department_id where r.role_id=? and e.user_id=?",role,user)==0)throw new ForbiddenException("只能授予目标员工所属部门的受管角色");}
    @PutMapping("/users/{id}/roles/{role}") @Transactional public void grant(@RequestHeader("Authorization") String auth,@PathVariable long id,@PathVariable long role){long a=admin(auth);grantable(a,id,role);rbac.assignRole(id,role);s.audit(a,"ROLE_GRANT",id+":"+role,"SUCCESS",id);}
    @DeleteMapping("/users/{id}/roles/{role}") @Transactional public void revoke(@RequestHeader("Authorization") String auth,@PathVariable long id,@PathVariable long role){long a=admin(auth);grantable(a,id,role);rbac.removeRole(id,role);s.audit(a,"ROLE_REVOKE",id+":"+role,"SUCCESS",id);}
    public record Binding(@Positive long departmentId) {}
    @PutMapping("/administrators/{id}") @Transactional public void bind(@RequestHeader("Authorization") String auth,@PathVariable long id,@Valid @RequestBody Binding b){long a=actor(auth);s.requireSuper(a);if(s.superAdmin(id))throw new IllegalArgumentException("超级管理员无需绑定部门");s.validAccount(id);if(s.count("select count(*) from sys_user where id=? and enabled=true",id)!=1)throw new IllegalArgumentException("目标账号不存在或停用");s.db().update("insert into admin_department(user_id,department_id) select ?,? where not exists(select 1 from admin_department where user_id=? and department_id=?)",id,b.departmentId(),id,b.departmentId());s.audit(a,"ADMIN_BIND",id+":"+b.departmentId(),"SUCCESS");}
    @DeleteMapping("/administrators/{id}/{department}") @Transactional public void unbind(@RequestHeader("Authorization") String auth,@PathVariable long id,@PathVariable long department){long a=actor(auth);s.requireSuper(a);s.db().update("delete from admin_department where user_id=? and department_id=?",id,department);s.audit(a,"ADMIN_UNBIND",id+":"+department,"SUCCESS");}
    @GetMapping("/sessions") public List<Map<String,Object>> sessions(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="self") String scope){long a=actor(auth);
        if(scope.equals("self"))return s.rows("select id,user_id,issued_at,expires_at from sys_login_session where user_id=? order by issued_at desc",a);
        if(!scope.equals("managed"))throw new IllegalArgumentException("scope 只能是 self 或 managed");admin(auth);
        return s.rows("select l.id,l.user_id,ac.username,u.display_name,l.issued_at,l.expires_at from sys_login_session l join sys_user u on u.id=l.user_id join sys_account ac on ac.user_id=u.id where l.expires_at>? and u.administrator=false"+s.managedUsers(a,"l.user_id")+" order by l.issued_at desc limit 200",Instant.now());}
    @DeleteMapping("/sessions/{session}") @Transactional public void session(@RequestHeader("Authorization") String auth,@PathVariable String session){long a=actor(auth);var rows=s.rows("select user_id from sys_login_session where id=?",session);if(rows.isEmpty())return;long target=((Number)rows.getFirst().get("user_id")).longValue();if(target!=a)s.requireUser(admin(auth),target);s.db().update("delete from sys_login_session where id=?",session);s.audit(a,"SESSION_REVOKE",Long.toString(target),"SUCCESS",target);}
    @PostMapping("/users/{id}/offline") @Transactional public void offline(@RequestHeader("Authorization") String auth,@PathVariable long id){long a=admin(auth);s.requireUser(a,id);tokens.revokeAll(id);s.audit(a,"FORCE_OFFLINE",Long.toString(id),"SUCCESS",id);}
    @GetMapping("/audit") public Map<String,Object> audit(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String kind){long a=admin(auth);page(page,size);String scope=s.superAdmin(a)?"":" and (department_id in(select department_id from admin_department where user_id="+a+") or target_department_id in(select department_id from admin_department where user_id="+a+"))";String from=" from audit_event where kind like ? escape '!'"+scope;String prefix=kind.replace("!","!!").replace("%","!%").replace("_","!_")+"%";return Map.of("items",s.rows("select *"+from+" order by id desc limit ? offset ?",prefix,size,(long)page*size),"total",s.count("select count(*)"+from,prefix));}
    @GetMapping("/integrations") public List<Map<String,Object>> integrations(@RequestHeader("Authorization") String auth){s.requireSuper(actor(auth));return s.rows("select * from integration_event order by id desc limit 100");}
    @GetMapping("/approvals") public List<Map<String,Object>> approvals(@RequestHeader("Authorization") String auth){long a=admin(auth);return s.rows("select * from approval_record where 1=1"+s.managedUsers(a,"user_id")+" order by id desc limit 100");}
    public record Application(@Positive long roleId) {}
    @PostMapping("/self/applications") @Transactional public Map<String,Object> application(@RequestHeader("Authorization") String auth,@Valid @RequestBody Application body){long a=actor(auth);if(s.count("select count(*) from sys_post p join sys_role r on r.id=p.role_id where p.deleted=false and r.id=? and r.enabled=true",body.roleId())!=1)throw new IllegalArgumentException("只能申请启用的岗位角色");String id=UUID.randomUUID().toString();s.db().update("insert into approval_record(request_id,user_id,role_id,decision,created_at) values(?,?,?,'PENDING',?)",id,a,body.roleId(),Instant.now());return s.rows("select id,request_id,decision from approval_record where request_id=?",id).getFirst();}
    @GetMapping("/self/applications") public List<Map<String,Object>> myApplications(@RequestHeader("Authorization") String auth){return s.rows("select * from approval_record where user_id=? order by id desc limit 100",actor(auth));}
    @GetMapping("/dictionary") public List<Map<String,Object>> dictionary(@RequestHeader("Authorization") String auth){admin(auth);return s.rows("select * from data_dictionary where deleted=false order by category,code");}
    public record DictionaryEntry(@NotBlank @Size(max=64) String category,@NotBlank @Size(max=64) String code,@NotBlank @Size(max=128) String name) {}
    @PostMapping("/dictionary") @Transactional public void dictionary(@RequestHeader("Authorization") String auth,@Valid @RequestBody DictionaryEntry body){long a=actor(auth);s.requireSuper(a);s.db().update("insert into data_dictionary(category,code,name,deleted) values(?,?,?,false)",body.category(),body.code(),body.name());s.audit(a,"DICTIONARY_CREATE",body.category()+":"+body.code(),"SUCCESS");}
    private static void page(int page,int size){if(page<0||page>100000||size<1||size>100)throw new IllegalArgumentException("分页参数错误");}
}
