package com.cdwy.permission.platform;

import com.cdwy.permission.service.*;
import com.cdwy.permission.repository.UserAccountRepository;
import com.cdwy.permission.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform")
public class AdministrationController {
    private final PlatformStore s;private final JwtTokenService tokens;private final UserAccountRepository users;
    public AdministrationController(PlatformStore s,JwtTokenService tokens,UserAccountRepository users){this.s=s;this.tokens=tokens;this.users=users;}
    @GetMapping("/administrators") public List<Map<String,Object>> list(@RequestHeader("Authorization")String auth){s.requireSuper(tokens.requireUserId(auth));return s.rows("select u.id,a.username,u.administrator,u.built_in_administrator,d.department_id from sys_user u join sys_account a on a.user_id=u.id left join admin_department d on d.user_id=u.id where u.administrator=true or d.user_id is not null order by u.id");}
    public record Membership(boolean administrator){}
    @PutMapping("/super-administrators/{id}") @Transactional public void membership(@RequestHeader("Authorization")String auth,@PathVariable long id,@RequestBody Membership m){
        long a=tokens.requireUserId(auth);s.requireSuper(a);if(a==id)throw new ForbiddenException("不能变更本人的管理员身份");
        // Lock administrators in a deterministic order so concurrent demotions cannot remove the last administrator.
        s.db().queryForList("select id from sys_user where administrator=true order by id for update",Long.class);
        var user=users.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("用户不存在"));
        if(user.isBuiltInAdministrator())throw new ForbiddenException("内置管理员身份不可修改");s.validAccount(id);
        if(!user.isEnabled()||user.getPasswordHash()==null)throw new ConflictException("请先启用账号并配置密码");
        user.setAdministrator(m.administrator());users.saveAndFlush(user);tokens.revokeAll(id);
        s.audit(a,"SUPER_ADMIN_MEMBERSHIP",Long.toString(id),m.administrator()?"GRANT":"REVOKE",id);
    }
    public record Profile(@NotBlank @Size(max=128)String displayName){}
    @PutMapping("/users/{id}/profile") @Transactional public void profile(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Profile p){long a=tokens.requireUserId(auth);s.requireUser(a,id);var user=users.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("用户不存在"));user.setDisplayName(p.displayName());s.audit(a,"USER_PROFILE",Long.toString(id),"SUCCESS",id);}
    @GetMapping("/organization/read-only") public Map<String,Object> organization(@RequestHeader("Authorization")String auth){long a=tokens.requireUserId(auth);if(!s.superAdmin(a)&&!s.departmentAdmin(a))throw new ForbiddenException("需要管理员权限");String scope=s.superAdmin(a)?" where deleted=false":" where deleted=false and id in(select department_id from admin_department where user_id="+a+")";return Map.of("departments",s.rows("select id,name,parent_id from sys_department"+scope),"posts",s.rows("select id,name,role_id from sys_post"+(s.superAdmin(a)?" where deleted=false":" where deleted=false and id in(select e.post_id from employee_profile e join admin_department d on d.department_id=e.department_id where d.user_id="+a+")")),"dictionary",s.rows("select * from data_dictionary where deleted=false order by category,code"));}
}
