package com.cdwy.permission.platform;

import com.cdwy.permission.dto.*;
import com.cdwy.permission.service.*;
import com.cdwy.permission.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Signed server-to-server ingress; request IDs are stable business event IDs. */
@RestController @RequestMapping("/api/integration")
public class IntegrationController {
    private final IntegrationService service;
    public IntegrationController(IntegrationService service){this.service=service;}
    @PostMapping("/{source}") public Map<String,Object> accept(@PathVariable String source,@RequestHeader("X-Request-Id") String id,@RequestHeader("X-Timestamp") String timestamp,@RequestHeader("X-Signature") String signature,@RequestBody String body){return service.accept(source,id,timestamp,signature,body);}
}

@Service
class IntegrationService {
    private final PlatformStore s;private final RbacService rbac;private final JwtTokenService tokens;private final TransactionTemplate tx;private final ObjectMapper json;
    private final String hrSecret,oaSecret;
    IntegrationService(PlatformStore s,RbacService rbac,JwtTokenService tokens,TransactionTemplate tx,ObjectMapper json,@Value("${permission.integration.hr-secret:}")String hrSecret,@Value("${permission.integration.oa-secret:}")String oaSecret){this.s=s;this.rbac=rbac;this.tokens=tokens;this.tx=tx;this.json=json;this.hrSecret=hrSecret;this.oaSecret=oaSecret;}
    public record Event(String action,String username,String displayName,Long userId,Long departmentId,Long postId,String phone,String company,List<Long> roleIds,Long approvalId,String decision) {}
    public synchronized Map<String,Object> accept(String source,String id,String timestamp,String signature,String body){
        String secret=switch(source){case "hr"->hrSecret;case "oa"->oaSecret;default->throw new IllegalArgumentException("未知集成来源");};
        if(secret.length()<32)throw new ForbiddenException("该集成未配置启用");
        if(!id.matches("[A-Za-z0-9_-]{1,64}")||body.length()>65536)throw new IllegalArgumentException("请求标识或请求体不合法");
        try {
            long seconds=Long.parseLong(timestamp);if(Math.abs(Instant.now().getEpochSecond()-seconds)>300)throw new ForbiddenException("请求签名已过期");
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] expected=mac.doFinal((source+"\n"+timestamp+"\n"+id+"\n"+body).getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(expected,HexFormat.of().parseHex(signature)))throw new ForbiddenException("请求签名不正确");
        }catch(ForbiddenException e){throw e;}catch(Exception e){throw new ForbiddenException("请求签名不正确");}
        final String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
        Event event;try{event=json.readValue(body,Event.class);}catch(Exception e){throw new IllegalArgumentException("集成数据格式不正确");}
        return tx.execute(status->{
            var previous=s.rows("select digest,result from integration_event where source=? and request_id=?",source,id);
            if(!previous.isEmpty()){if(!digest.equals(previous.getFirst().get("digest")))throw new ConflictException("同一请求编号不能对应不同内容");return Map.of("reused",true,"result",previous.getFirst().get("result"));}
            Long user=source.equals("hr")?hr(event):oa(event);
            s.db().update("insert into integration_event(source,request_id,digest,result,created_at,user_id) values(?,?,?,'SUCCESS',?,?)",source,id,digest,Instant.now(),user);
            s.audit(null,"INTEGRATION_"+source.toUpperCase(Locale.ROOT),id,"SUCCESS");
            return Map.of("reused",false,"result","SUCCESS");
        });
    }
    private Long hr(Event e){
        if(e.action()==null)throw new IllegalArgumentException("缺少 HR 事件类型");
        if(e.action().equals("JOIN")){
            if(e.username()==null||!e.username().matches("[A-Za-z0-9_.-]{1,64}")||e.displayName()==null||e.displayName().isBlank()||e.displayName().length()>128)throw new IllegalArgumentException("员工账号或姓名不合法");
            profile(e);long id=rbac.createUser(new CreateUserRequest(e.username(),e.displayName())).id();
            s.db().update("insert into employee_profile(user_id,department_id,post_id,phone,company) values(?,?,?,?,?)",id,e.departmentId(),e.postId(),e.phone(),e.company());s.ensurePolicy(id);return id;
        }
        if(e.userId()==null)throw new IllegalArgumentException("缺少员工编号");long id=e.userId();
        if(s.superAdmin(id)||s.departmentAdmin(id))throw new ForbiddenException("HR 不能直接变更管理员账号，请先人工解除管理身份");
        if(s.count("select count(*) from employee_profile where user_id=?",id)!=1)throw new IllegalArgumentException("员工不存在");
        s.db().queryForObject("select id from sys_user where id=? for update",Long.class,id);
        switch(e.action()){
            case "TRANSFER" -> {
                profile(e);if(e.roleIds()==null||e.roleIds().size()>100)throw new IllegalArgumentException("调岗必须明确提供完整岗位角色列表，空数组表示回收全部");
                for(long role:e.roleIds())if(s.count("select count(*) from sys_post p join sys_role r on r.id=p.role_id where p.id=? and r.id=? and r.enabled=true",e.postId(),role)!=1)throw new IllegalArgumentException("调岗只能授予新岗位对应的启用角色");
                s.db().update("delete from sys_user_role where user_id=?",id);s.db().update("update employee_profile set department_id=?,post_id=?,phone=?,company=? where user_id=?",e.departmentId(),e.postId(),e.phone(),e.company(),id);
                for(long role:new HashSet<>(e.roleIds()))rbac.assignRole(id,role);
            }
            case "LEAVE" -> {s.db().update("update sys_user set enabled=false where id=?",id);s.db().update("delete from sys_user_role where user_id=?",id);tokens.revokeAll(id);}
            default -> throw new IllegalArgumentException("HR 仅支持 JOIN、TRANSFER、LEAVE");
        }
        s.changed(id);return id;
    }
    private void profile(Event e){if(e.departmentId()==null||e.postId()==null||e.phone()==null||!e.phone().matches("[0-9+ -]{5,32}")||e.company()==null||e.company().isBlank()||e.company().length()>128)throw new IllegalArgumentException("员工组织或联系方式不完整");if(s.count("select count(*) from sys_department where id=?",e.departmentId())!=1||s.count("select count(*) from sys_post where id=?",e.postId())!=1)throw new IllegalArgumentException("部门或岗位不存在");}
    private Long oa(Event e){
        if(e.approvalId()==null||!Set.of("APPROVED","REJECTED").contains(e.decision()==null?"":e.decision()))throw new IllegalArgumentException("审批编号或结论错误");
        var rows=s.rows("select * from approval_record where id=? for update",e.approvalId());if(rows.isEmpty())throw new IllegalArgumentException("审批申请不存在");var row=rows.getFirst();
        if(!"PENDING".equals(row.get("decision")))throw new ConflictException("审批已经处理");long user=((Number)row.get("user_id")).longValue(),role=((Number)row.get("role_id")).longValue();
        if(e.decision().equals("APPROVED")){s.validAccount(user);if(s.count("select count(*) from sys_user where id=? and enabled=true",user)!=1)throw new ConflictException("申请人已停用");rbac.assignRole(user,role);}
        s.db().update("update approval_record set decision=? where id=?",e.decision(),e.approvalId());return user;
    }
}
