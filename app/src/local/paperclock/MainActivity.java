package local.paperclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.DatePicker;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** A minute-updated e-ink calendar with opportunistic network time correction. */
public final class MainActivity extends Activity {
    private static final String TAG = "PaperClock";
    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private CalendarScreen screen;
    private String bootId;
    private long anchorUtc;
    private long anchorElapsed;
    private long wallOffset;
    private boolean clockTrusted;
    private boolean mondayFirst;
    private boolean lightOn;
    private boolean resumed;
    private int shownYear;
    private int shownMonth;
    private boolean followToday = true;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean networkWasConnected;
    private boolean networkNeedsSync = true;
    private boolean syncInFlight;
    private long lastNetworkSuccess = -1;
    private long nextNetworkAttempt;
    private int networkFailures;
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean connected = networkConnected();
            boolean reconnected = connected && !networkWasConnected;
            networkWasConnected = connected;
            if (reconnected) networkNeedsSync = true;
            if (connected) requestNetworkTime(networkNeedsSync, false);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long utc = now();
            Calendar today = CalendarModel.at(utc);
            if (followToday) {
                shownYear = today.get(Calendar.YEAR);
                shownMonth = today.get(Calendar.MONTH);
            }
            screen.setContentDescription(description(today));
            screen.invalidate();
            Log.i(TAG, "render utc=" + utc + " local=" + format("yyyy-MM-dd HH:mm", utc)
                    + " trusted=" + clockTrusted);
            handler.removeCallbacks(this);
            handler.postDelayed(this, CalendarModel.nextMinuteDelay(utc));
            requestNetworkTime(networkNeedsSync, false);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences("paper_clock", MODE_PRIVATE);
        bootId = readBootId();
        anchorUtc = prefs.getLong("anchorUtc", 0);
        anchorElapsed = prefs.getLong("anchorElapsed", 0);
        wallOffset = prefs.getLong("wallOffset", 0);
        clockTrusted = anchorUtc > 0 && SystemClock.elapsedRealtime() >= anchorElapsed
                && bootId.equals(prefs.getString("bootId", ""));
        if (bootId.equals(prefs.getString("networkBootId", ""))) {
            lastNetworkSuccess = prefs.getLong("lastNetworkElapsed", -1);
        }
        mondayFirst = prefs.getBoolean("mondayFirst", false);
        lightOn = prefs.getBoolean("lightOn", false);
        acceptTime(getIntent());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyLight();
        screen = new CalendarScreen();
        setContentView(screen);
        networkWasConnected = networkConnected();
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        receiverRegistered = true;
        hideSystemBars();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptTime(intent);
        followToday = true;
        tick.run();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        boolean connected = networkConnected();
        if (connected && !networkWasConnected) networkNeedsSync = true;
        networkWasConnected = connected;
        hideSystemBars();
        tick.run();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        if (screen != null) handler.removeCallbacks(screen.longPress);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (receiverRegistered) {
            unregisterReceiver(networkReceiver);
            receiverRegistered = false;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void applyLight() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = lightOn ? 0.18f : 0.0f;
        getWindow().setAttributes(params);
    }

