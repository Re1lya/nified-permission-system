package com.cdwy.permission.platform;

import java.util.*;

/** Named platform permissions derive from the explicit administrator boundary, never from job titles. */
/**
 * 提供前端可展示操作的能力清单，来源是超级管理员/部门管理员的显式身份。
 * 能力清单不是后端授权的替代品，真正的目标范围仍由 requireUser、requireDepartment 等方法校验。
 */
public final class PlatformCapabilities {
    private PlatformCapabilities(){}
    public static Set<String> forUser(PlatformStore s,long actor){
        var result=new LinkedHashSet<String>(List.of("platform:self:read","platform:self:write"));
        if(s.superAdmin(actor)||s.departmentAdmin(actor))result.addAll(List.of("platform:users:read","platform:users:write","platform:roles:read","platform:roles:write","platform:audit:read","platform:reviews:write","platform:organization:read"));
        if(s.superAdmin(actor))result.addAll(List.of("platform:catalog:write","platform:integration:write","platform:administrators:write"));
        return result;
    }
}
