package com.calendar.app.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StringUtility {

    public static List<String> getEmailListFor(String emailsStr) {
        if (StringUtils.isBlank(emailsStr)) return new ArrayList<>();
        String[] emailList = emailsStr.split(",");
        List<String> emails = new ArrayList<>();
        for (String email : emailList) {
            emails.add(StringUtils.trim(email));
        }
        return emails;
    }
}
