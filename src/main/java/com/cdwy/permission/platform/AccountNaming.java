package com.cdwy.permission.platform;

import net.sourceforge.pinyin4j.PinyinHelper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AccountNaming {
    private final PlatformStore s;
    public AccountNaming(PlatformStore s){this.s=s;}
    public record Name(String username,boolean manualReview){}
    public Name generate(String name,String phone){
        if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("姓名不能为空且不超过128字符");
        StringBuilder base=new StringBuilder();boolean review=false;
        for(char c:name.toCharArray()){
            String[] values=PinyinHelper.toHanyuPinyinStringArray(c);
            if(values!=null&&values.length>0){base.append(values[0].charAt(0));if(Arrays.stream(values).map(v->v.charAt(0)).distinct().count()>1)review=true;}
            else if(c<128&&Character.isLetterOrDigit(c))base.append(Character.toLowerCase(c));
            else if(!Character.isWhitespace(c))throw new IllegalArgumentException("姓名含无法自动转写的字符，需要人工确认账号名");
        }
        if(base.isEmpty()||base.length()>48)throw new IllegalArgumentException("姓名无法生成有效账号名");
        String candidate=base.toString();if(!exists(candidate))return new Name(candidate,review);
        String digits=phone==null?"":phone.replaceAll("[^0-9]","");
        if(digits.length()<4)throw new IllegalArgumentException("账号重名且手机号不足四位，需要人工处理");
        candidate+=digits.substring(digits.length()-4);if(!exists(candidate))return new Name(candidate,review);
        for(int i=2;i<100000;i++)if(!exists(candidate+i))return new Name(candidate+i,true);
        throw new IllegalArgumentException("账号冲突过多，需要人工处理");
    }
    private boolean exists(String name){return s.count("select count(*) from sys_account where username=?",name)>0;}
}