    private String readBootId() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            try { return reader.readLine().trim(); }
            finally { reader.close(); }
        } catch (Exception ignored) {
            // elapsedRealtime also guards against reusing an anchor from a later uptime.
            return "boot-id-unavailable";
        }
    }

    private void acceptTime(Intent intent) {
        if (intent == null || !intent.hasExtra("utcMillis")) return;
        long utc = intent.getLongExtra("utcMillis", 0);
        if (utc == prefs.getLong("lastExternalUtc", 0)) return;
        if (utc >= 1577836800000L && utc < 4102444800000L) {
            setTime(utc);
            // Android can restore the original launch Intent after process death.
            // Reusing that old timestamp would otherwise rewind the display.
            prefs.edit().putLong("lastExternalUtc", utc).commit();
            intent.removeExtra("utcMillis");
        }
    }

    private void setTime(long utc) {
        setTimeAtElapsed(utc, SystemClock.elapsedRealtime());
    }

    private void setTimeAtElapsed(long utc, long elapsed) {
        anchorElapsed = elapsed;
        anchorUtc = utc;
        wallOffset = CalendarModel.anchoredTime(utc, elapsed, SystemClock.elapsedRealtime())
                - System.currentTimeMillis();
        clockTrusted = true;
        prefs.edit().putLong("anchorUtc", anchorUtc).putLong("anchorElapsed", anchorElapsed)
                .putLong("wallOffset", wallOffset).putString("bootId", bootId).commit();
        Log.i(TAG, "time synced " + format("yyyy-MM-dd HH:mm:ss", utc));
    }

    private boolean networkConnected() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected();
    }

    private void requestNetworkTime(boolean force, final boolean showResult) {
        if (!resumed || destroyed || syncInFlight) return;
        if (!networkConnected()) {
            if (showResult) Toast.makeText(this, "와이파이 연결을 확인해 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TimeSyncPolicy.isDue(SystemClock.elapsedRealtime(), lastNetworkSuccess, nextNetworkAttempt, force)) return;
        syncInFlight = true;
        networkNeedsSync = false;
        if (showResult) Toast.makeText(this, "시간을 맞추고 있습니다", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                NetworkTimeClient.Sample sample = null;
                String failure = "No time server available";
                for (String host : new String[]{"time.google.com", "time2.google.com", "time3.google.com"}) {
                    try {
                        sample = NetworkTimeClient.query(host, new NetworkTimeClient.ElapsedClock() {
                            @Override public long millis() { return SystemClock.elapsedRealtime(); }
                        });
                        break;
                    } catch (Exception e) {
                        failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
                final NetworkTimeClient.Sample result = sample;
                final String error = failure;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        syncInFlight = false;
                        if (result != null) {
                            setTimeAtElapsed(result.utcMillis, result.elapsedMillis);
                            lastNetworkSuccess = result.elapsedMillis;
                            nextNetworkAttempt = 0;
                            networkFailures = 0;
                            prefs.edit().putLong("lastNetworkElapsed", lastNetworkSuccess)
                                    .putString("networkBootId", bootId).commit();
                            Log.i(TAG, "network sync success server=" + result.server + " rtt="
                                    + result.roundTripMillis + " utc=" + result.utcMillis);
                            if (resumed) tick.run();
                            if (showResult && resumed) Toast.makeText(MainActivity.this,
                                    "한국 시간으로 맞췄습니다", Toast.LENGTH_SHORT).show();
                        } else {
                            networkFailures++;
                            nextNetworkAttempt = SystemClock.elapsedRealtime()
                                    + TimeSyncPolicy.retryDelay(networkFailures);
                            Log.w(TAG, "network sync failed; keeping clock: " + error);
                            if (showResult && resumed) Toast.makeText(MainActivity.this,
                                    "인터넷 연결을 확인해 주세요. 현재 시간은 유지합니다", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "PaperClock-TimeSync").start();
    }

    private long now() {
        if (clockTrusted) return CalendarModel.anchoredTime(anchorUtc, anchorElapsed, SystemClock.elapsedRealtime());
        return System.currentTimeMillis() + wallOffset;
    }

    private String format(String pattern, long utc) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.KOREA);
        f.setTimeZone(CalendarModel.KOREA);
        return f.format(Long.valueOf(utc));
    }

    private String description(Calendar today) {
        return format("yyyy년 M월 d일", today.getTimeInMillis()) + " "
                + CalendarModel.WEEKDAYS[today.get(Calendar.DAY_OF_WEEK) - 1] + ", "
                + format("HH:mm", today.getTimeInMillis()) + ", "
                + shownYear + "년 " + (shownMonth + 1) + "월 달력, "
                + (clockTrusted ? "한국 시간" : "시간 설정 필요")
                + (lastNetworkSuccess >= 0 ? ", 인터넷 시간 자동 맞춤" : "")
                + ", " + (lightOn ? "조명 켜짐" : "조명 꺼짐")
                + ", " + (mondayFirst ? "월요일 시작" : "일요일 시작")
                + ", 설정은 오른쪽 위 메뉴";
    }

    private void showMenu() {
        final String[] choices = {"오늘로 돌아가기", "날짜·시간 맞추기",
                lightOn ? "조명 끄기" : "조명 켜기",
                mondayFirst ? "일요일부터 한 주 시작" : "월요일부터 한 주 시작",
                "인터넷 시간으로 지금 맞추기", "앱 닫기"};
        new AlertDialog.Builder(this).setTitle("페이퍼 시계·달력")
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) { followToday = true; tick.run(); }
                        else if (which == 1) chooseDateTime();
                        else if (which == 2) {
                            lightOn = !lightOn;
                            prefs.edit().putBoolean("lightOn", lightOn).apply();
                            applyLight(); tick.run();
                        } else if (which == 3) {
                            mondayFirst = !mondayFirst;
                            prefs.edit().putBoolean("mondayFirst", mondayFirst).apply();
                            tick.run();
                        } else if (which == 4) requestNetworkTime(true, true);
                        else finish();
                    }
                }).setNegativeButton("취소", null).show();
    }

    private void chooseDateTime() {
        Calendar current = CalendarModel.at(now());
        final DatePicker date = new DatePicker(this);
        date.setCalendarViewShown(false);
        date.init(current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH), null);
        // KitKat's DatePickerDialog can invoke its date callback even when cancelled.
        // Explicit positive buttons keep cancellation from changing the clock.
        new AlertDialog.Builder(this).setTitle("날짜 맞추기").setView(date)
                .setNegativeButton("취소", null)
                .setPositiveButton("다음", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                date.clearFocus();
                final Calendar chosen = CalendarModel.monthStart(date.getYear(), date.getMonth());
                chosen.set(Calendar.DAY_OF_MONTH, date.getDayOfMonth());
                Calendar currentTime = CalendarModel.at(now());
                final TimePicker time = new TimePicker(MainActivity.this);
                time.setIs24HourView(Boolean.TRUE);
                time.setCurrentHour(currentTime.get(Calendar.HOUR_OF_DAY));
                time.setCurrentMinute(currentTime.get(Calendar.MINUTE));
                new AlertDialog.Builder(MainActivity.this).setTitle("시간 맞추기").setView(time)
                        .setNegativeButton("취소", null)
                        .setPositiveButton("시간 맞추기", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface timeDialog, int button) {
                        time.clearFocus();
                        chosen.set(Calendar.HOUR_OF_DAY, time.getCurrentHour());
                        chosen.set(Calendar.MINUTE, time.getCurrentMinute());
                        setTime(chosen.getTimeInMillis());
                        followToday = true; tick.run();
                    }
                }).show();
            }
        }).show();
    }

    private void changeMonth(int step) {
        Calendar month = CalendarModel.monthStart(shownYear, shownMonth);
        month.add(Calendar.MONTH, step);
        shownYear = month.get(Calendar.YEAR);
        shownMonth = month.get(Calendar.MONTH);
        followToday = false;
        tick.run();
    }

    private final class CalendarScreen extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        private final RectF menu = new RectF(1240, 54, 1380, 230);
        private final RectF previous = new RectF(50, 735, 235, 925);
        private final RectF next = new RectF(1169, 735, 1354, 925);
        private final RectF title = new RectF(250, 735, 1154, 925);
        private float scale = 1f;
        private float offsetX;
        private float offsetY;
        private boolean longPressed;
        private float downX;
        private float downY;
        private final Runnable longPress = new Runnable() {
            @Override public void run() { longPressed = true; showMenu(); }
        };

        CalendarScreen() {
            super(MainActivity.this);
            setBackgroundColor(Color.WHITE);
            setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private void text(Canvas c, String s, float x, float baseline, float size, int color, Typeface face) {
            paint.reset(); paint.setAntiAlias(true); paint.setColor(color);
            paint.setTypeface(face); paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(s, x, baseline, paint);
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
            paint.reset(); paint.setAntiAlias(true); paint.setColor(color); paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(x1, y1, x2, y2, paint);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            scale = Math.min(getWidth() / 1404f, getHeight() / 1872f);
            offsetX = (getWidth() - 1404f * scale) / 2f;
            offsetY = (getHeight() - 1872f * scale) / 2f;
            c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale);
            long utc = now();
            Calendar today = CalendarModel.at(utc);
            text(c, format("yyyy년 M월 d일", utc), 702, 190, 57, Color.BLACK, bold);
            text(c, CalendarModel.WEEKDAYS[today.get(Calendar.DAY_OF_WEEK) - 1],
                    702, 280, 51, Color.rgb(80, 80, 80), bold);
            String clock = format("HH:mm", utc);
            paint.setTypeface(bold); paint.setTextSize(330);
            float clockSize = Math.min(330, 1180f / paint.measureText(clock) * 330);
            text(c, clock, 702, 607, clockSize, Color.BLACK, bold);
            line(c, 103, 710, 1301, 710, Color.rgb(175, 175, 175), 2);
            text(c, shownYear + "년 " + (shownMonth + 1) + "월", 702, 862, 61, Color.BLACK, bold);
            line(c, 158, 807, 138, 829, Color.BLACK, 5);
            line(c, 138, 829, 158, 851, Color.BLACK, 5);
            line(c, 1246, 807, 1266, 829, Color.BLACK, 5);
            line(c, 1266, 829, 1246, 851, Color.BLACK, 5);

            String[] weekdays = mondayFirst ? new String[]{"월", "화", "수", "목", "금", "토", "일"}
                    : new String[]{"일", "월", "화", "수", "목", "금", "토"};
            float cellWidth = 1198f / 7f;
            for (int col = 0; col < 7; col++) {
                text(c, weekdays[col], 103 + (col + .5f) * cellWidth, 992, 35,
                        Color.rgb(100, 100, 100), bold);
            }
            Calendar[] cells = CalendarModel.monthCells(shownYear, shownMonth, mondayFirst);
            for (int i = 0; i < cells.length; i++) {
                Calendar day = cells[i];
                float x = 103 + ((i % 7) + .5f) * cellWidth;
                float y = 1097 + (i / 7) * 111;
                boolean isToday = day.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                        && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                boolean inMonth = day.get(Calendar.MONTH) == shownMonth;
                if (isToday) {
                    paint.reset(); paint.setAntiAlias(true); paint.setColor(Color.BLACK);
                    c.drawCircle(x, y - 17, 43, paint);
                }
                text(c, Integer.toString(day.get(Calendar.DAY_OF_MONTH)), x, y, 48,
                        isToday ? Color.WHITE : inMonth ? Color.BLACK : Color.rgb(185, 185, 185),
                        bold);
            }
            line(c, 103, 1736, 1301, 1736, Color.rgb(210, 210, 210), 2);
            String footer = !clockTrusted ? "시간 설정 필요 · 오른쪽 위 메뉴"
                    : !followToday ? "월 제목을 누르면 이번 달로 돌아갑니다" : "";
            if (footer.length() > 0) text(c, footer, 702, 1811, 29, Color.rgb(110, 110, 110), bold);
            paint.reset(); paint.setAntiAlias(true); paint.setColor(Color.rgb(75, 75, 75));
            for (int i = 0; i < 3; i++) c.drawCircle(1310, 113 + i * 21, 4.5f, paint);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = (event.getX() - offsetX) / scale;
            float y = (event.getY() - offsetY) / scale;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = x; downY = y; longPressed = false;
                handler.postDelayed(longPress, 650);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (Math.abs(x - downX) + Math.abs(y - downY) > 35) handler.removeCallbacks(longPress);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(longPress);
                if (!longPressed) {
                    performClick();
                    if (menu.contains(x, y)) showMenu();
                    else if (previous.contains(x, y)) changeMonth(-1);
                    else if (next.contains(x, y)) changeMonth(1);
                    else if (title.contains(x, y)) { followToday = true; tick.run(); }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) handler.removeCallbacks(longPress);
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
