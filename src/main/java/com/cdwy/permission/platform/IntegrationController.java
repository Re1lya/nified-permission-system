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
/**
 * HR/OA 服务间集成入口，以签名认证调用来源，不依赖管理员浏览器登录。
 * 同一文件内 IntegrationService 负责验签、事件幂等、事务编排，再委托 HrSynchronization 等模块。
 */
@RestController @RequestMapping("/api/integration")
public class IntegrationController {
    private final IntegrationService service;
    public IntegrationController(IntegrationService service){this.service=service;}
    @PostMapping("/{source}") public Map<String,Object> accept(@PathVariable String source,@RequestHeader("X-Request-Id") String id,@RequestHeader("X-Timestamp") String timestamp,@RequestHeader("X-Signature") String signature,@RequestBody String body){return service.accept(source,id,timestamp,signature,body);}
}

@Service
class IntegrationService {
    private final PlatformStore s;private final RbacService rbac;private final JwtTokenService tokens;private final TransactionTemplate tx;private final ObjectMapper json;
    private final String hrSecret,oaSecret;private final HrSynchronization sync;
    IntegrationService(PlatformStore s,RbacService rbac,JwtTokenService tokens,TransactionTemplate tx,ObjectMapper json,HrSynchronization sync,@Value("${permission.integration.hr-secret:}")String hrSecret,@Value("${permission.integration.oa-secret:}")String oaSecret){this.sync=sync;this.s=s;this.rbac=rbac;this.tokens=tokens;this.tx=tx;this.json=json;this.hrSecret=hrSecret;this.oaSecret=oaSecret;}
    public record Event(String action,String username,String displayName,Long userId,Long departmentId,Long postId,String phone,String company,List<Long> roleIds,Long approvalId,String decision,String externalId,String name,String parentExternalId,List<Event> records,String mode,String requestId,String departmentExternalId,String postExternalId) {}
    // 先校验五分钟时间窗口和 HMAC，再处理事件；时间窗口限制旧请求重放，业务幂等负责安全重试。
    public synchronized Map<String,Object> accept(String source,String id,String timestamp,String signature,String body){
        String secret=switch(source){case "hr"->hrSecret;case "oa"->oaSecret;default->throw new IllegalArgumentException("未知集成来源");};
        if(secret.length()<32)throw new ForbiddenException("该集成未配置启用");
        if(!id.matches("[A-Za-z0-9_-]{1,64}")||body.length()>12_000_000)throw new IllegalArgumentException("请求标识或请求体不合法");
        try {
            long seconds=Long.parseLong(timestamp);if(Math.abs(Instant.now().getEpochSecond()-seconds)>300)throw new ForbiddenException("请求签名已过期");
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] expected=mac.doFinal((source+"\n"+timestamp+"\n"+id+"\n"+body).getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(expected,HexFormat.of().parseHex(signature)))throw new ForbiddenException("请求签名不正确");
        }catch(ForbiddenException e){throw e;}catch(Exception e){throw new ForbiddenException("请求签名不正确");}
        final String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    // 成功事件记录与业务数据原子提交；发生异常退出后再单独记录 sync_failure，避免失败记录也被回滚。
        try { return tx.execute(status->{
            Event event;try{event=json.readValue(body,Event.class);}catch(Exception e){throw new IllegalArgumentException("集成数据格式不正确");}
            if(event==null)throw new IllegalArgumentException("集成事件不能为空");
            var failure=s.rows("select digest from sync_failure where source=? and request_id=?",source,id);
            if(!failure.isEmpty()&&!digest.equals(failure.getFirst().get("digest")))throw new ConflictException("修正内容后必须使用新的请求编号");
    // 幂等键是 source + request_id；相同编号相同内容复用结果，相同编号不同摘要必须拒绝。
            var previous=s.rows("select digest,result,response from integration_event where source=? and request_id=?",source,id);
            if(!previous.isEmpty()){if(!digest.equals(previous.getFirst().get("digest")))throw new ConflictException("同一请求编号不能对应不同内容");return Map.of("reused",true,"result",previous.getFirst().get("result"),"detail",previous.getFirst().get("response")==null?Map.of():json.readValue(previous.getFirst().get("response").toString(),Map.class));}
            Map<String,Object> detail=new LinkedHashMap<>();Long user=null;
            if(source.equals("hr")&&"RECONCILE".equals(event.action()))detail.putAll(sync.reconcile(event.records()));
            else if(source.equals("hr")&&"SYNC".equals(event.action())) {
                if(!Set.of("FULL","INCREMENTAL").contains(Objects.toString(event.mode(),""))||event.records()==null||event.records().size()>20000)throw new IllegalArgumentException("同步需要 FULL/INCREMENTAL 模式，最多20000条");
                for(var item:event.records())hr(item);
                detail.put("processed",event.records().size());detail.put("mode",event.mode());
            } else if(source.equals("oa")&&"QUERY_PERMISSIONS".equals(event.action())) {
                if(event.userId()==null)throw new IllegalArgumentException("缺少员工编号");s.validAccount(event.userId());
                detail.put("permissions",rbac.effectivePermissions(event.userId()));detail.put("permissionVersion",s.version(event.userId()));
            } else if(source.equals("oa")&&"QUERY_APPLICATION_STATUS".equals(event.action())) {
                if(event.requestId()==null)throw new IllegalArgumentException("需要 OA 申请单号");
                var applications=s.rows("select request_id,user_id,role_id,decision,created_at from approval_record where request_id=?",event.requestId());
                if(applications.isEmpty())throw new ResourceNotFoundException("申请不存在");detail.put("application",applications.getFirst());
            } else if(source.equals("oa")&&"APPLICATION".equals(event.action()))user=registerApplication(event);
            else user=source.equals("hr")?hr(event):oa(event);
            s.db().update("update sync_failure set status='RESOLVED',resolution='原始请求重试成功' where source=? and request_id=?",source,id);
            s.db().update("insert into integration_event(source,request_id,digest,result,created_at,user_id,response) values(?,?,?,'SUCCESS',?,?,?)",source,id,digest,Instant.now(),user,json.writeValueAsString(detail));
            s.audit(null,"INTEGRATION_"+source.toUpperCase(Locale.ROOT),id,"SUCCESS",user);
            return Map.of("reused",false,"result","SUCCESS","detail",detail);
        }); } catch(RuntimeException failure) {
            // The business transaction has rolled back. Save only a digest and a bounded reason, never a payload or secret.
            try {tx.executeWithoutResult(status->{
                if(s.count("select count(*) from sync_failure where source=? and request_id=?",source,id)==0)
                    s.db().update("insert into sync_failure(source,request_id,digest,error,status,created_at) values(?,?,?,?,'OPEN',?)",source,id,digest,
                        failure instanceof IllegalArgumentException||failure instanceof ConflictException||failure instanceof ForbiddenException?Objects.toString(failure.getMessage(),"同步失败").substring(0,Math.min(1000,Objects.toString(failure.getMessage(),"同步失败").length())):"数据冲突或处理失败，请核对外部编号和关联数据",Instant.now());
            });} catch(org.springframework.dao.DuplicateKeyException race){/* Another instance recorded the same failure. */}
            throw failure;
        }
    }
    private Long registerApplication(Event e){
        if(e.userId()==null||e.roleIds()==null||e.roleIds().size()!=1||e.requestId()==null||!e.requestId().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("OA 申请需要申请单号、用户和一个岗位角色");
        s.validAccount(e.userId());long role=e.roleIds().getFirst();
        if(s.count("select count(*) from sys_post p join sys_role r on r.id=p.role_id where p.deleted=false and r.id=? and r.enabled=true",role)!=1)throw new IllegalArgumentException("只能申请启用的岗位角色");
        var existing=s.rows("select user_id,role_id from approval_record where request_id=?",e.requestId());
        if(!existing.isEmpty()) {if(((Number)existing.getFirst().get("user_id")).longValue()!=e.userId()||((Number)existing.getFirst().get("role_id")).longValue()!=role)throw new ConflictException("申请单内容冲突");return e.userId();}
        s.db().update("insert into approval_record(request_id,user_id,role_id,decision,created_at) values(?,?,?,'PENDING',?)",e.requestId(),e.userId(),role,Instant.now());return e.userId();
    }
    private Long hr(Event input){
        Event e=sync.resolve(input);
        if(e.action()==null)throw new IllegalArgumentException("缺少 HR 事件类型");
        if(e.action().equals("DEPARTMENT")){sync.department(e);return null;}
        if(e.action().equals("POST")){sync.post(e);return null;}
        if(e.action().equals("JOIN")){profile(e);return sync.join(e);}
        Long resolved=e.externalId()==null?e.userId():sync.lookup("EMPLOYEE",e.externalId());
        if(resolved==null)throw new IllegalArgumentException("缺少或不存在员工编号");long id=resolved;
        if(s.superAdmin(id)||s.departmentAdmin(id))throw new ForbiddenException("HR 不能直接变更管理员账号，请先人工解除管理身份");
        if(s.count("select count(*) from employee_profile where user_id=?",id)!=1)throw new IllegalArgumentException("员工不存在");
        s.db().queryForObject("select id from sys_user where id=? for update",Long.class,id);
        switch(e.action()){
            case "UPDATE" -> {
                profile(e);var current=s.rows("select department_id,post_id from employee_profile where user_id=?",id).getFirst();
                if(((Number)current.get("department_id")).longValue()!=e.departmentId()||((Number)current.get("post_id")).longValue()!=e.postId())throw new ConflictException("组织岗位变更必须使用 TRANSFER 并给出完整授权集合");
                if(e.displayName()==null||e.displayName().isBlank()||e.displayName().length()>128)throw new IllegalArgumentException("姓名不合法");
                s.db().update("update sys_user set display_name=? where id=?",e.displayName(),id);
                s.db().update("update employee_profile set phone=?,company=? where user_id=?",e.phone(),e.company(),id);
            }
    // 调岗是一个业务事务：回收旧角色、修改组织岗位、写入明确给出的新角色集合及通知，缺一步都应回滚。
            case "TRANSFER" -> {
                long oldDepartment=s.db().queryForObject("select department_id from employee_profile where user_id=?",Long.class,id);
                profile(e);if(e.roleIds()==null||e.roleIds().size()>100)throw new IllegalArgumentException("调岗必须明确提供完整岗位角色列表，空数组表示回收全部");
                for(long role:e.roleIds())if(s.count("select count(*) from sys_post p join sys_role r on r.id=p.role_id where p.deleted=false and p.id=? and r.id=? and r.enabled=true",e.postId(),role)!=1)throw new IllegalArgumentException("调岗只能授予新岗位对应的启用角色");
                s.db().update("delete from sys_user_role where user_id=?",id);s.db().update("update employee_profile set department_id=?,post_id=?,phone=?,company=? where user_id=?",e.departmentId(),e.postId(),e.phone(),e.company(),id);
                for(long role:new HashSet<>(e.roleIds()))rbac.assignRole(id,role);
                sync.notice(oldDepartment,id,"TRANSFER_OUT","员工已调出，原岗位授权已回收");
                sync.notice(e.departmentId(),id,"TRANSFER_IN","员工已调入，新岗位授权已在同一事务中设置");
            }
            case "LEAVE" -> {s.db().update("update sys_user set enabled=false where id=?",id);s.db().update("delete from sys_user_role where user_id=?",id);tokens.revokeAll(id);}
            default -> throw new IllegalArgumentException("HR 仅支持 DEPARTMENT、POST、JOIN、UPDATE、TRANSFER、LEAVE");
        }
        s.changed(id);return id;
    }
    private void profile(Event e){if(e.departmentId()==null||e.postId()==null||e.phone()==null||!e.phone().matches("[0-9+ -]{5,32}")||e.company()==null||e.company().isBlank()||e.company().length()>128)throw new IllegalArgumentException("员工组织或联系方式不完整");if(s.count("select count(*) from sys_department where id=? and deleted=false",e.departmentId())!=1||s.count("select count(*) from sys_post where id=? and deleted=false",e.postId())!=1)throw new IllegalArgumentException("部门或岗位不存在");}
    // 审批结果不是任意授权指令：先锁定已登记申请，再检查 PENDING → 终态转换；同结果可重放，冲突结果拒绝。
    private Long oa(Event e){
        if((e.approvalId()==null&&e.requestId()==null)||!Set.of("APPROVED","REJECTED","CANCELLED").contains(e.decision()==null?"":e.decision()))throw new IllegalArgumentException("审批编号或结论错误");
        var rows=e.requestId()!=null?s.rows("select * from approval_record where request_id=? for update",e.requestId()):s.rows("select * from approval_record where id=? for update",e.approvalId());if(rows.isEmpty())throw new IllegalArgumentException("审批申请不存在");var row=rows.getFirst();
        if(!"PENDING".equals(row.get("decision"))){if(e.decision().equals(row.get("decision")))return ((Number)row.get("user_id")).longValue();throw new ConflictException("审批已经处理且结论不同");}long user=((Number)row.get("user_id")).longValue(),role=((Number)row.get("role_id")).longValue();
        if(e.decision().equals("APPROVED")){s.validAccount(user);if(s.count("select count(*) from sys_user where id=? and enabled=true",user)!=1)throw new ConflictException("申请人已停用");rbac.assignRole(user,role);}
        s.db().update("update approval_record set decision=? where id=?",e.decision(),row.get("id"));return user;
    }
}
