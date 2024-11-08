package com.calendar.app.db.repository;

import com.calendar.app.db.entity.UserCustomAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface UserCustomAvailabilityRepository extends JpaRepository<UserCustomAvailability, Integer> {

    List<UserCustomAvailability> findByUserIdAndAvailabilityDate(UUID userId, Date availabilityDate);

    @Query("SELECT e FROM UserCustomAvailability e WHERE e.userId IN :userIds AND e.availabilityDate BETWEEN :startDate AND :endDate")
    List<UserCustomAvailability> findByUserIdsAndDateRange(
            @Param("userIds") List<UUID> userIds,
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);
}
