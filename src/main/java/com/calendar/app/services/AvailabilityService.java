package com.calendar.app.services;

import com.calendar.app.db.UserDefaultAvailability;
import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.db.entity.UserCustomAvailability;
import com.calendar.app.db.entity.UserDetails;
import com.calendar.app.db.repository.UserCustomAvailabilityRepository;
import com.calendar.app.enums.AvailabilityWindowType;
import com.calendar.app.models.api.AvailabilityResponse;
import com.calendar.app.models.api.CustomAvailabilityModel;
import com.calendar.app.utils.AvailabilityUtility;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AvailabilityService {

    @Autowired private UserService userService;
    @Autowired private UserCustomAvailabilityRepository userCustomAvailabilityRepository;
    @Autowired private EventSchedulerService eventSchedulerService;

    public void updateUserDefaultAvailabilityFor(UUID userId, UserDefaultAvailability defaultAvailability) {
        UserDetails userDetails = userService.getUserFor(userId);
        userDetails.setDefaultAvailability(defaultAvailability);
        userService.createOrUpdate(userDetails);
    }

    public void updateUserAvailabilityFor(UUID userId, CustomAvailabilityModel request) {
        UserDetails userDetails = userService.getUserFor(userId);
        log.info("updateUserAvailabilityFor -> {}", userDetails);
        List<UserCustomAvailability> customAvailabilities = userCustomAvailabilityRepository.
                findByUserIdAndAvailabilityDate(userId, request.getDate());
        UserCustomAvailability userCustomAvailability = new UserCustomAvailability(userId);
        if (customAvailabilities != null && !customAvailabilities.isEmpty()) {
            userCustomAvailability = customAvailabilities.get(0);
        }
        userCustomAvailability.update(request);
        userCustomAvailabilityRepository.save(userCustomAvailability);
    }

    public UserDefaultAvailability getDefaultAvailabilityFor(UUID userId) {
        UserDetails userDetails = userService.getUserFor(userId);
        log.info("userDetails : {}", userDetails);
        return userDetails.getDefaultAvailability();
    }

    public AvailabilityResponse getAvailabilityFor(UUID userId, Date fromDate, AvailabilityWindowType windowType, List<String> emails) {
        Date toDate = DateUtils.addDays(fromDate, windowType.getDays());
        UserDetails organiser = userService.getUserFor(userId);
        List<UserDetails> participants = !emails.isEmpty() ? userService.getUserFor(emails) : new ArrayList<>();
        participants.add(organiser);
        List<EventScheduler> eventSchedulers = eventSchedulerService.getEventBy(participants, fromDate, toDate);
        log.info("eventSchedulers : {}", eventSchedulers);
        List<UserCustomAvailability> customAvailabilityModels = userCustomAvailabilityRepository.findByUserIdsAndDateRange(
                participants.stream().map(UserDetails::getUserId).toList(),
                fromDate, toDate
        );
        log.info("customAvailabilityModels : {}", customAvailabilityModels);
        return AvailabilityUtility.availabilityResponseBuilder(
                organiser, participants, customAvailabilityModels, eventSchedulers, fromDate, windowType
        );
    }
}
