package com.calendar.app.models.helper;

import com.calendar.app.controller.serializer.CustomDateSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;

@Data
@NoArgsConstructor
public class AvailabilityBucket {

    @JsonSerialize(using = CustomDateSerializer.class)
    private Date date;
    private String startTime;
    private String endTime;
    @JsonIgnore
    private int startTimeValue;
    @JsonIgnore
    private int endTimeValue;

    public AvailabilityBucket(Date date, String startTime, String endTime) {
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.startTimeValue = Integer.parseInt(startTime);
        this.endTimeValue = Integer.parseInt(endTime);
    }

    public AvailabilityBucket(Date date, int startTimeValue, int endTimeValue) {
        this.date = date;
        this.startTime = StringUtils.leftPad(String.valueOf(startTimeValue), 4, '0');
        this.endTime = StringUtils.leftPad(String.valueOf(endTimeValue), 4, '0');;
        this.startTimeValue = startTimeValue;
        this.endTimeValue = endTimeValue;
    }
}
