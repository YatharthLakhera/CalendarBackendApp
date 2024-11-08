package com.calendar.app.db.repository;

import com.calendar.app.db.entity.EventScheduler;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface EventSchedulerRepository extends JpaRepository<EventScheduler, UUID> {

    @Query("SELECT e FROM EventScheduler e JOIN e.audiences aud WHERE aud.user.userId IN :userIds AND e.eventDate BETWEEN :startDate AND :endDate")
    List<EventScheduler> findByParticipantUserIdsAndDateRange(
            @Param("userIds") List<UUID> userIds,
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);
}
