package com.calendar.app.exceptions;

public class ActionNotAllowed extends RuntimeException {

    public ActionNotAllowed() {
        super("Unable to perform the action");
    }
}
