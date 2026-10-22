package com.cdwy.permission.platform;

import com.cdwy.permission.service.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/platform/sync")
public class SyncAdministration {
    private final PlatformStore s;private final JwtTokenService tokens;
    public SyncAdministration(PlatformStore s,JwtTokenService tokens){this.s=s;this.tokens=tokens;}
    @GetMapping("/failures") public List<Map<String,Object>> failures(@RequestHeader("Authorization")String auth){s.requireSuper(tokens.requireUserId(auth));return s.rows("select * from sync_failure order by id desc limit 500");}
    @GetMapping("/identities") public List<Map<String,Object>> identities(@RequestHeader("Authorization")String auth,@RequestParam(defaultValue="EMPLOYEE")String kind,@RequestParam(defaultValue="0")int page){s.requireSuper(tokens.requireUserId(auth));if(!Set.of("EMPLOYEE","DEPARTMENT","POST").contains(kind)||page<0||page>100000)throw new IllegalArgumentException("查询条件不合法");return s.rows("select * from hr_identity where kind=? order by id limit 100 offset ?",kind,(long)page*100);}
    public record Resolution(@NotBlank @Size(max=1000)String reason){}
    @PostMapping("/failures/{id}/resolve") @Transactional public void resolve(@RequestHeader("Authorization")String auth,@PathVariable long id,@Valid @RequestBody Resolution body){long a=tokens.requireUserId(auth);s.requireSuper(a);if(s.db().update("update sync_failure set status='RESOLVED',resolution=? where id=? and status='OPEN'",body.reason(),id)!=1)throw new IllegalArgumentException("异常不存在或已经处理");s.audit(a,"SYNC_RESOLVE",Long.toString(id),"SUCCESS");}
}
