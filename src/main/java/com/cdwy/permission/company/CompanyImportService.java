package com.cdwy.permission.company;

import com.cdwy.permission.entity.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CompanyImportService {
    public record Department(String name,String parent,String manager) {}
    public record Employee(String name,String username,String company,String department,String post,String phone) {}
    public record Feature(String system,String module,String name,String description,Map<String,Set<String>> grants) {}
    public record Issue(String sheet,int row,String message) {}
    public record Plan(String id,Long actor,String fingerprint,Instant expires,List<Department> departments,
                       List<Employee> employees,List<Feature> features,List<Issue> errors,List<Issue> warnings) {}
    private final Map<String,Plan> plans=new LinkedHashMap<>();
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public CompanyImportService(EntityManager em,JdbcTemplate jdbc,TransactionTemplate tx) {this.em=em;this.jdbc=jdbc;this.tx=tx;}
    private static final Map<String,String> ACTIONS=Map.of("查看","view","新增","create","编辑","edit","审批","approve","导出","export","查看本人","view_self","编辑本人","edit_self");
    private static final List<String> TEMPLATES=List.of("系统管理员","公司领导","部门经理","项目经理","普通员工");

    public synchronized Map<String,Object> preview(Long actor,byte[] departmentFile,byte[] employeeFile,byte[] permissionFile) {
        plans.values().removeIf(p->p.expires().isBefore(Instant.now()));
        if(plans.size()>=10) throw new IllegalArgumentException("预览任务已满，请稍后重试，或先完成已有导入");
        var reader=new XlsxReader(); var ds=reader.read(departmentFile); var es=reader.read(employeeFile); var fs=reader.read(permissionFile);
        header(ds,0,List.of("部门名称","部门经理","上级部门"));
        header(es,0,List.of("姓名","用户名","分公司","部门","岗位","电话号码"));
        header(fs,1,List.of("软件系统","功能模块","功能点","功能说明","系统管理员","公司领导","部门经理","项目经理","普通员工"));
        var errors=new ArrayList<Issue>(); var warnings=new ArrayList<Issue>();
        var deps=new ArrayList<Department>(); var employees=new ArrayList<Employee>(); var features=new ArrayList<Feature>();
        var names=new HashSet<String>(); var usernames=new HashSet<String>(); var phones=new HashSet<String>();
        for(var r:ds.subList(1,ds.size())) {
            if(r.get("A").isEmpty() || !names.add(r.get("A"))) errors.add(new Issue("部门",r.number(),"部门名称为空或重复"));
            if(r.get("A").length()>128||r.get("B").length()>128||r.get("C").length()>128) errors.add(new Issue("部门",r.number(),"名称超过 128 字符"));
            deps.add(new Department(r.get("A"),r.get("C"),r.get("B")));
        }
        var parents=new HashMap<String,String>(); deps.forEach(d->parents.put(d.name(),d.parent()));
        for(var d:deps) {
            var visited=new HashSet<String>(); String node=d.name();
            while(!node.isEmpty()) {
                if(!visited.add(node)){errors.add(new Issue("部门",0,"组织树存在循环: "+d.name()));break;}
                if(!parents.containsKey(node)){errors.add(new Issue("部门",0,"上级部门不存在: "+node));break;}
                node=parents.get(node);
            }
        }
        for(var r:es.subList(1,es.size())) {
            String dep=r.get("D");
            if(!r.get("C").equals("总公司")&&!dep.equals(r.get("C"))) dep=r.get("C")+"-"+dep;
            if(!names.contains(dep)) errors.add(new Issue("员工",r.number(),"分公司与部门不能匹配组织表"));
            if(!r.get("B").matches("[A-Za-z0-9_-]{1,64}")||!usernames.add(r.get("B"))) errors.add(new Issue("员工",r.number(),"用户名不合法或重复"));
            if(!r.get("F").matches("[0-9+ -]{6,32}")) errors.add(new Issue("员工",r.number(),"电话号码格式不合法"));
            if(!phones.add(r.get("F"))) warnings.add(new Issue("员工",r.number(),"电话号码重复，不可用该号码唯一识别账号"));
            for(String c:List.of("A","C","D","E")) if(r.get(c).isEmpty()||r.get(c).length()>128) errors.add(new Issue("员工",r.number(),c+" 列必填且最多 128 字符"));
            employees.add(new Employee(r.get("A"),r.get("B"),r.get("C"),dep,r.get("E"),r.get("F")));
        }
        String system="",module=""; var featureKeys=new HashSet<String>();
        for(var r:fs.subList(2,fs.size())) {
            if(!r.get("A").isEmpty()){system=r.get("A");module="";}
            if(!r.get("B").isEmpty()) module=r.get("B");
            if(system.isEmpty()||module.isEmpty()||r.get("C").isEmpty()||!featureKeys.add(system+"/"+module+"/"+r.get("C"))) errors.add(new Issue("权限",r.number(),"系统、模块或功能缺失，或功能重复"));
            if(system.length()>128||module.length()>128||r.get("C").length()>100) errors.add(new Issue("权限",r.number(),"系统或功能名称过长"));
            Map<String,Set<String>> grants=new LinkedHashMap<>(); Set<String> all=new TreeSet<>();
            for(int i=0;i<5;i++) {
                String cell=r.get(String.valueOf((char)('E'+i)));
                var set=new TreeSet<String>(); grants.put(TEMPLATES.get(i),set);
                if(cell.isEmpty()) {warnings.add(new Issue("权限",r.number(),TEMPLATES.get(i)+" 权限为空，保留为未确认，不授予"));continue;}
                if(cell.equals("无")) continue;
                if(cell.equals("全部")){set.add("*");continue;}
                for(String action:cell.split("/")) {
                    if(action.equals("view")){action="查看";warnings.add(new Issue("权限",r.number(),"view 规范化为查看"));}
                    String code=ACTIONS.get(action);
                    if(code==null) errors.add(new Issue("权限",r.number(),"不识别的操作: "+action));
                    else {set.add(code);all.add(code);}
                }
            }
            // "All" expands only to explicitly defined actions on this function. No implicit CRUD.
            if(all.isEmpty()&&grants.values().stream().anyMatch(s->s.contains("*"))) warnings.add(new Issue("权限",r.number(),"只有全部但没有具体操作定义，暂不生成操作权限"));
            for(var set:grants.values()) if(set.remove("*")) set.addAll(all);
            features.add(new Feature(system,module,r.get("C"),r.get("D"),grants));
        }
        warnings.add(new Issue("权限",0,"本人范围操作只作为独立权限编码保存，不代表接入应用已实现数据范围校验"));
        warnings.add(new Issue("岗位",0,"创建岗位同名角色，但不自动分配给员工；五类清单保存为模板，需管理员明确应用"));
        var digest=digest(departmentFile,employeeFile,permissionFile);
        var plan=new Plan(UUID.randomUUID().toString(),actor,digest,Instant.now().plusSeconds(1800),deps,employees,features,errors,warnings);
        plans.put(plan.id(),plan);
        return summary(plan);
    }
    private Map<String,Object> summary(Plan p) {
        return Map.of("previewId",p.id(),"fingerprint",p.fingerprint(),"expiresAt",p.expires(),
            "counts",Map.of("departments",p.departments().size(),"employees",p.employees().size(),"posts",p.employees().stream().map(Employee::post).distinct().count(),"systems",p.features().stream().map(Feature::system).distinct().count(),"modules",p.features().stream().map(f->f.system()+"/"+f.module()).distinct().count(),"functions",p.features().size()),
            "errors",p.errors().stream().limit(200).toList(),"errorCount",p.errors().size(),"warnings",p.warnings().stream().limit(200).toList(),"warningCount",p.warnings().size());
    }
    public synchronized Map<String,Object> commit(Long actor,String id,boolean acknowledged) {
        var p=plans.get(id);
        if(p==null||!p.actor().equals(actor)||p.expires().isBefore(Instant.now())) throw new IllegalArgumentException("预览不存在、已过期或不属于当前管理员，请重新上传");
        if(!p.errors().isEmpty()||!acknowledged) throw new IllegalArgumentException("请先修复错误，并确认未确定权限不授予及保留已有数据的规则");
        return tx.execute(status->importPlan(p));
    }
    private Map<String,Object> importPlan(Plan p) {
        var old=jdbc.queryForList("select id from company_import_batch where fingerprint=?",p.fingerprint());
        if(!old.isEmpty()) return Map.of("reused",true,"message","相同三表已导入，不重复执行","fingerprint",p.fingerprint());
        Map<String,CompanyData.Department> departments=new HashMap<>();
        em.createQuery("from CompanyData$Department",CompanyData.Department.class); // Entity queries below use JPA simple names.
        return insertPlan(p,departments);
    }
    private Map<String,Object> insertPlan(Plan p,Map<String,CompanyData.Department> deps) {
        for(var d:em.createQuery("from CompanyData$Department",CompanyData.Department.class).getResultList()) deps.put(d.name,d);
        for(var d:p.departments()) if(!deps.containsKey(d.name())) {
            var entity=new CompanyData.Department();entity.name=d.name();entity.managerName=d.manager();em.persist(entity);deps.put(d.name(),entity);
        }
        for(var d:p.departments()) {
            var entity=deps.get(d.name());
            String existing=entity.parent==null?"":entity.parent.name;
            if(entity.parent!=null&&!existing.equals(d.parent())) throw new IllegalArgumentException("已有组织上级发生变化，请通过受控调动处理: "+d.name());
            if(!d.parent().isEmpty()) entity.parent=deps.get(d.parent());
        }
        Map<String,CompanyData.Post> posts=new HashMap<>();
        for(var post:em.createQuery("from CompanyData$Post",CompanyData.Post.class).getResultList()) posts.put(post.name,post);
        for(var name:p.employees().stream().map(Employee::post).distinct().toList()) if(!posts.containsKey(name)) {
            var role=new Role("post_"+key(name),name,"导入岗位；权限需管理员配置");em.persist(role);
            var post=new CompanyData.Post();post.name=name;post.role=role;em.persist(post);posts.put(name,post);
        }
        em.flush();
        var users=new HashMap<String,Long>();
        jdbc.query("select id,username from sys_user",(org.springframework.jdbc.core.RowCallbackHandler)rs->users.put(rs.getString(2),rs.getLong(1)));
        var newUsers=new ArrayList<Object[]>();
        for(var e:p.employees()) if(!users.containsKey(e.username())) newUsers.add(new Object[]{e.username(),e.name(),true,false});
        jdbc.batchUpdate("insert into sys_user(username,display_name,enabled,administrator) values (?,?,?,?)",newUsers);
        Set<String> createdUsernames=new HashSet<>();
        for(Object[] row:newUsers)createdUsernames.add((String)row[0]);
        jdbc.query("select id,username from sys_user",(org.springframework.jdbc.core.RowCallbackHandler)rs->users.put(rs.getString(2),rs.getLong(1)));
        Set<Long> existingProfiles=new HashSet<>(jdbc.queryForList("select user_id from employee_profile",Long.class));
        var newProfiles=new ArrayList<Object[]>();
        for(var e:p.employees()) {
            Long uid=users.get(e.username());
            // Refuse attaching HR data to a pre-existing manually maintained identity.
            if(!existingProfiles.contains(uid)&&!createdUsernames.contains(e.username())) throw new IllegalArgumentException("员工用户名与已有非导入账号冲突: "+e.username());
            if(existingProfiles.contains(uid)) {
                var current=jdbc.queryForMap("select department_id,post_id,phone,company from employee_profile where user_id=?",uid);
                if(!Objects.equals(((Number)current.get("department_id")).longValue(),deps.get(e.department()).id)
                    ||!Objects.equals(((Number)current.get("post_id")).longValue(),posts.get(e.post()).id)) throw new IllegalArgumentException("员工组织或岗位变化，请先执行调岗流程: "+e.username());
            } else newProfiles.add(new Object[]{uid,deps.get(e.department()).id,posts.get(e.post()).id,e.phone(),e.company()});
        }
        jdbc.batchUpdate("insert into employee_profile(user_id,department_id,post_id,phone,company) values (?,?,?,?,?)",newProfiles);
        int unresolvedManagers=0;
        for(var d:p.departments()) {
            var candidates=p.employees().stream().filter(e->e.name().equals(d.manager())&&e.department().equals(d.name())).toList();
            if(candidates.size()==1 && deps.get(d.name()).manager==null) deps.get(d.name()).manager=em.getReference(UserAccount.class,users.get(candidates.getFirst().username()));
            else if(candidates.size()!=1) unresolvedManagers++;
        }
        Map<String,BusinessSystem> systems=new HashMap<>();
        for(var s:em.createQuery("from BusinessSystem",BusinessSystem.class).getResultList()) systems.put(s.getCode(),s);
        Map<String,CompanyData.Module> modules=new HashMap<>();
        for(var m:em.createQuery("from CompanyData$Module",CompanyData.Module.class).getResultList()) modules.put(m.system.getCode()+"/"+m.name,m);
        Map<String,CompanyData.Function> functions=new HashMap<>();
        for(var f:em.createQuery("from CompanyData$Function",CompanyData.Function.class).getResultList()) functions.put(f.module.system.getCode()+"/"+f.module.name+"/"+f.name,f);
        Set<String> codes=new HashSet<>(jdbc.queryForList("select code from sys_permission",String.class));
        int createdPermissions=0;
        for(var f:p.features()) {
            String sc="app"+key(f.system());
            var system=systems.get(sc);
            if(system==null){system=new BusinessSystem(sc,f.system(),null);em.persist(system);systems.put(sc,system);}
            String mk=sc+"/"+f.module(); var module=modules.get(mk);
            if(module==null){module=new CompanyData.Module();module.system=system;module.name=f.module();em.persist(module);modules.put(mk,module);}
            String fk=mk+"/"+f.name();var function=functions.get(fk);
            if(function!=null) continue; // Existing grants and definitions are never overwritten by re-import.
            function=new CompanyData.Function();function.module=module;function.name=f.name();function.description=f.description();em.persist(function);functions.put(fk,function);
            Set<String> actions=new TreeSet<>();f.grants().values().forEach(actions::addAll);
            for(String action:actions) {
                String code=sc+":f"+key(f.module()+"/"+f.name())+":"+action;
                if(!codes.add(code)) throw new IllegalArgumentException("权限编码冲突，请核对已有目录");
                var permission=new Permission(code,f.name()+" · "+action,"/catalog/"+code,system);em.persist(permission);createdPermissions++;
                var detail=new CompanyData.Detail();detail.permission=permission;detail.function=function;detail.action=action;em.persist(detail);
                for(var grant:f.grants().entrySet()) if(grant.getValue().contains(action)) {
                    var t=new CompanyData.Template();t.name=grant.getKey();t.permission=permission;em.persist(t);
                }
            }
        }
        var batch=new CompanyData.Batch();batch.actorId=p.actor();batch.fingerprint=p.fingerprint();batch.createdAt=Instant.now();batch.employees=p.employees().size();batch.departments=p.departments().size();batch.functions=p.features().size();em.persist(batch);em.flush();
        return Map.of("reused",false,"batchId",batch.id,"createdUsers",newUsers.size(),"createdPermissions",createdPermissions,"unresolvedManagers",unresolvedManagers,"message","导入成功；新员工无密码、无角色，已有账号状态及授权保持不变");
    }
    private static void header(List<XlsxReader.Row> rows,int index,List<String> expected) {
        if(rows.size()<=index) throw new IllegalArgumentException("文件为空或表头缺失");
        for(int i=0;i<expected.size();i++) if(!rows.get(index).get(String.valueOf((char)('A'+i))).equals(expected.get(i))) throw new IllegalArgumentException("表头不匹配，期望 "+expected.get(i));
    }
    private static String key(String text) {return digest(text.getBytes(StandardCharsets.UTF_8)).substring(0,16);}
    private static String digest(byte[]... inputs) {
        try {var d=MessageDigest.getInstance("SHA-256");for(byte[] input:inputs){d.update(java.nio.ByteBuffer.allocate(4).putInt(input.length).array());d.update(input);}return HexFormat.of().formatHex(d.digest());}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
