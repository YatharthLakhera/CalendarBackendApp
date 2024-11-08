package com.calendar.app.db.entity;

import com.calendar.app.enums.TimeZone;
import com.calendar.app.models.api.CustomAvailabilityModel;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "user_custom_availability")
public class UserCustomAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private UUID userId;
    @Temporal(TemporalType.DATE)
    private Date availabilityDate;
    @Enumerated(EnumType.STRING)
    private TimeZone timeZone;
    private boolean isAvailable;
    private String availability;

    public UserCustomAvailability(UUID userId) {
        this.userId = userId;
    }

    public void update(CustomAvailabilityModel request) {
        this.availabilityDate = request.getDate();
        this.timeZone = request.getTimeZone();
        this.isAvailable = request.isAvailable();
        this.availability = request.getAvailability();
    }
}
