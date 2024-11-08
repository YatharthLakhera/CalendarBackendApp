package com.calendar.app.models.api;

import com.calendar.app.controller.deserializer.CustomDateDeserializer;
import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.enums.TimeZone;
import com.calendar.app.models.helper.AudienceReq;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
public class EventSchedulingRequest {

    private String title;
    private String description;
    @JsonDeserialize(using = CustomDateDeserializer.class)
    private Date eventDate;
    private String startTime;
    private String endTime;
    private TimeZone timeZone;
    private List<AudienceReq> audienceReq;

    public EventSchedulingRequest(EventScheduler eventScheduler) {
        this.title = eventScheduler.getTitle();
        this.description = eventScheduler.getDescription();
        this.startTime = eventScheduler.getStartTime();
        this.endTime = eventScheduler.getEndTime();
        this.timeZone = eventScheduler.getTimeZone();
        this.audienceReq = new ArrayList<>();
        for (EventAudience eventAudience : eventScheduler.getAudiences()) {
            this.audienceReq.add(new AudienceReq(eventAudience));
        }
    }
}
