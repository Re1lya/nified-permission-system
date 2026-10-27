package com.cdwy.permission;
import com.cdwy.permission.company.CompanyImportService;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:actual-workbooks;DB_CLOSE_DELAY=-1")
@EnabledIfEnvironmentVariable(named="COMPANY_WORKBOOK_DIR",matches=".+")
class ActualWorkbookImportTests {
 @Autowired CompanyImportService imports; @Autowired JdbcTemplate db;
 @Test void importsActualThreeWorkbooksWithoutAutomaticGrants() throws Exception {
  Path root=Path.of(System.getenv("COMPANY_WORKBOOK_DIR"));
  var preview=imports.preview(1L,Files.readAllBytes(root.resolve("市政公司部门信息表.xlsx")),Files.readAllBytes(root.resolve("市政公司员工信息表.xlsx")),Files.readAllBytes(root.resolve("市政公司应用软件功能权限清单(1).xlsx")));
  assertEquals(0,preview.get("errorCount"),preview.get("errors").toString());
  var result=imports.commit(1L,(String)preview.get("previewId"),true);
  assertEquals(10000,db.queryForObject("select count(*) from employee_profile",Integer.class));
  assertEquals(111,db.queryForObject("select count(*) from sys_department",Integer.class));
  assertEquals(359,db.queryForObject("select count(*) from sys_function",Integer.class));
  assertEquals(0,db.queryForObject("select count(*) from sys_user_role",Integer.class));
  assertEquals(0,db.queryForObject("select count(*) from sys_user where administrator=true",Integer.class));
  assertEquals(10000,db.queryForObject("select count(*) from sys_account a join account_policy p on p.user_id=a.user_id where a.password_hash is null and p.must_change_password=true and p.initial_password_issued=false",Integer.class));
  assertEquals(true,imports.commit(1L,(String)preview.get("previewId"),true).get("reused"));
  Files.createDirectories(Path.of("output"));
  Files.writeString(Path.of("output/actual-import-result.txt"),"counts="+preview.get("counts")+"\nwarnings="+preview.get("warningCount")+"\nresult="+result+"\nrolesAssigned=0\nrepeat=reused\n");
 }
}
