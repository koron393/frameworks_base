/*
 * Copyright (C) 2018 The OmniROM Project
 *                    The PixelExperience Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.internal.util.crdroid.weather;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.android.internal.R;

public class WeatherClient {

    public static final String SERVICE_PACKAGE = "com.crdroid.weather.client";
    public static final Uri WEATHER_URI = Uri.parse("content://com.crdroid.weather.client.provider/weather");
    public static final int WEATHER_UPDATE_SUCCESS = 0; // Success
    public static final int WEATHER_UPDATE_RUNNING = 1; // Update running
    public static final int WEATHER_UPDATE_ERROR = 2; // Error
    private static final String TAG = "WeatherClient";
    private static final boolean DEBUG = false;
    private static final String COLUMN_STATUS = "status";
    private static final String COLUMN_CONDITIONS = "conditions";
    private static final String COLUMN_TEMPERATURE_METRIC = "temperatureMetric";
    private static final String COLUMN_TEMPERATURE_IMPERIAL = "temperatureImperial";
    private static final String[] PROJECTION_DEFAULT_WEATHER = new String[]{
            COLUMN_STATUS,
            COLUMN_CONDITIONS,
            COLUMN_TEMPERATURE_METRIC,
            COLUMN_TEMPERATURE_IMPERIAL
    };

    private static final int WEATHER_UPDATE_INTERVAL = 60 * 20 * 1000; // 20 minutes
    private String updateIntentAction;
    private PendingIntent pendingWeatherUpdate;
    private WeatherInfo mWeatherInfo = new WeatherInfo();
    private Context mContext;
    private List<WeatherObserver> mObserver;
    private boolean isRunning;
    private boolean mScreenOn;
    private long lastUpdated = 0;
    private long scheduledAlarmTime = 0;
    private AlarmManager alarmManager;
    private IDreamManager mDreamManager;

    private BroadcastReceiver mWeatherReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (DEBUG) Log.d(TAG, "Received intent: " + action);
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
                cancelWeatherUpdateAlarm();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (!isDozeMode()) {
                    mScreenOn = true;
                }
            } else if (Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                resetScheduledAlarm();
            }
            updateWeather(false);
        }
    };

    public void updateWeather(boolean forced) {
        if (forced) {
            if (DEBUG) Log.d(TAG, "Forced update, triggering updateWeatherAndNotify");
            updateWeatherAndNotify();
        } else if (mScreenOn) {
            if (needsUpdate()) {
                if (DEBUG) Log.d(TAG, "Needs update, triggering updateWeatherAndNotify");
                updateWeatherAndNotify();
            } else {
                if (DEBUG) Log.d(TAG, "Scheduling update");
                scheduleWeatherUpdateAlarm();
            }
        }
    }

    private final Runnable mWeatherUpdate = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "updateWeatherData called");
            updateWeatherData();
            for (WeatherObserver observer : mObserver) {
                try {
                    observer.onWeatherUpdated(mWeatherInfo);
                } catch (Exception ignored) {
                }
            }
            lastUpdated = System.currentTimeMillis();
            resetScheduledAlarm();
            isRunning = false;
        }
    };

    private final Runnable mAsyncWeatherUpdate = new Runnable() {
        @Override
        public void run() {
            AsyncTask.execute(mWeatherUpdate);
        }
    };

    private boolean isDozeMode() {
        try {
            if (mDreamManager != null && mDreamManager.isDozing()) {
                return true;
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    public WeatherClient(Context context) {
        mContext = context;
        mObserver = new ArrayList<>();
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        updateIntentAction = "updateIntentAction_" + Integer.toString(getRandomInt());
        pendingWeatherUpdate = PendingIntent.getBroadcast(mContext, getRandomInt(), new Intent(updateIntentAction), 0);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(updateIntentAction);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mWeatherReceiver, filter);
    }

    public static boolean isAvailable(Context context) {
        final PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(SERVICE_PACKAGE, PackageManager.GET_ACTIVITIES);
            int enabled = pm.getApplicationEnabledSetting(SERVICE_PACKAGE);
            return enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private int getRandomInt() {
        Random r = new Random();
        return r.nextInt((20000000 - 10000000) + 1) + 10000000;
    }

    private boolean isBootCompleted() {
        return SystemProperties.get("sys.boot_completed").equals("1");
    }

    private void updateWeatherAndNotify() {
        if (isRunning || !isBootCompleted()) {
            return;
        }
        isRunning = true;
        mAsyncWeatherUpdate.run();
    }

    private boolean needsUpdate() {
        boolean lastUpdatedExpired = System.currentTimeMillis() - lastUpdated > WEATHER_UPDATE_INTERVAL;
        return mWeatherInfo.getStatus() != WEATHER_UPDATE_SUCCESS || lastUpdatedExpired;
    }

    private void resetScheduledAlarm() {
        scheduledAlarmTime = 0;
        scheduleWeatherUpdateAlarm();
    }

    private void scheduleWeatherUpdateAlarm() {
        if (System.currentTimeMillis() >= scheduledAlarmTime) {
            scheduledAlarmTime = System.currentTimeMillis() + WEATHER_UPDATE_INTERVAL;
        }
        alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(pendingWeatherUpdate);
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, scheduledAlarmTime, pendingWeatherUpdate);
        if (DEBUG) Log.d(TAG, "Update scheduled");
    }

    private void cancelWeatherUpdateAlarm() {
        alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(pendingWeatherUpdate);
        if (DEBUG) Log.d(TAG, "Update scheduling canceled");
    }

    private void updateWeatherData() {
        Cursor c = mContext.getContentResolver().query(WEATHER_URI, PROJECTION_DEFAULT_WEATHER,
                null, null, null);
        if (c != null) {
            try {
                int count = c.getCount();
                if (count > 0) {
                    for (int i = 0; i < count; i++) {
                        c.moveToPosition(i);
                        if (i == 0) {
                            mWeatherInfo.status = c.getInt(0);
                            mWeatherInfo.conditions = c.getString(1);
                            mWeatherInfo.temperatureMetric = c.getInt(2);
                            mWeatherInfo.temperatureImperial = c.getInt(3);
                        }
                    }
                }
            } finally {
                c.close();
            }
        } else {
            mWeatherInfo.status = WEATHER_UPDATE_ERROR;
        }

        if (DEBUG) Log.d(TAG, mWeatherInfo.toString());
    }

    public void addObserver(final WeatherObserver observer) {
        mObserver.add(observer);
    }

    public void removeObserver(WeatherObserver observer) {
        mObserver.remove(observer);
    }

    public void destroy() {
        mContext.unregisterReceiver(mWeatherReceiver);
        mObserver = new ArrayList<>();
    }

    public interface WeatherObserver {
        void onWeatherUpdated(WeatherInfo info);
    }

    public class WeatherInfo {

        int status = WEATHER_UPDATE_ERROR;
        String conditions = "";
        int temperatureMetric = 0;
        int temperatureImperial = 0;
        final Map<String, Integer> conditionsToDrawableMap = new HashMap<>();

        public WeatherInfo() {
            conditionsToDrawableMap.put("partly-cloudy", R.drawable.weather_partly_cloudy);
            conditionsToDrawableMap.put("partly-cloudy-night", R.drawable.weather_partly_cloudy_night);
            conditionsToDrawableMap.put("mostly-cloudy", R.drawable.weather_mostly_cloudy);
            conditionsToDrawableMap.put("mostly-cloudy-night", R.drawable.weather_mostly_cloudy_night);
            conditionsToDrawableMap.put("cloudy", R.drawable.weather_cloudy);
            conditionsToDrawableMap.put("clear-night", R.drawable.weather_clear_night);
            conditionsToDrawableMap.put("mostly-clear-night", R.drawable.weather_mostly_clear_night);
            conditionsToDrawableMap.put("sunny", R.drawable.weather_sunny);
            conditionsToDrawableMap.put("mostly-sunny", R.drawable.weather_mostly_sunny);
            conditionsToDrawableMap.put("scattered-showers", R.drawable.weather_scattered_showers);
            conditionsToDrawableMap.put("scattered-showers-night", R.drawable.weather_scattered_showers_night);
            conditionsToDrawableMap.put("rain", R.drawable.weather_rain);
            conditionsToDrawableMap.put("windy", R.drawable.weather_windy);
            conditionsToDrawableMap.put("snow", R.drawable.weather_snow);
            conditionsToDrawableMap.put("scattered-thunderstorms", R.drawable.weather_isolated_scattered_thunderstorms);
            conditionsToDrawableMap.put("scattered-thunderstorms-night", R.drawable.weather_isolated_scattered_thunderstorms_night);
            conditionsToDrawableMap.put("isolated-thunderstorms", R.drawable.weather_isolated_scattered_thunderstorms);
            conditionsToDrawableMap.put("isolated-thunderstorms-night", R.drawable.weather_isolated_scattered_thunderstorms_night);
            conditionsToDrawableMap.put("thunderstorms", R.drawable.weather_thunderstorms);
            conditionsToDrawableMap.put("foggy", R.drawable.weather_foggy);
        }

        public int getTemperature(boolean metric) {
            return metric ? this.temperatureMetric : this.temperatureImperial;
        }

        public int getStatus() {
            return this.status;
        }

        public String getConditions() {
            return this.conditions;
        }

        public int getWeatherConditionImage() {
            if (conditionsToDrawableMap.containsKey(conditions))
                return conditionsToDrawableMap.get(conditions);
            return 0;
        }

        @Override
        public String toString() {
            return "WeatherInfo: " +
                    "status=" + getStatus() + "," +
                    "conditions=" + getConditions() + "," +
                    "temperatureMetric=" + getTemperature(true) + "," +
                    "temperatureImperial=" + getTemperature(false);
        }
    }
}
