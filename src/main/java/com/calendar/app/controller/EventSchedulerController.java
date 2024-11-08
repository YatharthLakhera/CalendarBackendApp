package com.calendar.app.controller;

import com.calendar.app.enums.EventStatus;
import com.calendar.app.models.api.EventSchedulingRequest;
import com.calendar.app.models.api.EventSchedulingResponse;
import com.calendar.app.services.EventSchedulerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class EventSchedulerController {

    @Autowired private EventSchedulerService eventSchedulerService;

    @PostMapping("/event")
    public EventSchedulingResponse addEventFor(@RequestHeader("userId") UUID userId,
                                               @RequestBody EventSchedulingRequest request) {
        return eventSchedulerService.addEventFor(userId, request);
    }

    @PutMapping("/event/{eventId}/update")
    public EventSchedulingResponse rescheduleEventFor(@RequestHeader("userId") UUID userId,
                                                      @PathVariable("eventId") UUID eventId,
                                                      @RequestBody EventSchedulingRequest request) {
        return eventSchedulerService.rescheduleEventFor(userId, eventId, request);
    }

    @GetMapping("/event/{eventId}")
    public EventSchedulingResponse getEventFor(@PathVariable("eventId") UUID eventId) {
        return eventSchedulerService.getEventResponseBy(eventId);
    }

    @PutMapping("/event/{eventId}/status/{status}")
    public void updateEventStatus(@RequestHeader("userId") UUID userId, @PathVariable("eventId") UUID eventId,
                                  @PathVariable("status") EventStatus status) {
        eventSchedulerService.updateEventStatus(userId, eventId, status);
    }

    @DeleteMapping("/event/{eventId}")
    public void deleteEventFor(@RequestHeader("userId") UUID userId, @PathVariable("eventId") UUID eventId) {
        eventSchedulerService.deleteEventFor(userId, eventId);
    }
}
