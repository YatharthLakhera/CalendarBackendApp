package com.calendar.app.db;

import com.calendar.app.controller.deserializer.AvailabilityPatternValidator;
import com.calendar.app.enums.TimeZone;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UserDefaultAvailability {

    private TimeZone timeZone;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String monday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String tuesday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String wednesday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String thursday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String friday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String saturday;
    @JsonDeserialize(using = AvailabilityPatternValidator.class)
    private String sunday;

    @JsonIgnore
    public List<String> getWeeksAvailability() {
        List<String> weeksAvailability = new ArrayList<>();
        weeksAvailability.add(this.monday);
        weeksAvailability.add(this.tuesday);
        weeksAvailability.add(this.wednesday);
        weeksAvailability.add(this.thursday);
        weeksAvailability.add(this.friday);
        weeksAvailability.add(this.saturday);
        weeksAvailability.add(this.sunday);
        return weeksAvailability;
    }
}
