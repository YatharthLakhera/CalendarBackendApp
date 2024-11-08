package com.calendar.app.models.helper;

import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.enums.EventStatus;
import com.calendar.app.enums.UserEventRole;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AudienceReq {

    private String email;
    private UserEventRole role;

    public AudienceReq(EventAudience eventAudience) {
        this.email = eventAudience.getUser().getEmail();
        this.role = eventAudience.getRole();
    }
}
