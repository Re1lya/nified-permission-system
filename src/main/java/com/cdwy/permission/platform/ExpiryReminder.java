package com.cdwy.permission.platform;

import java.time.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryReminder {
    private final PlatformStore s;
    public ExpiryReminder(PlatformStore s){this.s=s;}
    @Scheduled(cron="0 0 8 * * *",zone="Asia/Shanghai") @Transactional public void remind(){
        var now=Instant.now();var accounts=s.rows("select p.user_id,p.expires_at,e.department_id from account_policy p join employee_profile e on e.user_id=p.user_id where p.deleted=false and p.person_type in('EXTERNAL','TEMPORARY') and p.expires_at>? and p.expires_at<=? order by p.user_id",now,now.plus(Duration.ofDays(14)));
        for(var account:accounts){
            long user=((Number)account.get("user_id")).longValue();
            s.db().queryForObject("select user_id from account_policy where user_id=? for update",Long.class,user);
            if(s.count("select count(*) from platform_notification where user_id=? and kind='EXPIRY_REMINDER' and created_at>?",user,now.minus(Duration.ofDays(7)))==0)
                s.db().update("insert into platform_notification(department_id,user_id,kind,message,created_at) values(?,?,'EXPIRY_REMINDER',?,?)",account.get("department_id"),user,"外部或临时账号将在14天内到期，请核对续期需求",now);
        }
    }
}
