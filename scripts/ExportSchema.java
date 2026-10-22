import java.nio.file.*;
import java.util.*;
import org.hibernate.boot.*;
import org.hibernate.boot.registry.*;
import org.hibernate.tool.schema.spi.*;

public class ExportSchema {
 public static void main(String[] args)throws Exception{
  Files.deleteIfExists(Path.of(args[1]));
  Map<String,Object> settings=new HashMap<>();
  settings.put("hibernate.dialect","org.hibernate.dialect.MySQLDialect");
  settings.put("hibernate.boot.allow_jdbc_metadata_access",false);
  settings.put("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl");
  settings.put("jakarta.persistence.schema-generation.database.action","none");
  settings.put("jakarta.persistence.schema-generation.scripts.action","create");
  settings.put("jakarta.persistence.schema-generation.scripts.create-target",args[1]);
  settings.put("hibernate.hbm2ddl.delimiter",";");settings.put("hibernate.format_sql",true);
  var registry=new StandardServiceRegistryBuilder().applySettings(settings).build();
  try{
   var sources=new MetadataSources(registry);Path root=Path.of(args[0]);
   try(var files=Files.walk(root.resolve("com/cdwy/permission"))){for(Path path:files.filter(p->p.toString().endsWith(".class")).toList()){
    String name=root.relativize(path).toString().replace('/', '.').replace(".class","");
    Class<?> cls=Class.forName(name,false,ExportSchema.class.getClassLoader());
    if(cls.isAnnotationPresent(jakarta.persistence.Entity.class))sources.addAnnotatedClass(cls);
   }}
   SchemaManagementToolCoordinator.process(sources.buildMetadata(),registry,settings,DelayedDropRegistryNotAvailableImpl.INSTANCE);
  }finally{StandardServiceRegistryBuilder.destroy(registry);}
 }
}
