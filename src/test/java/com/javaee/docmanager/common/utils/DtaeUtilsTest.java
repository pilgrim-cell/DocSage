package com.javaee.docmanager.common.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DateUtilsTest {

    @Test
    void formatDate_validDate_shouldReturnFormattedString() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        assertEquals("2026-06-21", DateUtils.formatDate(date));
    }

    @Test
    void formatDate_null_shouldReturnEmptyString() {
        assertEquals("", DateUtils.formatDate((LocalDate) null));
    }

    @Test
    void parseDate_validString_shouldReturnDate() {
        LocalDate date = DateUtils.parseDate("2026-06-21");
        assertEquals(LocalDate.of(2026, 6, 21), date);
    }

    @Test
    void parseDate_emptyString_shouldReturnNull() {
        assertNull(DateUtils.parseDate(""));
    }

    @Test
    void formatAndParseDateTime_shouldBeReversible() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 6, 21, 15, 30, 0);
        String formatted = DateUtils.formatDateTime(dateTime);
        LocalDateTime parsed = DateUtils.parseDateTime(formatted);
        assertEquals(dateTime, parsed);
    }
}