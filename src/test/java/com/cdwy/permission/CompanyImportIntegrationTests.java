package com.cdwy.permission;

import com.cdwy.permission.company.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:company-tests;DB_CLOSE_DELAY=-1")
class CompanyImportIntegrationTests {
 @Autowired CompanyImportService service;
 @Autowired JdbcTemplate db;
 @Autowired com.cdwy.permission.service.RbacService rbac;
 static byte[] xlsx(String[][] rows) throws Exception {
  var out=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(out)) {
   zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
   var xml=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
   for(int r=0;r<rows.length;r++) {xml.append("<row r=\"").append(r+1).append("\">");for(int c=0;c<rows[r].length;c++)xml.append("<c r=\"").append((char)('A'+c)).append(r+1).append("\" t=\"inlineStr\"><is><t>").append(rows[r][c]).append("</t></is></c>");xml.append("</row>");}
   zip.write(xml.append("</sheetData></worksheet>").toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
  }return out.toByteArray();
 }
 byte[] deps(String s) throws Exception{return xlsx(new String[][]{{"部门名称","部门经理","上级部门"},{"总公司","",""},{"部门"+s,"测试员工","总公司"}});}
 byte[] employees(String s) throws Exception{return xlsx(new String[][]{{"姓名","用户名","分公司","部门","岗位","电话号码"},{"测试员工","SG"+s,"总公司","部门"+s,"岗位"+s,"13900000000"}});}
 byte[] permissions(String s) throws Exception{return xlsx(new String[][]{{"清单"},{"软件系统","功能模块","功能点","功能说明","系统管理员","公司领导","部门经理","项目经理","普通员工"},{"系统"+s,"模块","功能","说明","全部","查看/审批","查看/编辑","无","查看本人"}});}
 @Test void atomicImportIsIdempotentAndNeverGrantsEmployeesRolesOrAdmin() throws Exception {
  String s="a"+System.nanoTime();var p=service.preview(1L,deps(s),employees(s),permissions(s));
  assertEquals(0,p.get("errorCount"));assertEquals(0,db.queryForObject("select count(*) from hr_identity where kind='EMPLOYEE' and external_id=?",Integer.class,"SG"+s));
  String id=(String)p.get("previewId");assertThrows(IllegalArgumentException.class,()->service.commit(2L,id,true));
  assertThrows(IllegalArgumentException.class,()->service.commit(1L,id,false));
  service.commit(1L,id,true);assertEquals(true,service.commit(1L,id,true).get("reused"));
  assertEquals(1,db.queryForObject("select count(*) from hr_identity h join sys_user u on u.id=h.local_id join sys_account a on a.user_id=u.id join account_policy p on p.user_id=u.id where h.kind='EMPLOYEE' and h.external_id=? and u.administrator=false and a.password_hash is not null and p.must_change_password=true and p.initial_password_issued=false",Integer.class,"SG"+s));
  assertEquals(0,db.queryForObject("select count(*) from sys_user_role ur join hr_identity h on h.local_id=ur.user_id where h.kind='EMPLOYEE' and h.external_id=?",Integer.class,"SG"+s));
  assertTrue(db.queryForObject("select count(*) from permission_detail where action='view_self'",Integer.class)>0);
 }
 @Test void malformedRelationshipBlocksEntireImport() throws Exception {
  String s="b"+System.nanoTime();var p=service.preview(1L,deps(s),employees(s+"missing"),permissions(s));
  assertTrue((int)p.get("errorCount")>0);assertThrows(IllegalArgumentException.class,()->service.commit(1L,(String)p.get("previewId"),true));
  assertEquals(0,db.queryForObject("select count(*) from sys_department where name=?",Integer.class,"部门"+s));
 }
 @Test void collisionRollsBackPreviouslyInsertedDepartmentsAndRoles() throws Exception {
  String s="c"+System.nanoTime();long existing=rbac.createUser(new com.cdwy.permission.dto.CreateUserRequest("manual"+s,"已有手工账号")).id();
  db.update("insert into hr_identity(kind,external_id,local_id) values('EMPLOYEE',?,?)","SG"+s,existing);
  var p=service.preview(1L,deps(s),employees(s),permissions(s));
  assertThrows(IllegalArgumentException.class,()->service.commit(1L,(String)p.get("previewId"),true));
  assertEquals(0,db.queryForObject("select count(*) from sys_department where name=?",Integer.class,"部门"+s));
 }
 @Test void rejectsCyclesAndWrongHeaders() throws Exception {
  String s="d"+System.nanoTime();var cyclic=xlsx(new String[][]{{"部门名称","部门经理","上级部门"},{"总公司","","部门"+s},{"部门"+s,"","总公司"}});
  var p=service.preview(1L,cyclic,employees(s),permissions(s));assertTrue((int)p.get("errorCount")>0);
  assertThrows(IllegalArgumentException.class,()->service.preview(1L,employees(s),deps(s),permissions(s)));
 }
 @Test void rejectsExternalEntities() throws Exception {
  var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));z.write("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]><x>&e;</x>".getBytes(StandardCharsets.UTF_8));}
  assertThrows(IllegalArgumentException.class,()->new XlsxReader().read(out.toByteArray()));
 }
}
