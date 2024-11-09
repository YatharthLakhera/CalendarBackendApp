package com.calendar.app.db;

import com.calendar.app.controller.deserializer.AvailabilityPatternValidator;
import com.calendar.app.enums.TimeZone;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

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
        weeksAvailability.add(StringUtils.isBlank(this.monday) ? "" : this.monday);
        weeksAvailability.add(StringUtils.isBlank(this.tuesday) ? "" : this.tuesday);
        weeksAvailability.add(StringUtils.isBlank(this.wednesday) ? "" : this.wednesday);
        weeksAvailability.add(StringUtils.isBlank(this.thursday) ? "" : this.thursday);
        weeksAvailability.add(StringUtils.isBlank(this.friday) ? "" : this.friday);
        weeksAvailability.add(StringUtils.isBlank(this.saturday) ? "" : this.saturday);
        weeksAvailability.add(StringUtils.isBlank(this.sunday) ? "" : this.sunday);
        return weeksAvailability;
    }
}
