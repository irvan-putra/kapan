package com.vanipy.kapan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FormatTest {

    // 2026-11-24 12:00 WIB = 2026-11-24 05:00 UTC
    private static final long NOW = 1_764_014_400_000L + 5L * 3_600_000L;

    @Test
    public void untilDaysAndHours() {
        long target = NOW + 3L * 86_400_000L + 4L * 3_600_000L;
        assertEquals("in 3d 4h", Format.until(NOW, target));
    }

    @Test
    public void untilHoursAndMinutes() {
        long target = NOW + 5L * 3_600_000L + 12L * 60_000L;
        assertEquals("in 5h 12m", Format.until(NOW, target));
    }

    @Test
    public void untilMinutesOnly() {
        assertEquals("in 40m", Format.until(NOW, NOW + 40L * 60_000L));
    }

    @Test
    public void untilPastIsNow() {
        assertEquals("now", Format.until(NOW, NOW - 60_000L));
    }

    @Test
    public void untilNumberHoursUnderTwoDays() {
        assertEquals("5", Format.untilNumber(NOW, NOW + 5L * 3_600_000L));
        assertEquals("h", Format.untilUnit(NOW, NOW + 5L * 3_600_000L));
    }

    @Test
    public void untilNumberDaysFromTwoDays() {
        assertEquals("3", Format.untilNumber(NOW, NOW + 3L * 86_400_000L));
        assertEquals("d", Format.untilUnit(NOW, NOW + 3L * 86_400_000L));
    }

    @Test
    public void whenDateOnlyOmitsTime() {
        assertEquals(Format.day(NOW), Format.when(NOW, true));
    }
}
