package com.calendar.app.utils;

import com.calendar.app.db.entity.EventScheduler;
import com.calendar.app.db.entity.UserCustomAvailability;
import com.calendar.app.db.entity.UserDetails;
import com.calendar.app.enums.AvailabilityWindowType;
import com.calendar.app.models.api.AvailabilityResponse;
import com.calendar.app.models.api.EventSchedulingResponse;
import com.calendar.app.models.helper.AvailabilityBucket;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AvailabilityUtility {

    private static final int AVAILABILITY_MATRIX_LENGTH = 288;

    private enum ProcessType {
        INCREASE(1), DECREASE(-1);

        private final int value;

        public int getValue() {
            return value;
        }

        ProcessType(int value) {
            this.value = value;
        }
    }

    public static AvailabilityResponse availabilityResponseBuilder(UserDetails organiser, List<UserDetails> participants,
                                                                   List<UserCustomAvailability> customAvailabilities,
                                                                   List<EventScheduler> eventSchedulers,
                                                                   Date fromDate, AvailabilityWindowType windowType) {

        Map<UUID, List<UserCustomAvailability>> userBasedCustomAvailabilitiesMap = customAvailabilities.stream()
                .collect(Collectors.groupingBy(UserCustomAvailability::getUserId));
        List<AvailabilityBucket> userAvailabilityBuckets = new ArrayList<>();
        for (UserDetails participant : participants) {
            UUID participantId = participant.getUserId();
            List<UserCustomAvailability> userBasedCustomAvailabilities = userBasedCustomAvailabilitiesMap.get(participantId);
            if (userBasedCustomAvailabilities == null) userBasedCustomAvailabilities = new ArrayList<>();
            userAvailabilityBuckets.addAll(
                    getAvailabilityBucketsFor(
                            fromDate,
                            windowType,
                            participant,
                            userBasedCustomAvailabilities
                    )
            );
        }
        List<AvailabilityBucket> finalAvailabilityList = processFinalAvailabilityList(
                fromDate, windowType, participants.size(), userAvailabilityBuckets, eventSchedulers
        );
        return new AvailabilityResponse(
                finalAvailabilityList,
                eventSchedulers.stream()
                        .filter(event -> event.isOrganisedBy(organiser))
                        .map(EventSchedulingResponse::new)
                        .toList()
        );
    }

    private static List<AvailabilityBucket> getAvailabilityBucketsFor(Date fromDate, AvailabilityWindowType windowType,
            UserDetails participant, List<UserCustomAvailability> customAvailabilities) {

        // Getting list of timings for the week
        List<String> weeksAvailability = new ArrayList<>();
        if (participant.getDefaultAvailability() != null) {
            weeksAvailability = participant.getDefaultAvailability().getWeeksAvailability();
        }
        Map<Date, UserCustomAvailability> customAvailabilityMap = customAvailabilities.stream()
                .collect(Collectors.toMap(UserCustomAvailability::getAvailabilityDate, availability -> availability));
        DayOfWeek dayOfWeek = getDayOfWeekFor(fromDate);
        // Rotating the list to matching the current day of the week order
        if (!weeksAvailability.isEmpty()) {
            Collections.rotate(weeksAvailability, - dayOfWeek.getValue());
        }
        List<AvailabilityBucket> availabilityBuckets = new ArrayList<>();
        for (int day = 0; day < windowType.getDays(); day++) {
            Date currentDate = DateUtils.addDays(fromDate, day);
            String availabilityString;
            // If custom availability is present, ignoring default one
            if (customAvailabilityMap.containsKey(currentDate)) {
                // Adding empty availability if user is not available
                availabilityString = customAvailabilityMap.get(currentDate).isAvailable()
                        ? customAvailabilityMap.get(currentDate).getAvailability() : "";
            } else if (!weeksAvailability.isEmpty()) {
                // Getting availability based on day of week
                availabilityString = weeksAvailability.get(day % 7);
            } else {
                continue;
            }
            // Creating the availability list
            availabilityBuckets.addAll(getAvailabilityBucketsFor(currentDate, availabilityString));
        }
        return availabilityBuckets;
    }

    private static List<AvailabilityBucket> getAvailabilityBucketsFor(Date availabilityDate, String availabilityString) {
        if (StringUtils.isBlank(availabilityString)) return new ArrayList<>();
        // Will add timezone support here
        List<AvailabilityBucket> result = new ArrayList<>();
        for (String[] timings : AvailabilityParseUtility.parse(availabilityString)) {
            result.add(new AvailabilityBucket(availabilityDate, timings[0], timings[1]));
        }
        return result;
    }

    private static List<AvailabilityBucket> processFinalAvailabilityList(
            Date fromDate, AvailabilityWindowType windowType, int participantCount,
            List<AvailabilityBucket> userAvailabilityBuckets, List<EventScheduler> eventSchedulers) {

        int[][] availabilityMatrix = new int[windowType.getDays()][AVAILABILITY_MATRIX_LENGTH];
        // Explicitly initialize the matrix with zeros
        for (int[] matrix : availabilityMatrix) {
            // Set each element to zero
            Arrays.fill(matrix, 0);
        }
        // This will add the availabilities to the matrix based on userAvailabilityBuckets
        Map<Date, List<AvailabilityBucket>> dateBasedAvailabilities = userAvailabilityBuckets.stream()
                .collect(Collectors.groupingBy(AvailabilityBucket::getDate));
        processAvailabilityMatrixFor(availabilityMatrix, ProcessType.INCREASE, dateBasedAvailabilities, fromDate, windowType);
        // This will remove the availabilities to the matrix based on events as those slots are not available
        dateBasedAvailabilities = eventSchedulers.stream()
                .map(event -> new AvailabilityBucket(event.getEventDate(), event.getStartTime(), event.getEndTime()))
                .collect(Collectors.groupingBy(AvailabilityBucket::getDate));
        processAvailabilityMatrixFor(availabilityMatrix, ProcessType.DECREASE, dateBasedAvailabilities, fromDate, windowType);
        // Calculating availability from availabilityMatrix
        return generatingAvailabilityFrom(availabilityMatrix, participantCount, fromDate, windowType);
    }

    private static void processAvailabilityMatrixFor(int[][] availabilityMatrix, ProcessType processType,
                                                     Map<Date, List<AvailabilityBucket>> dateBasedAvailabilities,
                                                     Date fromDate, AvailabilityWindowType windowType) {


        // Check for availability based on days
        for (int day = 0; day < windowType.getDays(); day++) {
            Date curDate = DateUtils.addDays(fromDate, day);
            List<AvailabilityBucket> availabilityBuckets = dateBasedAvailabilities.get(curDate);
            if (availabilityBuckets == null) continue;
            // Dividing day into 5 minutes window and checking availability at each time
            for (int timeWindow = 0; timeWindow < AVAILABILITY_MATRIX_LENGTH; timeWindow++) {
                int curTimeInMin = getCurrentTimeInMinFor(timeWindow);
                for (AvailabilityBucket availabilityBucket : availabilityBuckets) {
                    if (availabilityBucket.getStartTimeValue() <= curTimeInMin
                            && curTimeInMin <= availabilityBucket.getEndTimeValue()) {
                        // Increase when adding for availability and decreasing for blocked timings
                        availabilityMatrix[day][timeWindow] += processType.getValue();
                    }
                }
            }
        }
    }

    private static List<AvailabilityBucket> generatingAvailabilityFrom(int[][] availabilityMatrix, int participantCount,
                                                                       Date fromDate, AvailabilityWindowType windowType) {
        List<AvailabilityBucket> finalAvailability = new ArrayList<>();
        for (int day = 0; day < windowType.getDays(); day++) {
            Date curDate = DateUtils.addDays(fromDate, day);
            Integer start = null;
            for (int timeWindow = 0; timeWindow < AVAILABILITY_MATRIX_LENGTH; timeWindow++) {
                int curTimeInMin = getCurrentTimeInMinFor(timeWindow);
                log.debug("curTimeInMin : {}", curTimeInMin);
                if (availabilityMatrix[day][timeWindow] == participantCount && start == null) {
                    start = curTimeInMin;
                } else if (availabilityMatrix[day][timeWindow] != participantCount && start != null) {
                    finalAvailability.add(
                            new AvailabilityBucket(curDate, start, getCurrentTimeInMinFor(timeWindow - 1))
                    );
                    start = null;
                }
            }
        }
        return finalAvailability;
    }

    private static int getCurrentTimeInMinFor(int timeWindow) {
        int hour = ((timeWindow * 5) / 60) * 100;
        int min = (timeWindow * 5) % 60;
        // time for 24 hours clock
        return hour + min;
    }

    private static DayOfWeek getDayOfWeekFor(Date date) {
        LocalDate localDate = date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        // Get the day of the week
        return localDate.getDayOfWeek();
    }
}
