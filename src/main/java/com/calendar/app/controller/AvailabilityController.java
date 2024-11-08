package com.calendar.app.controller;

import com.calendar.app.db.UserDefaultAvailability;
import com.calendar.app.enums.AvailabilityWindowType;
import com.calendar.app.models.api.AvailabilityResponse;
import com.calendar.app.models.api.CustomAvailabilityModel;
import com.calendar.app.services.AvailabilityService;
import com.calendar.app.utils.DateUtility;
import com.calendar.app.utils.StringUtility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class AvailabilityController {

    @Autowired private AvailabilityService availabilityService;

    @GetMapping("/availability/defaults")
    public UserDefaultAvailability getDefaultAvailabilityFor(@RequestHeader("userId") UUID userId) {
        return availabilityService.getDefaultAvailabilityFor(userId);
    }

    @PutMapping("/availability/defaults")
    public void updateUserDefaultAvailabilityFor(@RequestHeader("userId") UUID userId,
                                                 @RequestBody UserDefaultAvailability userRequest) {

        availabilityService.updateUserDefaultAvailabilityFor(userId, userRequest);
    }

    @PutMapping("/availability/custom")
    public void updateUserAvailabilityFor(@RequestHeader("userId") UUID userId,
                                          @RequestBody CustomAvailabilityModel request) {

        availabilityService.updateUserAvailabilityFor(userId, request);
    }

    @GetMapping("/availability/{fromDate}/{windowType}")
    public AvailabilityResponse getAvailabilityFor(@RequestHeader("userId") UUID userId,
                                                   @PathVariable("fromDate") String fromDate,
                                                   @PathVariable("windowType") AvailabilityWindowType windowType,
                                                   @RequestParam(value = "emails", required = false) String emailList) {

        return availabilityService.getAvailabilityFor(
                userId,
                DateUtility.parseDate(fromDate),
                windowType,
                StringUtility.getEmailListFor(emailList)
        );
    }
}
