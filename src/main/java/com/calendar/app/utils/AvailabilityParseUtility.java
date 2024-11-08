package com.calendar.app.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AvailabilityParseUtility {

    private static final String TIMING_BUCKET_DELIMITED = ";";
    private static final String TIMING_DELIMITED = ":";

    public static List<String[]> parse(String availabilityString) {
        if (StringUtils.isBlank(availabilityString)) return new ArrayList<>();
        String[] availabilities = availabilityString.split(TIMING_BUCKET_DELIMITED);
        List<String[]> parsedAvailability = new ArrayList<>();
        for (String availability : availabilities) {
            String[] timings = availability.split(TIMING_DELIMITED);
            parsedAvailability.add(timings);
        }
        return parsedAvailability;
    }
}
