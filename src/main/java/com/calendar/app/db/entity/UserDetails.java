package com.calendar.app.db.entity;

import com.calendar.app.db.UserDefaultAvailability;
import com.calendar.app.db.converter.UserDefaultAvailabilityConverter;
import com.calendar.app.models.api.UserRequest;
import com.calendar.app.models.api.UserUpdateRequest;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "user_details")
public class UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID userId;
    private String name;
    private String email;
    @Convert(converter = UserDefaultAvailabilityConverter.class)
    private UserDefaultAvailability defaultAvailability;

    public UserDetails(UserRequest userRequest) {
        this.email = userRequest.getEmail();
        this.name = userRequest.getName();
    }
}
