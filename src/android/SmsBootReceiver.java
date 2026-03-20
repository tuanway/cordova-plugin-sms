package com.cordova.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

public class SmsBootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action;
    SharedPreferences prefs;
    boolean enabled;
    String optionsJson;
    JSONObject options;
    Intent serviceIntent;

    action = intent == null ? "" : intent.getAction();
    if (!"android.intent.action.BOOT_COMPLETED".equals(action) && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
      return;
    }

    prefs = context.getSharedPreferences(Sms.PREFS_NAME, Context.MODE_PRIVATE);
    enabled = prefs.getBoolean(Sms.PREF_KEY_BACKGROUND_WATCH, false);
    if (!enabled) {
      return;
    }

    optionsJson = prefs.getString(Sms.PREF_KEY_BACKGROUND_WATCH_OPTIONS, "{}");
    try {
      options = new JSONObject(optionsJson);
    } catch (Exception ignored) {
      options = new JSONObject();
    }

    serviceIntent = SmsWatchService.buildStartIntent(context, options);
    if (Build.VERSION.SDK_INT >= 26) {
      context.startForegroundService(serviceIntent);
    } else {
      context.startService(serviceIntent);
    }
  }
}
