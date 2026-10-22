package com.cdwy.permission.platform;

import java.util.*;

/** Named platform permissions derive from the explicit administrator boundary, never from job titles. */
public final class PlatformCapabilities {
    private PlatformCapabilities(){}
    public static Set<String> forUser(PlatformStore s,long actor){
        var result=new LinkedHashSet<String>(List.of("platform:self:read","platform:self:write"));
        if(s.superAdmin(actor)||s.departmentAdmin(actor))result.addAll(List.of("platform:users:read","platform:users:write","platform:roles:read","platform:roles:write","platform:audit:read","platform:reviews:write","platform:organization:read"));
        if(s.superAdmin(actor))result.addAll(List.of("platform:catalog:write","platform:integration:write","platform:administrators:write"));
        return result;
    }
}
