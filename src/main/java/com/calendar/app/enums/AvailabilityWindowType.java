package com.calendar.app.enums;

public enum AvailabilityWindowType {

    DAY(1), WEEK(7);

    private int days;

    AvailabilityWindowType(int days) {
        this.days = days;
    }

    public int getDays() {
        return days;
    }
}
