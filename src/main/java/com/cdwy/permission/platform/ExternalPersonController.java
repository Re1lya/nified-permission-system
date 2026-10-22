package com.cdwy.permission.platform;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.exception.*;
import com.cdwy.permission.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** UC-C05 / BR-11: platform-created external and temporary people always carry an expiry date. */
@RestController @RequestMapping("/api/platform/external-users")
public class ExternalPersonController {
    private final ExternalPersonService service;private final JwtTokenService tokens;
    public ExternalPersonController(ExternalPersonService service,JwtTokenService tokens){this.service=service;this.tokens=tokens;}
    public record Creation(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") String username,@NotBlank @Size(max=128) String displayName,
        @NotNull PersonType personType,@NotNull LocalDate expiresOn,@NotNull @Positive Long departmentId,@NotNull @Positive Long postId,
        @NotBlank @Size(max=128) String company,@NotBlank @Pattern(regexp="[0-9+ -]{5,32}") String phone,@NotBlank String password) {}
    public record Renewal(@NotNull LocalDate expiresOn) {}
    @PostMapping public Map<String,Object> create(@RequestHeader("Authorization") String auth,@Valid @RequestBody Creation body){return service.create(tokens.requireUserId(auth),body);}
    @PutMapping("/{id}/expiry") public Map<String,Object> renew(@RequestHeader("Authorization") String auth,@PathVariable long id,@Valid @RequestBody Renewal body){return service.renew(tokens.requireUserId(auth),id,body.expiresOn());}
    @GetMapping public List<Map<String,Object>> list(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="14") int remindDays){return service.list(tokens.requireUserId(auth),remindDays);}
}

/** Maximum validity per person type; validity runs through the end of expiresOn in the company's time zone. */
enum PersonType {
    EXTERNAL(365),TEMPORARY(90);
    final int maxDays;
    PersonType(int maxDays){this.maxDays=maxDays;}
}

@Service
class ExternalPersonService {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final PlatformStore s;private final RbacService rbac;
    ExternalPersonService(PlatformStore s,RbacService rbac){this.s=s;this.rbac=rbac;}

    private long admin(long actor){if(!s.superAdmin(actor)&&!s.departmentAdmin(actor))throw new ForbiddenException("需要管理权限");return actor;}
    private static Instant endOf(LocalDate day){return day.plusDays(1).atStartOfDay(ZONE).toInstant();}
    private static void validity(PersonType type,LocalDate expiresOn){
        LocalDate today=LocalDate.now(ZONE);
        if(expiresOn.isBefore(today))throw new IllegalArgumentException("有效期不能早于今天");
        if(expiresOn.isAfter(today.plusDays(type.maxDays)))throw new IllegalArgumentException((type==PersonType.EXTERNAL?"外部人员":"临时人员")+"有效期最长 "+type.maxDays+" 天");
    }

    @Transactional public Map<String,Object> create(long actor,ExternalPersonController.Creation body){
        admin(actor);s.requireDepartment(actor,body.departmentId());validity(body.personType(),body.expiresOn());
        if(s.count("select count(*) from sys_department where id=? and deleted=false",body.departmentId())!=1||s.count("select count(*) from sys_post where id=? and deleted=false",body.postId())!=1)throw new IllegalArgumentException("部门或岗位不存在");
        long id=rbac.createUser(new CreateUserRequest(body.username(),body.displayName())).id();
        s.ensurePolicy(id);
        rbac.setPassword(id,new SetPasswordRequest(body.password()));
        s.db().update("insert into employee_profile(user_id,department_id,post_id,phone,company) values(?,?,?,?,?)",id,body.departmentId(),body.postId(),body.phone(),body.company());
        s.db().update("update account_policy set person_type=?,expires_at=?,must_change_password=true where user_id=?",body.personType().name(),endOf(body.expiresOn()),id);
        s.audit(actor,"EXTERNAL_CREATE",id+":"+body.personType()+":"+body.expiresOn(),"SUCCESS",id);
        return Map.of("id",id,"personType",body.personType().name(),"expiresOn",body.expiresOn().toString());
    }

    @Transactional public Map<String,Object> renew(long actor,long id,LocalDate expiresOn){
        admin(actor);s.requireUser(actor,id);
        var rows=s.rows("select person_type from account_policy where user_id=? and deleted=false",id);
        if(rows.isEmpty())throw new ResourceNotFoundException("账号不存在");
        PersonType type;
        try{type=PersonType.valueOf((String)rows.getFirst().get("person_type"));}catch(IllegalArgumentException e){throw new IllegalArgumentException("只有外部人员和临时人员需要续期");}
        validity(type,expiresOn);
        s.db().update("update account_policy set expires_at=? where user_id=?",endOf(expiresOn),id);
        s.changed(id);s.audit(actor,"EXTERNAL_RENEW",id+":"+expiresOn,"SUCCESS",id);
        return Map.of("id",id,"expiresOn",expiresOn.toString());
    }

    public List<Map<String,Object>> list(long actor,int remindDays){
        admin(actor);if(remindDays<1||remindDays>90)throw new IllegalArgumentException("提醒天数应在 1 到 90 之间");
        String scope=s.superAdmin(actor)?"":" and e.department_id in(select department_id from admin_department where user_id="+actor+")";
        var rows=s.rows("select u.id,ac.username,u.display_name,u.enabled,p.person_type,p.expires_at,e.company,e.department_id,e.post_id from account_policy p join sys_user u on u.id=p.user_id left join employee_profile e on e.user_id=u.id where p.person_type in('EXTERNAL','TEMPORARY') and p.deleted=false"+scope+" order by p.expires_at,u.id");
        LocalDate today=LocalDate.now(ZONE);
        for(var row:rows){
            Object value=row.remove("expires_at");
            Instant expires=value instanceof OffsetDateTime offset?offset.toInstant():((java.sql.Timestamp)value).toInstant();
            LocalDate last=expires.atZone(ZONE).toLocalDate().minusDays(1);long left=Duration.between(today.atStartOfDay(ZONE),last.atStartOfDay(ZONE)).toDays();
            row.put("expires_on",last.toString());row.put("days_left",left);
            row.put("status",!expires.isAfter(Instant.now())?"EXPIRED":left<remindDays?"EXPIRING":"ACTIVE");
        }
        return rows;
    }
}
