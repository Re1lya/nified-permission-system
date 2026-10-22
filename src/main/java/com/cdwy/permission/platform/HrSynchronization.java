package com.cdwy.permission.platform;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.entity.*;
import com.cdwy.permission.company.CompanyData;
import com.cdwy.permission.exception.*;
import com.cdwy.permission.service.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Runs inside the signed integration transaction; external IDs never depend on a person's name. */
@Service
class HrSynchronization {
    private final PlatformStore s;private final EntityManager em;private final RbacService rbac;private final AccountNaming naming;private final PasswordEncoder passwords;
    HrSynchronization(PlatformStore s,EntityManager em,RbacService rbac,AccountNaming naming,PasswordEncoder passwords){this.s=s;this.em=em;this.rbac=rbac;this.naming=naming;this.passwords=passwords;}
    IntegrationService.Event resolve(IntegrationService.Event e){
        if(e==null)throw new IllegalArgumentException("同步记录不能为null");
        Long department=e.departmentExternalId()==null?e.departmentId():lookup("DEPARTMENT",e.departmentExternalId());
        Long post=e.postExternalId()==null?e.postId():lookup("POST",e.postExternalId());
        if(e.departmentExternalId()!=null&&department==null||e.postExternalId()!=null&&post==null)throw new IllegalArgumentException("关联部门或岗位尚未同步");
        return new IntegrationService.Event(e.action(),e.username(),e.displayName(),e.userId(),department,post,e.phone(),e.company(),e.roleIds(),e.approvalId(),e.decision(),e.externalId(),e.name(),e.parentExternalId(),e.records(),e.mode(),e.requestId(),e.departmentExternalId(),e.postExternalId());
    }
    Long lookup(String kind,String external){if(external==null||external.isBlank()||external.length()>128)throw new IllegalArgumentException("需要稳定的外部编号");var rows=s.rows("select local_id from hr_identity where kind=? and external_id=?",kind,external);return rows.isEmpty()?null:((Number)rows.getFirst().get("local_id")).longValue();}
    void bind(String kind,String external,long id){s.db().update("insert into hr_identity(kind,external_id,local_id) values(?,?,?)",kind,external,id);}
    long department(IntegrationService.Event e){
        String external=e.externalId();Long existing=lookup("DEPARTMENT",external);if(e.name()==null||e.name().isBlank()||e.name().length()>128)throw new IllegalArgumentException("部门名称不合法");
        Long parent=e.parentExternalId()==null?null:lookup("DEPARTMENT",e.parentExternalId());if(e.parentExternalId()!=null&&parent==null)throw new IllegalArgumentException("请先同步父部门");
        if(existing==null){var matching=s.rows("select id from sys_department where name=? and deleted=false",e.name());if(!matching.isEmpty()){existing=((Number)matching.getFirst().get("id")).longValue();bind("DEPARTMENT",external,existing);}}
        var entity=existing==null?new CompanyData.Department():em.find(CompanyData.Department.class,existing);
        if(entity==null)throw new ConflictException("该 HR 部门已在平台删除，请人工核对外部编号");
        entity.name=e.name();var parentEntity=parent==null?null:em.find(CompanyData.Department.class,parent);
        if(parent!=null&&parentEntity==null)throw new ConflictException("上级部门已删除，请先修正 HR 组织关联");
        for(var p=parentEntity;p!=null;p=p.parent)if(Objects.equals(p.id,entity.id))throw new IllegalArgumentException("部门不能形成循环");
        entity.parent=parentEntity;if(existing==null){em.persist(entity);em.flush();bind("DEPARTMENT",external,entity.id);}return entity.id;
    }
    long post(IntegrationService.Event e){
        Long existing=lookup("POST",e.externalId());if(e.name()==null||e.name().isBlank()||e.name().length()>128)throw new IllegalArgumentException("岗位名称不合法");
        if(existing==null){var matching=s.rows("select id from sys_post where name=? and deleted=false",e.name());if(!matching.isEmpty()){existing=((Number)matching.getFirst().get("id")).longValue();bind("POST",e.externalId(),existing);}}
        if(existing!=null){var p=em.find(CompanyData.Post.class,existing);if(p==null)throw new ConflictException("该 HR 岗位已在平台删除，请人工核对外部编号");p.name=e.name();p.role.setName(e.name());return p.id;}
        var r=rbac.createRole(new CreateRoleRequest("hr_"+UUID.randomUUID().toString().replace("-",""),e.name(),"HR 岗位"));
        var p=em.createQuery("from CompanyData$Post where role.id=:role",CompanyData.Post.class).setParameter("role",r.id()).getSingleResult();bind("POST",e.externalId(),p.id);return p.id;
    }
    long join(IntegrationService.Event e){
        String external=e.externalId()==null?e.username():e.externalId();Long existing=lookup("EMPLOYEE",external);
        if(existing!=null){
            if(e.displayName()==null||e.displayName().isBlank()||e.displayName().length()>128)throw new IllegalArgumentException("员工姓名不合法");
            if(s.superAdmin(existing)||s.departmentAdmin(existing))throw new ForbiddenException("HR 不能修改管理员");s.validAccount(existing);
            var profile=s.rows("select department_id,post_id from employee_profile where user_id=?",existing).getFirst();
            if(((Number)profile.get("department_id")).longValue()!=e.departmentId()||((Number)profile.get("post_id")).longValue()!=e.postId())throw new ConflictException("已有员工组织变化，请发送 TRANSFER");
            s.db().update("update sys_user set display_name=? where id=?",e.displayName(),existing);
            s.db().update("update employee_profile set phone=?,company=? where user_id=?",e.phone(),e.company(),existing);return existing;
        }
        var name=naming.generate(e.displayName(),e.phone());
        long id=rbac.createUser(new CreateUserRequest(name.username(),e.displayName())).id();
        s.db().update("insert into employee_profile(user_id,department_id,post_id,phone,company) values(?,?,?,?,?)",id,e.departmentId(),e.postId(),e.phone(),e.company());
        s.ensurePolicy(id);rbac.setPassword(id,new SetPasswordRequest("Aa1!"+UUID.randomUUID()));
        s.db().update("update account_policy set must_change_password=true,initial_password_issued=false where user_id=?",id);bind("EMPLOYEE",external,id);
        if(name.manualReview())notice(e.departmentId(),id,"ACCOUNT_NAME_REVIEW","账号重名或含多音字，请核对："+name.username());
        return id;
    }
    void notice(long department,long user,String kind,String message){s.db().update("insert into platform_notification(department_id,user_id,kind,message,created_at) values(?,?,?,?,?)",department,user,kind,message,Instant.now());}
    Map<String,Object> reconcile(List<IntegrationService.Event> records){
        if(records==null||records.size()>20000)throw new IllegalArgumentException("对账清单最多20000条");
        Set<String> expected=new HashSet<>();for(var e:records){if(e==null||e.externalId()==null||e.externalId().isBlank()||e.externalId().length()>128||!expected.add(e.externalId()))throw new IllegalArgumentException("对账编号为空、过长或重复");}
        var actual=new HashSet<>(s.db().queryForList("select external_id from hr_identity where kind='EMPLOYEE'",String.class));
        var missing=new TreeSet<>(expected);missing.removeAll(actual);var extra=new TreeSet<>(actual);extra.removeAll(expected);
        return Map.of("missing",missing,"extra",extra,"matched",expected.size()-missing.size(),"note","仅对账，不因清单缺失自动删除或停用账号");
    }
}
