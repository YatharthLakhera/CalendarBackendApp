package com.calendar.app.db.entity;

import com.calendar.app.enums.EventStatus;
import com.calendar.app.enums.TimeZone;
import com.calendar.app.enums.UserEventRole;
import com.calendar.app.models.api.EventSchedulingRequest;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@Entity
@NoArgsConstructor
@Table(name = "event_scheduler")
public class EventScheduler {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;
    private String title;
    private String description;
    @Temporal(TemporalType.DATE)
    private Date eventDate;
    private String startTime;
    private String endTime;
    @Enumerated(EnumType.STRING)
    private TimeZone timeZone;
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventAudience> audiences;

    public EventScheduler(EventSchedulingRequest request) {
        this.title = request.getTitle();
        this.description = request.getDescription();
        this.eventDate = request.getEventDate();
        this.startTime = request.getStartTime();
        this.endTime = request.getEndTime();
        this.timeZone = request.getTimeZone();
    }

    public void update(EventSchedulingRequest request) {
        this.title = request.getTitle();
        this.description = request.getDescription();
        this.startTime = request.getStartTime();
        this.endTime = request.getEndTime();
        this.timeZone = request.getTimeZone();
    }

    public void addAudience(UserDetails userDetails, UserEventRole role, EventStatus status) {
        if (this.audiences == null) {
            this.audiences = new ArrayList<>();
        }
        this.audiences.add(new EventAudience(this, userDetails, role, status));
    }

    public void removeAllAudiences() {
        for (EventAudience audience : new HashSet<>(this.audiences)) {
            remove(audience);
        }
    }

    public void remove(EventAudience audience) {
        audience.setEvent(null); // Clear the reference to this event
        audiences.remove(audience); // Remove from the list
    }

    public boolean isOrganisedBy(UserDetails user) {
        for (EventAudience audience : this.audiences) {
            if (user.getUserId().equals(audience.getUser().getUserId())
                    && audience.getRole() == UserEventRole.ORGANISER) {
                return true;
            }
        }
        return false;
    }
}
