package com.calendar.app.models.helper;

import com.calendar.app.db.entity.UserDetails;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class User {

    private UUID userId;
    private String email;
    private String name;

    public User(UserDetails userDetails) {
        this.userId = userDetails.getUserId();
        this.email = userDetails.getEmail();
        this.name = userDetails.getName();
    }
}
