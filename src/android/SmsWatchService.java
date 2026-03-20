package com.cordova.sms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;

import org.json.JSONObject;

public class SmsWatchService extends Service {
  private static final String ACTION_START = "com.cordova.sms.action.START_BACKGROUND_WATCH";
  private static final String ACTION_STOP = "com.cordova.sms.action.STOP_BACKGROUND_WATCH";
  private static final String CHANNEL_ID = "cordova_sms_watch";
  private static final int NOTIFICATION_ID = 9910;

  private ContentObserver smsObserver;
  private ContentObserver mmsObserver;
  private ContentObserver conversationObserver;

  public static Intent buildStartIntent(Context context, JSONObject options) {
    Intent intent;

    intent = new Intent(context, SmsWatchService.class);
    intent.setAction(ACTION_START);
    intent.putExtra("options", options == null ? "{}" : options.toString());
    return intent;
  }

  public static Intent buildStopIntent(Context context) {
    Intent intent;

    intent = new Intent(context, SmsWatchService.class);
    intent.setAction(ACTION_STOP);
    return intent;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    startForeground(NOTIFICATION_ID, buildNotification());
    registerObservers();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    unregisterObservers();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private Notification buildNotification() {
    Notification.Builder builder;
    int iconId;

    iconId = getApplicationInfo().icon == 0 ? android.R.drawable.stat_notify_more : getApplicationInfo().icon;

    if (Build.VERSION.SDK_INT >= 26) {
      createNotificationChannel();
      builder = new Notification.Builder(this, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(this);
    }

    builder.setSmallIcon(iconId);
    builder.setContentTitle("SMS background watch");
    builder.setContentText("Watching SMS and MMS provider changes");
    builder.setOngoing(true);
    return builder.build();
  }

  private void createNotificationChannel() {
    NotificationManager manager;
    NotificationChannel channel;

    manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }

    channel = new NotificationChannel(CHANNEL_ID, "SMS Background Watch", NotificationManager.IMPORTANCE_LOW);
    manager.createNotificationChannel(channel);
  }

  private void registerObservers() {
    final ContentResolver resolver;
    final Handler handler;

    unregisterObservers();
    resolver = getContentResolver();
    handler = new Handler(Looper.getMainLooper());

    this.smsObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("sms", selfChange, uri == null ? Telephony.Sms.CONTENT_URI : uri);
      }
    };

    this.mmsObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("mms", selfChange, uri == null ? Uri.parse("content://mms") : uri);
      }
    };

    this.conversationObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("threads", selfChange, uri == null ? Uri.parse("content://mms-sms/conversations") : uri);
      }
    };

    resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, this.smsObserver);
    resolver.registerContentObserver(Uri.parse("content://mms"), true, this.mmsObserver);
    resolver.registerContentObserver(Uri.parse("content://mms-sms/conversations"), true, this.conversationObserver);
  }

  private void unregisterObservers() {
    ContentResolver resolver;

    resolver = getContentResolver();

    if (this.smsObserver != null) {
      resolver.unregisterContentObserver(this.smsObserver);
      this.smsObserver = null;
    }

    if (this.mmsObserver != null) {
      resolver.unregisterContentObserver(this.mmsObserver);
      this.mmsObserver = null;
    }

    if (this.conversationObserver != null) {
      resolver.unregisterContentObserver(this.conversationObserver);
      this.conversationObserver = null;
    }
  }

  private void publishProviderChange(String source, boolean selfChange, Uri uri) {
    Sms.publishProviderChangeEvent(source, selfChange, uri, true);
  }
}
