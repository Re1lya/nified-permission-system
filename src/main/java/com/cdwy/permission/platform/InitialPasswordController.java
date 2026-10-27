package com.cdwy.permission.platform;

import com.cdwy.permission.dto.SetPasswordRequest;
import com.cdwy.permission.exception.ForbiddenException;
import com.cdwy.permission.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * BR-03: imported and HR-created accounts start with a random, undistributed password. An administrator issues random initial passwords
 * for one department at a time; they must be changed at first login and appear only in this one response.
 */
@RestController @RequestMapping("/api/platform/initial-passwords")
public class InitialPasswordController {
    private final InitialPasswordService service;private final JwtTokenService tokens;
    public InitialPasswordController(InitialPasswordService service,JwtTokenService tokens){this.service=service;this.tokens=tokens;}
    public record Batch(@NotNull @Positive Long departmentId,@Min(1) @Max(InitialPasswordService.MAX_BATCH) Integer limit) {}
    @GetMapping public Map<String,Object> pending(@RequestHeader("Authorization") String auth,@RequestParam long departmentId){return service.pending(tokens.requireUserId(auth),departmentId);}
    @PostMapping public Map<String,Object> issue(@RequestHeader("Authorization") String auth,@Valid @RequestBody Batch body){return service.issue(tokens.requireUserId(auth),body.departmentId(),body.limit()==null?InitialPasswordService.MAX_BATCH:body.limit());}
}

@Service
class InitialPasswordService {
    static final int MAX_BATCH=200;
    private static final String UPPER="ABCDEFGHJKLMNPQRSTUVWXYZ",LOWER="abcdefghijkmnpqrstuvwxyz",DIGIT="23456789",SYMBOL="!@#$%&*?";
    private final SecureRandom random=new SecureRandom();
    private final PlatformStore s;private final RbacService rbac;
    InitialPasswordService(PlatformStore s,RbacService rbac){this.s=s;this.rbac=rbac;}

    private void scope(long actor,long department){if(!s.superAdmin(actor)&&!s.departmentAdmin(actor))throw new ForbiddenException("需要管理权限");s.requireDepartment(actor,department);}
    private static final String WITHOUT_PASSWORD=" from sys_user u join sys_account ac on ac.user_id=u.id join employee_profile e on e.user_id=u.id left join account_policy p on p.user_id=u.id where e.department_id=? and (ac.password_hash is null or (p.must_change_password=true and p.initial_password_issued=false)) and u.enabled=true and u.administrator=false and (p.deleted is null or p.deleted=false)";

    public Map<String,Object> pending(long actor,long department){scope(actor,department);return Map.of("departmentId",department,"pending",s.count("select count(*)"+WITHOUT_PASSWORD,department));}

    // 按部门检查管理范围，批量生成密码、编码落库、标记首次必须改密，并仅在本次响应返回明文。
    // 后续查询不返回密码；日志只记录数量。批次上限用于控制单次处理规模。
    @Transactional public Map<String,Object> issue(long actor,long department,int limit){
        scope(actor,department);
        var rows=s.rows("select u.id,ac.username,u.display_name"+WITHOUT_PASSWORD+" order by u.id limit ?",department,limit);
        List<Map<String,Object>> issued=new ArrayList<>();
        for(var row:rows){
            long id=((Number)row.get("id")).longValue();String password=generate();
            s.ensurePolicy(id);rbac.setPassword(id,new SetPasswordRequest(password));
            s.db().update("update account_policy set must_change_password=true,initial_password_issued=true where user_id=?",id);
            issued.add(Map.of("username",row.get("username"),"displayName",row.get("display_name"),"initialPassword",password));
        }
        // The audit records how many accounts were initialised, never the passwords themselves.
        s.auditDepartment(actor,"INITIAL_PASSWORDS",department+":"+issued.size(),"SUCCESS",department);
        return Map.of("departmentId",department,"issued",issued,"remaining",s.count("select count(*)"+WITHOUT_PASSWORD,department));
    }

    /** 12 characters with all four classes, which satisfies BR-04 (at least three of four). */
    String generate(){
        List<Character> chars=new ArrayList<>();
        for(String pool:List.of(UPPER,LOWER,DIGIT,SYMBOL))chars.add(pool.charAt(random.nextInt(pool.length())));
        String all=UPPER+LOWER+DIGIT+SYMBOL;
        while(chars.size()<12)chars.add(all.charAt(random.nextInt(all.length())));
        Collections.shuffle(chars,random);
        StringBuilder out=new StringBuilder();chars.forEach(out::append);return out.toString();
    }
}
