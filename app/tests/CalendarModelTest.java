package local.paperclock;

import java.util.Calendar;

public final class CalendarModelTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Calendar oct26 = CalendarModel.monthStart(2026, Calendar.OCTOBER);
        oct26.set(Calendar.DAY_OF_MONTH, 26);
        check(oct26.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY, "Korean weekday");
        check(CalendarModel.at(oct26.getTimeInMillis()).get(Calendar.DAY_OF_MONTH) == 26, "KST round trip");
        Calendar[] october = CalendarModel.monthCells(2026, Calendar.OCTOBER, false);
        check(october[4].get(Calendar.DAY_OF_MONTH) == 1, "Sunday-first alignment");
        check(october[0].get(Calendar.MONTH) == Calendar.SEPTEMBER, "leading month");
        Calendar[] monday = CalendarModel.monthCells(2026, Calendar.OCTOBER, true);
        check(monday[3].get(Calendar.DAY_OF_MONTH) == 1, "Monday-first alignment");
        Calendar[] feb = CalendarModel.monthCells(2024, Calendar.FEBRUARY, false);
        int febDays = 0;
        for (Calendar c : feb) if (c.get(Calendar.MONTH) == Calendar.FEBRUARY) febDays++;
        check(febDays == 29, "leap year");
        Calendar[] december = CalendarModel.monthCells(2026, Calendar.DECEMBER, false);
        check(december[41].get(Calendar.YEAR) == 2027, "year boundary");
        check(CalendarModel.anchoredTime(100000, 1000, 65000) == 164000, "elapsed clock including sleep");
        check(CalendarModel.nextMinuteDelay(59999) == 31, "minute boundary scheduling");
        System.out.println("PASS: weekday, month alignment, leap year, year rollover, monotonic clock, minute scheduling");
    }
}
