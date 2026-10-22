package com.cdwy.permission.platform;

/** Protocol capability boundary. Other protocols remain reserved, as required by phase one. */
public interface ApplicationProtocol {
    String code();
    boolean singleSignOn();
    boolean requiresCallback();
    static ApplicationProtocol of(String value){
        return switch(value){case "OIDC"->new Capability("OIDC",true,true);case "LEGACY"->new Capability("LEGACY",false,false);default->throw new IllegalArgumentException("一期仅实现 OIDC；其他协议须通过适配器扩展");};
    }
    record Capability(String code,boolean singleSignOn,boolean requiresCallback) implements ApplicationProtocol{}
}
