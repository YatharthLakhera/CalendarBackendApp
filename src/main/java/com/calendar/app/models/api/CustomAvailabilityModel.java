package com.calendar.app.models.api;

import com.calendar.app.controller.deserializer.AvailabilityPatternValidator;
import com.calendar.app.db.entity.UserCustomAvailability;
import com.calendar.app.enums.TimeZone;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
public class CustomAvailabilityModel {

    private Date date;
    private TimeZone timeZone;
    private boolean isAvailable;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String availability;

    public CustomAvailabilityModel(UserCustomAvailability customAvailability) {
        this.date = customAvailability.getAvailabilityDate();
        this.timeZone = customAvailability.getTimeZone();
        this.isAvailable = customAvailability.isAvailable();
        this.availability = customAvailability.getAvailability();
    }
}
