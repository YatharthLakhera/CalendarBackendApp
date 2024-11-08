package com.calendar.app.models.api;

import com.calendar.app.enums.TimeZone;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserUpdateRequest {

    @NotNull(message = "Name is Required")
    private String name;

    public UserUpdateRequest(String name) {
        this.name = name;
    }
}
