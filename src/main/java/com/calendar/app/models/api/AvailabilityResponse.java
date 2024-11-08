package com.calendar.app.models.api;

import com.calendar.app.models.helper.AvailabilityBucket;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AvailabilityResponse {

    public List<AvailabilityBucket> availabilityBuckets;
    // This will only contain the events of organiser as they should not see others events details
    public List<EventSchedulingResponse> events;

    public AvailabilityResponse(List<AvailabilityBucket> availabilityBuckets, List<EventSchedulingResponse> events) {
        this.availabilityBuckets = availabilityBuckets;
        this.events = events;
    }
}
