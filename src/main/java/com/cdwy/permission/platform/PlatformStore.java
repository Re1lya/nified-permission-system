package com.cdwy.permission.platform;

import com.cdwy.permission.exception.*;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PlatformStore {
    private final JdbcTemplate db;private final org.springframework.transaction.support.TransactionTemplate separate;
    public PlatformStore(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager transactions){
        this.db=db;this.separate=new org.springframework.transaction.support.TransactionTemplate(transactions);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public JdbcTemplate db(){return db;}
    public List<Map<String,Object>> rows(String sql,Object...args){return db.query(sql,(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();var m=rs.getMetaData();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));return row;},args);}
    public long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    public boolean superAdmin(long id){return count("select count(*) from sys_user where id=? and administrator=true and enabled=true",id)>0;}
    public boolean departmentAdmin(long id){return count("select count(*) from admin_department where user_id=?",id)>0;}
    public void requireSuper(long id){if(!superAdmin(id))throw new ForbiddenException("仅超级管理员可以执行此操作");}
    public void requireDepartment(long actor,long department){
        if(!superAdmin(actor)&&count("select count(*) from admin_department where user_id=? and department_id=?",actor,department)==0)throw new ForbiddenException("不在当前管理员管辖部门内");
    }
    public void requireUser(long actor,long target){
        if(superAdmin(actor))return;
        if(superAdmin(target)||departmentAdmin(target))throw new ForbiddenException("部门管理员不能维护管理员账号");
        if(count("select count(*) from employee_profile e join admin_department a on a.department_id=e.department_id where a.user_id=? and e.user_id=?",actor,target)==0)throw new ForbiddenException("只能管理绑定部门的员工");
    }
    public void audit(Long actor,String kind,String target,String result){audit(actor,kind,target,result,null);}
    public void audit(Long actor,String kind,String target,String result,Long targetUser){auditDepartment(actor,kind,target,result,departmentOf(targetUser));}
    /**
     * Departments are resolved in the caller's transaction, so a user created in it is visible; the row itself is written
     * in a separate transaction and survives a rollback (failed logins, denials).
     */
    public void auditDepartment(Long actor,String kind,String target,String result,Long targetDepartment){
        Long actorDepartment=departmentOf(actor);String text=target.substring(0,Math.min(255,target.length()));
        separate.executeWithoutResult(status->db.update("insert into audit_event(actor_id,department_id,target_department_id,kind,target,result,created_at) values (?,?,?,?,?,?,?)",actor,actorDepartment,targetDepartment,kind,text,result,Instant.now()));
    }
    private Long departmentOf(Long user){return user==null?null:rows("select department_id from employee_profile where user_id=?",user).stream().map(r->((Number)r.get("department_id")).longValue()).findFirst().orElse(null);}
    /** SQL condition limiting rows whose user_id column belongs to the actor's bound departments; empty for super admins. */
    public String managedUsers(long actor,String column){return superAdmin(actor)?"":" and "+column+" in(select e.user_id from employee_profile e join admin_department a on a.department_id=e.department_id where a.user_id="+actor+")";}
    public long version(long user){return rows("select version from account_policy where user_id=?",user).stream().mapToLong(r->((Number)r.get("version")).longValue()).findFirst().orElse(0);}
    // A plain count + insert: H2 reuses the result of a repeated INSERT ... SELECT ... WHERE NOT EXISTS within one transaction.
    @Transactional public void ensurePolicy(long user){if(count("select count(*) from account_policy where user_id=?",user)==0)db.update("insert into account_policy(user_id,person_type,must_change_password,version,deleted,initial_password_issued) values(?,'EMPLOYEE',false,0,false,false)",user);}
    @Transactional public void changed(long user){ensurePolicy(user);db.update("update account_policy set version=version+1 where user_id=?",user);}
    @Transactional public void permissionsChanged(){db.update("update account_policy set version=version+1");}
    public void validAccount(long user){
        if(count("select count(*) from account_policy where user_id=? and (deleted=true or (expires_at is not null and expires_at<=?))",user,Instant.now())>0)throw new AuthenticationFailedException("账号已过期或停用");
    }
    public boolean mustChange(long user){return count("select count(*) from account_policy where user_id=? and must_change_password=true",user)>0;}
    public void notLocked(String username){if(count("select count(*) from login_attempt where username=? and locked_until>?",username,Instant.now())>0)throw new AuthenticationFailedException("登录暂时锁定，请稍后重试");}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public synchronized void loginResult(String username,boolean ok){
        db.update("insert into login_attempt(username,failures) select ?,0 where not exists(select 1 from login_attempt where username=?)",username,username);
        if(ok)db.update("update login_attempt set failures=0,locked_until=null where username=?",username);
        else {db.update("update login_attempt set failures=failures+1 where username=?",username);db.update("update login_attempt set locked_until=? where username=? and failures>=5",Instant.now().plusSeconds(300),username);}
    }
}
