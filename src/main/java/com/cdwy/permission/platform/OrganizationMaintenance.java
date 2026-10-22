package com.cdwy.permission.platform;

import com.cdwy.permission.exception.*;
import com.cdwy.permission.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform/organization")
public class OrganizationMaintenance {
    private final PlatformStore s;private final JwtTokenService tokens;private final RbacService rbac;
    public OrganizationMaintenance(PlatformStore s,JwtTokenService tokens,RbacService rbac){this.s=s;this.tokens=tokens;this.rbac=rbac;}
    private long actor(String auth){long a=tokens.requireUserId(auth);s.requireSuper(a);return a;}
    @DeleteMapping("/departments/{id}") @Transactional public void department(@RequestHeader("Authorization")String auth,@PathVariable long id){long a=actor(auth);s.db().queryForObject("select id from sys_department where id=? and deleted=false for update",Long.class,id);if(s.count("select count(*) from sys_department where parent_id=? and deleted=false",id)>0||s.count("select count(*) from employee_profile where department_id=?",id)>0||s.count("select count(*) from admin_department where department_id=?",id)>0||s.count("select count(*) from managed_role where department_id=?",id)>0)throw new ConflictException("部门仍有下级、员工或管理绑定，不能删除");s.db().update("delete from department_permission where department_id=?",id);s.db().update("update sys_department set deleted=true,name=? where id=?","deleted_department_"+id,id);s.audit(a,"DEPARTMENT_DELETE",Long.toString(id),"SUCCESS");}
    public record Named(@NotBlank @Size(max=128)String name){}
    @PutMapping("/posts/{id}") @Transactional public void post(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Named body){long a=actor(auth);var rows=s.rows("select role_id from sys_post where id=? and deleted=false for update",id);if(rows.isEmpty())throw new ResourceNotFoundException("岗位不存在");s.db().update("update sys_post set name=? where id=?",body.name(),id);s.db().update("update sys_role set name=? where id=?",body.name(),rows.getFirst().get("role_id"));s.audit(a,"POST_UPDATE",Long.toString(id),"SUCCESS");}
    @DeleteMapping("/posts/{id}") @Transactional public void post(@RequestHeader("Authorization")String auth,@PathVariable long id){long a=actor(auth);var rows=s.rows("select role_id from sys_post where id=? and deleted=false for update",id);if(rows.isEmpty())throw new ResourceNotFoundException("岗位不存在");long role=((Number)rows.getFirst().get("role_id")).longValue();if(s.count("select count(*) from employee_profile where post_id=?",id)>0||s.count("select count(*) from sys_user_role where role_id=?",role)>0)throw new ConflictException("岗位或角色仍被员工引用");rbac.deleteRole(role);s.audit(a,"POST_DELETE",Long.toString(id),"SUCCESS");}
    public record Dictionary(@NotBlank @Size(max=64)String category,@NotBlank @Size(max=64)String code,@NotBlank @Size(max=128)String name){}
    @PutMapping("/dictionary/{id}") @Transactional public void dictionary(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Dictionary d){long a=actor(auth);if(s.db().update("update data_dictionary set category=?,code=?,name=? where id=? and deleted=false",d.category(),d.code(),d.name(),id)!=1)throw new ResourceNotFoundException("字典项不存在");s.audit(a,"DICTIONARY_UPDATE",Long.toString(id),"SUCCESS");}
    @DeleteMapping("/dictionary/{id}") @Transactional public void dictionary(@RequestHeader("Authorization")String auth,@PathVariable long id){long a=actor(auth);if(s.db().update("update data_dictionary set deleted=true,code=? where id=? and deleted=false","deleted_"+id,id)!=1)throw new ResourceNotFoundException("字典项不存在");s.audit(a,"DICTIONARY_DELETE",Long.toString(id),"SUCCESS");}
}
