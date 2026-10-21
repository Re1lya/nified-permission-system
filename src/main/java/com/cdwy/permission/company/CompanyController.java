package com.cdwy.permission.company;

import com.cdwy.permission.service.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** All /api/company routes inherit the default administrator guard. */
@RestController @RequestMapping("/api/company")
public class CompanyController {
    private final CompanyImportService imports; private final JwtTokenService tokens; private final JdbcTemplate db;
    public CompanyController(CompanyImportService imports,JwtTokenService tokens,JdbcTemplate db){this.imports=imports;this.tokens=tokens;this.db=db;}
    @PostMapping("/imports/preview")
    public Map<String,Object> preview(@RequestHeader("Authorization") String auth,
        @RequestParam MultipartFile departments,@RequestParam MultipartFile employees,@RequestParam MultipartFile permissions) throws java.io.IOException {
        for(var file:List.of(departments,employees,permissions)) if(file.isEmpty()||file.getSize()>10_000_000) throw new IllegalArgumentException("每份 XLSX 必须非空且小于 10 MB");
        return imports.preview(tokens.requireUserId(auth),departments.getBytes(),employees.getBytes(),permissions.getBytes());
    }
    public record Confirmation(@NotBlank String previewId,boolean acknowledgeWarnings) {}
    @PostMapping("/imports/commit")
    public Map<String,Object> commit(@RequestHeader("Authorization") String auth,@Valid @RequestBody Confirmation body){return imports.commit(tokens.requireUserId(auth),body.previewId(),body.acknowledgeWarnings());}
    @GetMapping("/imports") public List<Map<String,Object>> batches(){return rows("select * from company_import_batch order by id desc limit 50");}
    @GetMapping("/departments") public List<Map<String,Object>> departments(){return rows("select d.id,d.name,d.parent_id,d.manager_name,u.username manager_username from sys_department d left join sys_user u on d.manager_id=u.id order by d.id");}
    @GetMapping("/posts") public List<Map<String,Object>> posts(){return rows("select p.id,p.name,p.role_id,r.enabled from sys_post p join sys_role r on r.id=p.role_id order by p.id");}
    @GetMapping("/employees") public Map<String,Object> employees(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Long departmentId){
        if(page<0||page>100000||size<1||size>100||q.length()>128)throw new IllegalArgumentException("分页或搜索参数不合法");
        String where=" where (lower(u.username) like ? escape '!' or lower(u.display_name) like ? escape '!')";
        var args=new ArrayList<Object>();String term="%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(term);args.add(term);
        if(departmentId!=null){where+=" and e.department_id=?";args.add(departmentId);}
        String from=" from sys_user u left join employee_profile e on e.user_id=u.id left join sys_department d on d.id=e.department_id left join sys_post p on p.id=e.post_id";
        Long count=db.queryForObject("select count(*)"+from+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return Map.of("total",count,"page",page,"size",size,"items",rows("select u.id,u.username,u.display_name,u.enabled,u.administrator,e.company,e.phone,d.name department,p.name post"+from+where+" order by u.id limit ? offset ?",args.toArray()));
    }
    @GetMapping("/catalog") public Map<String,Object> catalog(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String q){
        if(page<0||page>100000||size<1||size>100||q.length()>128)throw new IllegalArgumentException("分页或搜索参数不合法");
        String like="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String from=" from sys_function f join sys_module m on m.id=f.module_id join biz_system s on s.id=m.system_id where f.name like ? escape '!' or m.name like ? escape '!' or s.name like ? escape '!'";
        var items=rows("select f.id,f.name,f.description,m.name module,s.name system"+from+" order by f.id limit ? offset ?",like,like,like,size,(long)page*size);
        for(var item:items) item.put("permissions",rows("select p.id,p.code,p.name,d.action from permission_detail d join sys_permission p on p.id=d.permission_id where d.function_id=?",item.get("id")));
        return Map.of("items",items,"total",db.queryForObject("select count(*)"+from,Long.class,like,like,like),"page",page,"size",size);
    }
    @GetMapping("/templates") public List<Map<String,Object>> templates(){return rows("select name,count(*) permission_count from permission_template group by name order by name");}
    public record TemplateRequest(@NotBlank String name) {}
    @PostMapping("/posts/{id}/template") @Transactional
    public Map<String,Object> template(@PathVariable Long id,@Valid @RequestBody TemplateRequest request){
        var roles=rows("select role_id from sys_post where id=?",id);
        if(roles.isEmpty())throw new IllegalArgumentException("岗位不存在");
        var permissions=rows("select permission_id from permission_template where name=?",request.name());
        if(permissions.isEmpty())throw new IllegalArgumentException("权限模板不存在或没有已确认权限");
        Long role=((Number)roles.getFirst().get("role_id")).longValue();
        // Serialize grant modification on the role row, including repeated clicks.
        db.queryForObject("select id from sys_role where id=? for update",Long.class,role);
        int created=0;
        for(var p:permissions) created+=db.update("insert into sys_role_permission(role_id,permission_id) select ?,? where not exists (select 1 from sys_role_permission where role_id=? and permission_id=?)",role,p.get("permission_id"),role,p.get("permission_id"));
        if(created>0)db.update("update account_policy set version=version+1 where user_id in(select user_id from sys_user_role where role_id=?)",role);
        return Map.of("added",created,"message","模板权限已追加到岗位角色；未分配员工角色，也未提升平台管理员身份");
    }
    private List<Map<String,Object>> rows(String sql,Object... args){
        return db.query(sql,(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));return row;},args);
    }
}
