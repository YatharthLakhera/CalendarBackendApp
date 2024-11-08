package com.calendar.app.controller.deserializer;

import com.calendar.app.exceptions.BadRequestException;
import com.calendar.app.utils.AvailabilityParseUtility;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class AvailabilityPatternValidator extends JsonDeserializer<String> {


    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String availabilityStr = p.getText();
        if (StringUtils.isBlank(availabilityStr)) return null;
        try {
            for (String[] availability : AvailabilityParseUtility.parse(availabilityStr)) {
                int startTime = Integer.parseInt(availability[0]);
                int endTime = Integer.parseInt(availability[1]);
                if (startTime >= endTime) {
                    throw new BadRequestException("Start time should be less than end time");
                }
                if (startTime % 5 != 0 || endTime % 5 != 0) {
                    throw new BadRequestException("Time should be a multiple of 5");
                }
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException("Time should be in 24 hr format i.e. 1000");
        } catch (Exception e) {
            throw new BadRequestException("Time availability format is not valid");
        }
        return availabilityStr;
    }
}
