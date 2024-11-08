package com.calendar.app.exceptions;

public class UserNotFound extends NotFoundException {

    public UserNotFound() {
        super("User Not Found");
    }
}
