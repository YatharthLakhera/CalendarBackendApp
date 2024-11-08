package com.calendar.app.utils;

import com.calendar.app.exceptions.BadRequestException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DateUtility {

    private static final String YYYY_MM_DD = "yyyy-MM-dd";

    public static Date parseDate(String dateString) {
        SimpleDateFormat formatter = new SimpleDateFormat(YYYY_MM_DD);
        try {
            return formatter.parse(dateString);
        } catch (ParseException e) {
            throw new BadRequestException("Failed to parse date -> " + e.getMessage());
        }
    }
}
