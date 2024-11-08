package com.calendar.app.models.api;

import com.calendar.app.controller.serializer.CustomDateSerializer;
import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.enums.TimeZone;
import com.calendar.app.models.helper.AudienceRes;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
public class EventSchedulingResponse {

    private UUID eventId;
    private String title;
    private String description;
    @JsonSerialize(using = CustomDateSerializer.class)
    private Date eventDate;
    private String startTime;
    private String endTime;
    private TimeZone timeZone;
    private List<AudienceRes> audienceRes;

    public EventSchedulingResponse(EventScheduler eventScheduler) {
        this.eventId = eventScheduler.getEventId();
        this.title = eventScheduler.getTitle();
        this.description = eventScheduler.getDescription();
        this.eventDate = eventScheduler.getEventDate();
        this.startTime = eventScheduler.getStartTime();
        this.endTime = eventScheduler.getEndTime();
        this.timeZone = eventScheduler.getTimeZone();
        this.audienceRes = new ArrayList<>();
        for (EventAudience eventAudience : eventScheduler.getAudiences()) {
            this.audienceRes.add(new AudienceRes(eventAudience));
        }
    }
}
