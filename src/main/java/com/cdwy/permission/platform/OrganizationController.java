package com.cdwy.permission.platform;

import com.cdwy.permission.company.CompanyData;
import com.cdwy.permission.entity.*;
import com.cdwy.permission.exception.*;
import com.cdwy.permission.service.*;
import com.cdwy.permission.dto.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform/organization")
public class OrganizationController {
    private final PlatformStore s;private final JwtTokenService tokens;private final EntityManager em;private final RbacService rbac;
    public OrganizationController(PlatformStore s,JwtTokenService tokens,EntityManager em,RbacService rbac){this.s=s;this.tokens=tokens;this.em=em;this.rbac=rbac;}
    private long actor(String auth){return tokens.requireUserId(auth);}
    public record Department(@NotBlank @Size(max=128) String name,Long parentId,Long managerId) {}
    @PostMapping("/departments") @Transactional public Map<String,Object> department(@RequestHeader("Authorization") String auth,@Valid @RequestBody Department body){long a=actor(auth);s.requireSuper(a);var d=new CompanyData.Department();d.name=body.name();if(body.parentId()!=null){d.parent=em.find(CompanyData.Department.class,body.parentId());if(d.parent==null)throw new IllegalArgumentException("上级部门不存在");}em.persist(d);em.flush();manager(d,body.managerId());s.audit(a,"DEPARTMENT_CREATE",d.name,"SUCCESS");return Map.of("id",d.id);}
    @PutMapping("/departments/{id}") @Transactional public void department(@RequestHeader("Authorization") String auth,@PathVariable long id,@Valid @RequestBody Department body){long a=actor(auth);s.requireSuper(a);var d=em.find(CompanyData.Department.class,id);if(d==null)throw new ResourceNotFoundException("部门不存在");var parent=body.parentId()==null?null:em.find(CompanyData.Department.class,body.parentId());if(body.parentId()!=null&&parent==null)throw new IllegalArgumentException("上级部门不存在");for(var p=parent;p!=null;p=p.parent)if(p.id==id)throw new IllegalArgumentException("部门树不能形成循环");d.name=body.name();d.parent=parent;manager(d,body.managerId());s.audit(a,"DEPARTMENT_UPDATE",Long.toString(id),"SUCCESS");}
    private void manager(CompanyData.Department d,Long id){if(id!=null&&s.count("select count(*) from employee_profile where user_id=? and department_id=?",id,d.id)!=1)throw new IllegalArgumentException("负责人必须是本部门员工");d.manager=id==null?null:em.getReference(UserAccount.class,id);d.managerName=d.manager==null?null:d.manager.getDisplayName();}
    public record Post(@NotBlank @Size(max=128) String name,@NotBlank @Size(max=64) String code,Long departmentId) {}
    @PostMapping("/posts") @Transactional public Map<String,Object> post(@RequestHeader("Authorization") String auth,@Valid @RequestBody Post body){long a=actor(auth);s.requireSuper(a);if(body.departmentId()!=null)s.requireDepartment(a,body.departmentId());var role=rbac.createRole(new CreateRoleRequest(body.code(),body.name(),"岗位角色"));var p=em.createQuery("from CompanyData$Post where role.id=:role",CompanyData.Post.class).setParameter("role",role.id()).getSingleResult();if(body.departmentId()!=null)s.db().update("insert into managed_role(role_id,department_id) values(?,?)",role.id(),body.departmentId());s.audit(a,"POST_CREATE",body.name(),"SUCCESS");return Map.of("id",p.id,"roleId",role.id());}
    @PutMapping("/grant-limits/{department}/{permission}") @Transactional public void limit(@RequestHeader("Authorization") String auth,@PathVariable long department,@PathVariable long permission){long a=actor(auth);s.requireSuper(a);s.db().update("insert into department_permission(department_id,permission_id) select ?,? where not exists(select 1 from department_permission where department_id=? and permission_id=?)",department,permission,department,permission);s.audit(a,"DELEGATION_LIMIT",department+":"+permission,"SUCCESS");}
    private void role(long a,long role,long permission,boolean assigning){if(s.superAdmin(a))return;var rows=s.rows("select department_id from managed_role where role_id=?",role);if(rows.isEmpty())throw new ForbiddenException("共享岗位角色仅超级管理员可维护");long department=((Number)rows.getFirst().get("department_id")).longValue();s.requireDepartment(a,department);if(assigning&&s.count("select count(*) from department_permission where department_id=? and permission_id=?",department,permission)==0)throw new ForbiddenException("权限不在超级管理员委派范围内");}
    @PutMapping("/roles/{role}/permissions/{permission}") @Transactional public void grant(@RequestHeader("Authorization") String auth,@PathVariable long role,@PathVariable long permission){long a=actor(auth);role(a,role,permission,true);rbac.assignPermission(role,permission);s.audit(a,"PERMISSION_GRANT",role+":"+permission,"SUCCESS");}
    @DeleteMapping("/roles/{role}/permissions/{permission}") @Transactional public void revoke(@RequestHeader("Authorization") String auth,@PathVariable long role,@PathVariable long permission){long a=actor(auth);role(a,role,permission,false);rbac.removePermission(role,permission);s.audit(a,"PERMISSION_REVOKE",role+":"+permission,"SUCCESS");}
    @GetMapping("/roles") public List<Map<String,Object>> roles(@RequestHeader("Authorization") String auth){long a=actor(auth);if(s.superAdmin(a))return s.rows("select r.id,r.code,r.name,r.enabled,m.department_id from sys_role r left join managed_role m on m.role_id=r.id where r.deleted=false order by r.id");if(!s.departmentAdmin(a))throw new ForbiddenException("需要管理权限");return s.rows("select r.id,r.code,r.name,r.enabled,m.department_id from sys_role r join managed_role m on m.role_id=r.id join admin_department a on a.department_id=m.department_id where a.user_id=? and r.deleted=false order by r.id",a);}
}
