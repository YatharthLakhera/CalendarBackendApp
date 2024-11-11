# CalendarBackendApp

## Overview

This is a application designed for Calendly Backend. Functionality the application provides as shared below!

### User Related Feature

This is to cover user journey. Currently this only supports `name` and `email` properties. User can later update the `name` but `email` cannot be changed.
This provides with 4 api's for user endpoint to:
- Create user
- Update user name
- Get user via ID (UUID is user as userId which will help point to a specific unique user while also avoiding security risks)
- Search api to get user via email which will be used when booking appointment for a user. It also helps in testing as UUID is not something that can be rememberd

**Note** : UUID is stored in DB as binary so it will look like garbarge value and cannot be queried directly. Please use the below example **query** to search via `UUID`

'''
SELECT * FROM user_details WHERE id = UNHEX(REPLACE('1728dfe4-a197-47bf-9c4c-c80011c58f9e', '-', ''));
'''

### User Availability Feature

This feature helps set a default week based availability for the user while also providing an option to add custom availability on specific dates. This endpoint supports 4 api's.
- Add and update default availablity of user. This also accepts timezone but this functionality is not yet implemented.
- Get default availability slots for the user and the set timezone
- Add and update custom dates based availability
- Main api which takes in the user and a list of emails for which user wants to book meeting. This returns an intersection timeslots based on all users default/custom availability and already booked appointments. If no emails are passed then it returns the availability of the user. It also returns list of all the events for that user as they should be able to view those. Lastly this api execpts a window of `DAY` or `WEEK` based on which the availability is shared.

### Appointment Booking Feature

This feature details with booking, rescheduling, cancelling and getting appointment details. It also have another api which let's participants accept or decline the invite.

## Assumptions

- Email cannot be changed as that's a critical piece of information which helps in booking appointment.
- User only will want to see availability for a day or at max a week. The design do support scaling this but currently its not added.
- This feature have supports for Timezone but its not implemented. So currently we are assuming that all participants are in the same timezone i.e IST.
- Users cannot see events of other users.
- User can only book appointments of duration which are multiple of 5 minutes.
- If a meeting is from `10:00 am` to `11:00 am` for a user then extremities cannot be used for another meeting i.e other meetings can have starttime at `11:05 am` or endtime at `9:55 am`
- Currently system is not using caching of already calculated slots on individual bases as we are not expecting meeting to be booked for huge number of participants. Expection is around 10 but the solution is scalable.
- User have option to accept or decline an appointment but for the organiser it's `auto-accepted`.
- Only Participants with role of `ORGANISER` can delete or update the event.
  
## Requirements

- Maven 3
- Spring 3
- Java 17
- Mysql 8 Server Running
- Swagger UI 3 is added to the project (http://localhost:8080/swagger-ui/index.html#)

## Post Requests Body

### Users Request

```
{
  "name": "user11",
  "email": "user11@gmail.com"
}
```

### Availability Default Request

Adding default priority for the users weekly calendar as 10 am to 11 am & 1 pm to 6 pm

```
{
  "timeZone": "IST",
  "monday": "1000:1100;1300:1800",
  "tuesday": "1000:1100;1300:1800",
  "wednesday": "1000:1100;1300:1800",
  "thursday": "1000:1100;1300:1800",
  "friday": "1000:1100;1300:1800",
  "saturday": "1000:1100;1300:1800",
  "sunday": "1000:1100;1300:1800"
}
```

### Custom Request

```
{
  "date": "2024-12-11",
  "timeZone": "IST",
  "availability": "1000:1100;1300:1800",
  "available": true
}
```

### Event Request

```
{
  "title": "Testing",
  "description": "Testing appointment booking",
  "eventDate": "2024-11-11",
  "startTime": "1000",
  "endTime": "1100",
  "timeZone": "IST",
  "audienceReq": [
    {
      "email": "user1@gmail.com",
      "role": "ORGANISER"
    },{
      "email": "user2@gmail.com",
      "role": "PARTICIPANT"
    }
  ]
}
```
