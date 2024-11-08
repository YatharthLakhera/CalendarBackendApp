package com.calendar.app.models.helper;

import com.calendar.app.db.entity.EventAudience;
import com.calendar.app.enums.EventStatus;
import com.calendar.app.enums.UserEventRole;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AudienceRes {

    private User user;
    private UserEventRole role;
    private EventStatus status;

    public AudienceRes(EventAudience eventAudience) {
        this.user = new User(eventAudience.getUser());
        this.role = eventAudience.getRole();
        this.status = eventAudience.getStatus();
    }

    public AudienceRes(User user, UserEventRole role, EventStatus status) {
        this.user = user;
        this.role = role;
        this.status = status;
    }
}
