package local.paperclock;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/** Calendar and monotonic-clock calculations shared by the screen and JVM checks. */
public final class CalendarModel {
    public static final TimeZone KOREA = TimeZone.getTimeZone("Asia/Seoul");
    public static final String[] WEEKDAYS = {"일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일"};

    private CalendarModel() { }

    public static Calendar at(long utcMillis) {
        Calendar c = new GregorianCalendar(KOREA);
        c.setTimeInMillis(utcMillis);
        return c;
    }

    public static long anchoredTime(long utcAnchor, long elapsedAnchor, long elapsedNow) {
        return utcAnchor + elapsedNow - elapsedAnchor;
    }

    public static long nextMinuteDelay(long utcMillis) {
        return 60000L - (utcMillis % 60000L) + 30L;
    }

    public static Calendar monthStart(int year, int month) {
        Calendar c = new GregorianCalendar(KOREA);
        c.clear();
        c.set(year, month, 1);
        return c;
    }

    public static Calendar[] monthCells(int year, int month, boolean mondayFirst) {
        Calendar day = monthStart(year, month);
        int first = mondayFirst ? Calendar.MONDAY : Calendar.SUNDAY;
        int leading = (day.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        day.add(Calendar.DAY_OF_MONTH, -leading);
        Calendar[] cells = new Calendar[42];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (Calendar) day.clone();
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cells;
    }
}
