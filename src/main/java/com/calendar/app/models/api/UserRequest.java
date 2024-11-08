package com.calendar.app.models.api;

import com.calendar.app.db.entity.UserDetails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserRequest extends UserUpdateRequest {

    @Email(message = "Please provide a valid email")
    @NotNull(message = "Email is Required")
    private String email;

    public UserRequest(UserDetails userDetails) {
        super(userDetails.getName());
        this.email = userDetails.getEmail();
    }
}
