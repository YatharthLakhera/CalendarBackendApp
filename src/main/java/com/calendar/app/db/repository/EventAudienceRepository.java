package com.calendar.app.db.repository;

import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.db.entity.UserDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventAudienceRepository extends JpaRepository<EventAudience, Integer> {

    List<EventAudience> findByEventAndUser(EventScheduler event, UserDetails user);

}
