package com.cdwy.permission.controller;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Versioned representation: preserve legacy classroom clients while the current console uses the DOC-01 contract. */
@RestControllerAdvice
public class ApiEnvelopeAdvice implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return !org.springframework.http.converter.StringHttpMessageConverter.class.isAssignableFrom(converter);}
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
        if(!request.getURI().getPath().startsWith("/api/")||!Objects.toString(request.getHeaders().getFirst("Accept"),"").contains("application/vnd.cdwy.v2+json"))return body;
        response.getHeaders().add("Vary","Accept");
        int status=response instanceof ServletServerHttpResponse servlet?servlet.getServletResponse().getStatus():200;
        if(status==204)return body;
        String message=body instanceof Map<?,?> map&&map.get("message")!=null?map.get("message").toString():status<400?"成功":"请求失败";
        var result=new LinkedHashMap<String,Object>();result.put("code",status<400?"SUCCESS":switch(status){case 400->"INVALID_ARGUMENT";case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 409->"CONFLICT";default->"SERVER_ERROR";});result.put("message",message);result.put("data",status<400?body:null);return result;
    }
}
