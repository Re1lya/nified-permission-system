import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Offline controller contract inventory. Does not instantiate Spring or execute endpoints. */
public class ExportApi {
 static Map<String,Object> schema(Type type){
  if(type instanceof ParameterizedType p){if(p.getRawType()==List.class||p.getRawType()==Set.class||p.getRawType()==Collection.class)return Map.of("type","array","items",schema(p.getActualTypeArguments()[0]));if(p.getRawType().getTypeName().equals("org.springframework.http.ResponseEntity"))return schema(p.getActualTypeArguments()[0]);return Map.of("type","object");}
  if(!(type instanceof Class<?> c))return Map.of("type","object");
  if(c==void.class||c==Void.class)return Map.of();
  if(c==String.class||c.isEnum())return Map.of("type","string");
  if(c==boolean.class||c==Boolean.class)return Map.of("type","boolean");
  if(c.isPrimitive()||Number.class.isAssignableFrom(c))return Map.of("type","integer","format","int64");
  if(c==java.time.Instant.class)return Map.of("type","string","format","date-time");
  if(c==java.time.LocalDate.class)return Map.of("type","string","format","date");
  if(c.getName().startsWith("java."))return Map.of("type","object");
  Map<String,Object> properties=new LinkedHashMap<>();List<String> required=new ArrayList<>();
  // Integration Event is recursive: represent its batch entries through a named schema reference.
  if(c.isRecord()) for(var component:c.getRecordComponents()){
   String name=component.getName();Type t=component.getGenericType();
   properties.put(name,name.equals("records")?Map.of("type","array","items",Map.of("$ref","#/components/schemas/IntegrationEvent")):schema(t));
  } else for(var field:c.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()))properties.put(field.getName(),schema(field.getGenericType()));
  if(properties.isEmpty())return Map.of("type","object");
  for(var field:c.getDeclaredFields())for(var annotation:field.getAnnotations())if(Set.of("NotNull","NotBlank","NotEmpty").contains(annotation.annotationType().getSimpleName()))required.add(field.getName());
  Map<String,Object> result=new LinkedHashMap<>(Map.of("type","object","properties",properties));if(!required.isEmpty())result.put("required",required);return result;
 }
 static int successStatus(Method method){
  if(method.isAnnotationPresent(ResponseStatus.class))return method.getAnnotation(ResponseStatus.class).code().value();
  if(method.getGenericReturnType() instanceof ParameterizedType p&&p.getRawType().getTypeName().equals("org.springframework.http.ResponseEntity")){
   if(p.getActualTypeArguments()[0]==Void.class)return 204;
   if(method.getName().equals("create")&&Set.of("UserController","RoleController","PermissionController","BusinessSystemController").contains(method.getDeclaringClass().getSimpleName()))return 201;
   throw new IllegalStateException("Declare verified response status for "+method);
  }return 200;
 }
 public static void main(String[] args)throws Exception{
  List<Object> routes=new ArrayList<>();Path root=Path.of(args[0]);
  try(var files=Files.walk(root.resolve("com/cdwy/permission"))){for(Path path:files.filter(p->p.toString().endsWith(".class")).sorted().toList()){
   Class<?> c=Class.forName(root.relativize(path).toString().replace('/','.').replace(".class",""),false,ExportApi.class.getClassLoader());if(!c.isAnnotationPresent(RestController.class))continue;
   var mapping=c.getAnnotation(RequestMapping.class);String prefix=mapping==null||mapping.value().length==0?"":mapping.value()[0];
   for(Method method:c.getDeclaredMethods()){
    String verb=null;String[] paths={};
    if(method.isAnnotationPresent(GetMapping.class)){verb="GET";paths=method.getAnnotation(GetMapping.class).value();}
    if(method.isAnnotationPresent(PostMapping.class)){verb="POST";paths=method.getAnnotation(PostMapping.class).value();}
    if(method.isAnnotationPresent(PutMapping.class)){verb="PUT";paths=method.getAnnotation(PutMapping.class).value();}
    if(method.isAnnotationPresent(DeleteMapping.class)){verb="DELETE";paths=method.getAnnotation(DeleteMapping.class).value();}
    if(verb==null)continue;if(paths.length==0)paths=new String[]{""};
    for(String suffix:paths){String route=prefix+suffix;if(!route.startsWith("/api/"))continue;
     Map<String,Object> item=new LinkedHashMap<>();item.put("path",route);item.put("method",verb);item.put("handler",c.getSimpleName()+"."+method.getName());item.put("response",schema(method.getGenericReturnType()));item.put("successStatus",successStatus(method));List<Object> parameters=new ArrayList<>();
     for(Parameter param:method.getParameters()){
      if(param.isAnnotationPresent(RequestBody.class)){item.put("request",schema(param.getParameterizedType()));continue;}
      String where=null,name=param.getName();boolean required=false;
      var pv=param.getAnnotation(PathVariable.class);var qp=param.getAnnotation(RequestParam.class);var hp=param.getAnnotation(RequestHeader.class);
      if(pv!=null){where="path";name=pv.value().isEmpty()?name:pv.value();required=true;}
      if(qp!=null){where="query";name=qp.value().isEmpty()?name:qp.value();required=qp.required()&&qp.defaultValue().equals(ValueConstants.DEFAULT_NONE);}
      if(hp!=null){where="header";name=hp.value().isEmpty()?name:hp.value();required=hp.required();}
      if(where!=null&&!name.equalsIgnoreCase("Authorization"))parameters.add(Map.of("in",where,"name",name,"required",required,"schema",schema(param.getParameterizedType())));
     }
     item.put("parameters",parameters);routes.add(item);
    }
   }
  }}
  new JsonMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),routes);
 }
}
