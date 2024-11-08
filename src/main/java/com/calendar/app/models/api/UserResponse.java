package com.calendar.app.models.api;

import com.calendar.app.db.entity.UserDetails;
import lombok.Data;

import java.util.UUID;

@Data
public class UserResponse extends UserRequest {

    private UUID id;

    public UserResponse(UserDetails userDetails) {
        super(userDetails);
        this.id = userDetails.getUserId();
    }
}
