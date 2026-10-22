package com.cdwy.permission.platform;

import com.cdwy.permission.service.*;
import com.cdwy.permission.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class AuditRetention {
    private final PlatformStore s;
    AuditRetention(PlatformStore s){this.s=s;}
    /** Move, do not discard, records older than twelve calendar months. One transaction per bounded batch. */
    @Scheduled(cron="${permission.audit.archive-cron:0 */5 * * * *}",zone="UTC")
    @Transactional public void archive(){
        var cutoff=ZonedDateTime.now(ZoneOffset.UTC).minusMonths(12).toInstant();
        var ids=s.db().queryForList("select id from audit_event where created_at<? order by id limit 1000 for update",Long.class,cutoff);
        for(long id:ids){
            s.db().update("insert into audit_archive(id,actor_id,department_id,target_department_id,kind,target,result,created_at,archived_at) select id,actor_id,department_id,target_department_id,kind,target,result,created_at,? from audit_event where id=?",Instant.now(),id);
            s.db().update("delete from audit_event where id=?",id);
        }
    }
}

@RestController @RequestMapping("/api/platform")
public class AuditLifecycle {
    private final PlatformStore s;private final JwtTokenService tokens;private final RbacService rbac;
    public AuditLifecycle(PlatformStore s,JwtTokenService tokens,RbacService rbac){this.s=s;this.tokens=tokens;this.rbac=rbac;}
    private long admin(String auth){long id=tokens.requireUserId(auth);if(!s.superAdmin(id)&&!s.departmentAdmin(id))throw new ForbiddenException("需要管理权限");return id;}
    public record Decision(@Positive long userId,@Positive long roleId,@Pattern(regexp="KEEP|REVOKE") @NotBlank String decision,@NotBlank @Size(max=1000) String reason){}
    @GetMapping("/reviews/candidates") public Map<String,Object> candidates(@RequestHeader("Authorization")String auth,@RequestParam(defaultValue="0")int page){if(page<0||page>100000)throw new IllegalArgumentException("页码不合法");long a=admin(auth);var items=s.rows("select ur.user_id,ac.username,ur.role_id,r.name role_name from sys_user_role ur join sys_user u on u.id=ur.user_id join sys_account ac on ac.user_id=u.id join sys_role r on r.id=ur.role_id where u.administrator=false"+s.managedUsers(a,"ur.user_id")+" order by ur.user_id,ur.role_id limit 100 offset ?",page*100L);return Map.of("items",items,"total",s.count("select count(*) from sys_user_role ur join sys_user u on u.id=ur.user_id where u.administrator=false"+s.managedUsers(a,"ur.user_id")));}
    @GetMapping("/reviews") public Map<String,Object> reviews(@RequestHeader("Authorization")String auth,@RequestParam(defaultValue="0")int page){if(page<0||page>100000)throw new IllegalArgumentException("页码不合法");long a=admin(auth);var items=s.rows("select * from permission_review where 1=1"+s.managedUsers(a,"user_id")+" order by id desc limit 100 offset ?",page*100L);return Map.of("items",items,"total",s.count("select count(*) from permission_review where 1=1"+s.managedUsers(a,"user_id")));}
    @PostMapping("/reviews") @Transactional public void review(@RequestHeader("Authorization")String auth,@Valid @RequestBody Decision d){
        long a=admin(auth);s.requireUser(a,d.userId());
        s.db().queryForObject("select id from sys_user where id=? for update",Long.class,d.userId());
        if(s.count("select count(*) from sys_user_role where user_id=? and role_id=?",d.userId(),d.roleId())!=1)throw new ConflictException("该角色已回收，请刷新复核清单");
        if(d.decision().equals("REVOKE"))rbac.removeRole(d.userId(),d.roleId());
        s.db().update("insert into permission_review(actor_id,user_id,role_id,decision,reason,created_at) values(?,?,?,?,?,?)",a,d.userId(),d.roleId(),d.decision(),d.reason(),Instant.now());
        s.audit(a,"PERMISSION_REVIEW",d.userId()+":"+d.roleId(),d.decision(),d.userId());
    }
    @GetMapping("/audit/search") public Map<String,Object> search(@RequestHeader("Authorization")String auth,@RequestParam(defaultValue="false")boolean archived,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant until,@RequestParam(required=false)Long actorId,@RequestParam(defaultValue="")String kind,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){
        long a=admin(auth);if(page<0||page>100000||size<1||size>100||from!=null&&until!=null&&!from.isBefore(until))throw new IllegalArgumentException("查询条件不合法");
        String sql=" from "+(archived?"audit_archive":"audit_event")+" where 1=1";List<Object> args=new ArrayList<>();
        if(!s.superAdmin(a)){sql+=" and (department_id in(select department_id from admin_department where user_id=?) or target_department_id in(select department_id from admin_department where user_id=?))";args.add(a);args.add(a);}
        if(from!=null){sql+=" and created_at>=?";args.add(from);}if(until!=null){sql+=" and created_at<?";args.add(until);}if(actorId!=null){sql+=" and actor_id=?";args.add(actorId);}if(!kind.isBlank()){sql+=" and kind=?";args.add(kind);}
        long total=s.count("select count(*)"+sql,args.toArray());args.add(size);args.add((long)page*size);
        return Map.of("total",total,"items",s.rows("select *"+sql+" order by id desc limit ? offset ?",args.toArray()));
    }
    @GetMapping("/notifications") public List<Map<String,Object>> notifications(@RequestHeader("Authorization")String auth){long a=admin(auth);return s.rows("select * from platform_notification where 1=1"+(s.superAdmin(a)?"":" and department_id in(select department_id from admin_department where user_id="+a+")")+" order by id desc limit 200");}
}
