package com.calendar.app.exceptions;

public class EventNotFound extends NotFoundException {

    public EventNotFound() {
        super("Event Not Found");
    }
}
