package com.cdwy.permission.platform;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
@RestController
public class OidcPages {
    private static String escape(String value){return HtmlUtils.htmlEscape(value==null?"":value);}
    private static String page(String title,String body){return "<!doctype html><html lang='zh-CN'><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>"+title+"</title><link rel='stylesheet' href='/app.css'><main class='login'><p class='eyebrow'>统一权限管理平台</p><h1>"+title+"</h1>"+body+"</main></html>";}
    private static String csrf(HttpServletRequest request){var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());return token==null?"":"<input type='hidden' name='"+escape(token.getParameterName())+"' value='"+escape(token.getToken())+"'>";}
    @GetMapping(value="/login",produces="text/html;charset=UTF-8") public String login(HttpServletRequest request){return page("统一账号登录",(request.getParameter("error")!=null?"<p role='alert'>登录失败，请检查账号、密码、账号状态及初始密码是否已修改。</p>":"")+"<p>登录后返回发起请求的应用。</p><form method='post' action='/login'>"+csrf(request)+"<label>账号或手机号<input name='username' required maxlength='64' autocomplete='username'></label><label>密码<input type='password' name='password' required autocomplete='current-password'></label><button class='primary'>登录</button></form><a href='/ui/index.html'>返回管理门户</a>");}
    @GetMapping(value="/consent",produces="text/html;charset=UTF-8") public String consent(HttpServletRequest request){String client=request.getParameter("client_id"),state=request.getParameter("state");StringBuilder fields=new StringBuilder();for(String scope:String.valueOf(request.getParameter("scope")).split(" "))if(!scope.equals("openid")&&!scope.equals("null"))fields.append("<label class='check'><input type='checkbox' name='scope' value='").append(escape(scope)).append("'>读取基本身份信息（").append(escape(scope)).append("）</label>");return page("确认应用登录","<p>应用 <strong>"+escape(client)+"</strong> 请求读取你的身份信息。此同意不代表获得业务功能权限。</p><form method='post' action='/oauth2/authorize'>"+csrf(request)+"<input type='hidden' name='client_id' value='"+escape(client)+"'><input type='hidden' name='state' value='"+escape(state)+"'>"+fields+"<button class='primary'>同意并继续</button><a href='/ui/index.html'>取消并返回门户</a></form>");}
}
