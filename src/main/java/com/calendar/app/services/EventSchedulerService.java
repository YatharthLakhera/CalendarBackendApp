package com.calendar.app.services;

import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.db.entity.UserDetails;
import com.calendar.app.db.repository.EventAudienceRepository;
import com.calendar.app.db.repository.EventSchedulerRepository;
import com.calendar.app.enums.EventStatus;
import com.calendar.app.enums.UserEventRole;
import com.calendar.app.exceptions.ActionNotAllowed;
import com.calendar.app.exceptions.EventNotFound;
import com.calendar.app.models.api.EventSchedulingRequest;
import com.calendar.app.models.api.EventSchedulingResponse;
import com.calendar.app.models.helper.AudienceReq;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class EventSchedulerService {

    @Autowired private UserService userService;
    @Autowired private EventAudienceRepository eventAudienceRepository;
    @Autowired private EventSchedulerRepository eventSchedulerRepository;

    public EventSchedulingResponse addEventFor(UUID organiserId, EventSchedulingRequest request) {
        UserDetails organiser = userService.getUserFor(organiserId);
        log.info("organiser : {}", organiser);
        EventScheduler eventScheduler = new EventScheduler(request);
        // Adding organiser to audience list and auto-accepting invite
        eventScheduler.addAudience(organiser, UserEventRole.ORGANISER, EventStatus.ACCEPTED);
        for (AudienceReq audienceReq : request.getAudienceReq()) {
            UserDetails userDetails = userService.getUserFor(audienceReq.getEmail());
            eventScheduler.addAudience(userDetails, audienceReq.getRole(), EventStatus.PENDING);
            log.info("eventScheduler : {}", eventScheduler);
        }
        eventScheduler = eventSchedulerRepository.save(eventScheduler);
        log.info("addEvent -> {}", eventScheduler);
        return new EventSchedulingResponse(eventScheduler);
    }

    @Transactional
    public EventSchedulingResponse rescheduleEventFor(UUID userId, UUID eventId, EventSchedulingRequest request) {
        UserDetails organiser = userService.getUserFor(userId);
        EventScheduler eventScheduler = getEventBy(eventId);
        if (!eventScheduler.isOrganisedBy(organiser)) {
            throw new ActionNotAllowed();
        }
        // Updating the details of event
        eventScheduler.update(request);
        // Removing all the existing audience
        eventScheduler.removeAllAudiences();
        log.info("removeAllAudiences for eventScheduler: {}", eventScheduler);
        // Adding new audience
        eventScheduler.addAudience(organiser, UserEventRole.ORGANISER, EventStatus.ACCEPTED);
        for (AudienceReq audienceReq : request.getAudienceReq()) {
            UserDetails userDetails = userService.getUserFor(audienceReq.getEmail());
            eventScheduler.addAudience(userDetails, audienceReq.getRole(), EventStatus.PENDING);
            log.info("eventScheduler : {}", eventScheduler);
        }
        eventScheduler = eventSchedulerRepository.save(eventScheduler);
        log.info("rescheduleEvent -> {}", eventScheduler);
        return new EventSchedulingResponse(eventScheduler);
    }

    public void deleteEventFor(UUID userId, UUID eventId) {
        UserDetails userDetails = userService.getUserFor(userId);
        EventScheduler event = getEventBy(eventId);
        if (!event.isOrganisedBy(userDetails)) {
            throw new ActionNotAllowed();
        }
        eventSchedulerRepository.delete(event);
        log.info("deleteEvent : {}", event);
    }

    public EventSchedulingResponse getEventResponseBy(UUID eventId) {
        return new EventSchedulingResponse(getEventBy(eventId));
    }

    public void updateEventStatus(UUID userId, UUID eventId, EventStatus status) {
        UserDetails userDetails = userService.getUserFor(userId);
        EventScheduler event = getEventBy(eventId);
        log.info("updateEventStatus -> {}", userDetails);
        List<EventAudience> audiences = eventAudienceRepository.findByEventAndUser(event, userDetails);
        log.info("audiences : {}", audiences);
        if (audiences == null || audiences.isEmpty()) {
            throw new ActionNotAllowed();
        }
        EventAudience eventAudience = audiences.get(0);
        eventAudience.setStatus(status);
        eventAudience = eventAudienceRepository.save(eventAudience);
        log.info("eventAudience : {}", eventAudience);
    }

    protected EventScheduler getEventBy(UUID eventId) {
        Optional<EventScheduler> eventScheduler = eventSchedulerRepository.findById(eventId);
        if (eventScheduler.isPresent()) {
            return eventScheduler.get();
        }
        throw new EventNotFound();
    }

    protected List<EventScheduler> getEventBy(List<UserDetails> userDetails, Date fromDate, Date toDate) {
        List<UUID> userIds = userDetails.stream().map(UserDetails::getUserId).toList();
        return eventSchedulerRepository.findByParticipantUserIdsAndDateRange(userIds, fromDate, toDate);
    }
}
