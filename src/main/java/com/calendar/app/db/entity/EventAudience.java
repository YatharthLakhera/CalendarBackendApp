package com.calendar.app.db.entity;

import com.calendar.app.enums.EventStatus;
import com.calendar.app.enums.UserEventRole;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.util.Objects;


@Data
@Entity
@NoArgsConstructor
@Table(name = "event_audience")
public class EventAudience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    @JsonBackReference
    private EventScheduler event;
    @OneToOne
    @JoinColumn(name = "user_id")
    private UserDetails user;
    @Enumerated(EnumType.STRING)
    private UserEventRole role;
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    public EventAudience(EventScheduler event, UserDetails userDetails, UserEventRole role, EventStatus status) {
        this.event = event;
        this.user = userDetails;
        this.role = role;
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        EventAudience that = (EventAudience) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
