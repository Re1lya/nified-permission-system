package com.cdwy.permission.platform;

/** Protocol capability boundary. Other protocols remain reserved, as required by phase one. */
/**
 * 协议能力抽象：用接口描述是否免登、是否需要回调，调用方依赖能力而非散落的协议字符串判断。
 * 当前仅提供 OIDC 和 LEGACY 能力描述；这是扩展边界，不代表已经实现 SAML/CAS 等协议适配器。
 */
public interface ApplicationProtocol {
    String code();
    boolean singleSignOn();
    boolean requiresCallback();
    static ApplicationProtocol of(String value){
        return switch(value){case "OIDC"->new Capability("OIDC",true,true);case "LEGACY"->new Capability("LEGACY",false,false);default->throw new IllegalArgumentException("一期仅实现 OIDC；其他协议须通过适配器扩展");};
    }
    record Capability(String code,boolean singleSignOn,boolean requiresCallback) implements ApplicationProtocol{}
}
