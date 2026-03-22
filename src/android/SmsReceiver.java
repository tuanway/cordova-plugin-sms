package com.cordova.sms;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SmsReceiver extends BroadcastReceiver {
  private static final String MESSAGE_NOTIFICATION_CHANNEL_ID = "cordova_sms_messages";
  private static final int MESSAGE_NOTIFICATION_BASE_ID = 12000;
  private static final long MMS_PROVIDER_RECENCY_WINDOW_MS = 10L * 60L * 1000L;
  private static final long MMS_PROVIDER_REFRESH_INITIAL_DELAY_MS = 1200L;
  private static final long MMS_PROVIDER_REFRESH_RETRY_DELAY_MS = 1800L;
  private static final int MMS_PROVIDER_REFRESH_MAX_ATTEMPTS = 4;

  @Override
  public void onReceive(Context context, Intent intent) {
    String action;
    JSONObject event;
    Uri insertedUri;

    try {
      action = intent == null ? "" : intent.getAction();

      if (
        Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action) ||
        "android.provider.Telephony.SMS_DELIVER".equals(action)
      ) {
        event = buildIncomingSmsEvent(intent);
        if ("android.provider.Telephony.SMS_DELIVER".equals(action)) {
          insertedUri = Sms.persistIncomingSms(
            context,
            event.optString("address"),
            event.optString("body"),
            event.optLong("date", System.currentTimeMillis()),
            event.optInt("subscriptionId", -1)
          );
          if (insertedUri != null) {
            Sms.publishProviderChangeEvent("sms", false, insertedUri, false);
          }
        }
        if (shouldNotifyIncomingMessage(context, action)) {
          showIncomingNotification(context, event);
        }
        Sms.publishEvent(event);
        return;
      }

      if (
        "android.provider.Telephony.WAP_PUSH_RECEIVED".equals(action) ||
        "android.provider.Telephony.WAP_PUSH_DELIVER".equals(action)
      ) {
        int subscriptionId;
        long previousLatestProviderId;
        long receivedAt;
        BroadcastReceiver.PendingResult pendingResult;

        subscriptionId = readSubscriptionId(intent);
        previousLatestProviderId = queryLatestIncomingMmsProviderId(context);
        receivedAt = System.currentTimeMillis();
        event = buildIncomingMmsEvent(context, intent, previousLatestProviderId, receivedAt);
        if (shouldNotifyIncomingMessage(context, action)) {
          showIncomingNotification(context, event);
        }
        Sms.publishEvent(event);
        if (event.optLong("providerId", 0L) > 0L) {
          Sms.publishProviderChangeEvent(
            "mms",
            false,
            Uri.parse("content://mms/" + event.optLong("providerId")),
            false
          );
          return;
        }
        pendingResult = goAsync();
        scheduleIncomingMmsRefresh(
          context == null ? null : context.getApplicationContext(),
          subscriptionId,
          previousLatestProviderId,
          receivedAt,
          0,
          pendingResult
        );
        return;
      }

      if (Sms.BROADCAST_ACTION_SMS_SENT.equals(action)) {
        Sms.publishEvent(buildStatusEvent("smsSentStatus", intent, getResultCode()));
        return;
      }

      if (Sms.BROADCAST_ACTION_SMS_DELIVERED.equals(action)) {
        Sms.publishEvent(buildStatusEvent("smsDeliveryStatus", intent, getResultCode()));
        return;
      }

      if (Sms.BROADCAST_ACTION_MMS_SENT.equals(action)) {
        Sms.publishEvent(buildStatusEvent("mmsSentStatus", intent, getResultCode()));
      }
    } catch (Throwable ignored) {
    }
  }

  private boolean shouldNotifyIncomingMessage(Context context, String action) {
    if (context == null || Sms.isAppForeground()) {
      return false;
    }

    if (
      "android.provider.Telephony.SMS_DELIVER".equals(action) ||
      "android.provider.Telephony.WAP_PUSH_DELIVER".equals(action)
    ) {
      return true;
    }

    if (
      Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action) ||
      "android.provider.Telephony.WAP_PUSH_RECEIVED".equals(action)
    ) {
      return !isDefaultSmsPackage(context);
    }

    return false;
  }

  private boolean isDefaultSmsPackage(Context context) {
    String defaultPackage;

    if (context == null || Build.VERSION.SDK_INT < 19) {
      return true;
    }

    defaultPackage = Telephony.Sms.getDefaultSmsPackage(context);
    if (TextUtils.isEmpty(defaultPackage)) {
      return false;
    }

    return context.getPackageName().equals(defaultPackage);
  }

  private void showIncomingNotification(Context context, JSONObject event) {
    NotificationManager manager;
    Notification.Builder builder;
    PendingIntent contentIntent;
    String messageText;
    int notificationId;
    int iconId;

    if (context == null || event == null) {
      return;
    }

    manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }

    createNotificationChannel(manager);
    contentIntent = buildNotificationPendingIntent(context, event);
    messageText = buildNotificationText(event);
    notificationId = resolveNotificationId(event);
    iconId = context.getApplicationInfo().icon == 0 ? android.R.drawable.sym_action_chat : context.getApplicationInfo().icon;

    if (Build.VERSION.SDK_INT >= 26) {
      builder = new Notification.Builder(context, MESSAGE_NOTIFICATION_CHANNEL_ID);
    } else {
      builder = new Notification.Builder(context);
    }

    builder.setSmallIcon(iconId);
    builder.setContentTitle(buildNotificationTitle(event));
    builder.setContentText(messageText);
    builder.setAutoCancel(true);
    builder.setShowWhen(true);
    builder.setWhen(event.optLong("date", System.currentTimeMillis()));
    builder.setContentIntent(contentIntent);

    if (Build.VERSION.SDK_INT >= 16) {
      builder.setStyle(new Notification.BigTextStyle().bigText(messageText));
    }

    manager.notify(notificationId, builder.build());
  }

  private void createNotificationChannel(NotificationManager manager) {
    NotificationChannel channel;

    if (manager == null || Build.VERSION.SDK_INT < 26) {
      return;
    }

    channel = new NotificationChannel(
      MESSAGE_NOTIFICATION_CHANNEL_ID,
      "SMS Messages",
      NotificationManager.IMPORTANCE_HIGH
    );
    channel.setDescription("Incoming SMS and MMS messages");
    manager.createNotificationChannel(channel);
  }

  private PendingIntent buildNotificationPendingIntent(Context context, JSONObject event) {
    Intent intent;
    int flags;

    intent = new Intent(context, SmsComposeActivity.class);
    intent.setAction("com.cordova.sms.action.OPEN_THREAD_FROM_NOTIFICATION");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    intent.putExtra(SmsComposeActivity.EXTRA_LAUNCH_SOURCE, "notification");
    intent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_ACTION, "notification");
    intent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_ADDRESS, safeString(event.opt("address")));
    intent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_BODY, buildNotificationText(event));
    intent.putExtra(SmsComposeActivity.EXTRA_THREAD_KEY, safeString(event.opt("threadKey")));
    intent.putExtra(SmsComposeActivity.EXTRA_ADDRESSES, joinAddresses(event.optJSONArray("addresses")));
    intent.putExtra(SmsComposeActivity.EXTRA_NOTIFICATION_ID, resolveNotificationId(event));
    intent.putExtra(SmsComposeActivity.EXTRA_OPEN_THREAD, true);

    flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }

    return PendingIntent.getActivity(context, resolveNotificationId(event), intent, flags);
  }

  private int resolveNotificationId(JSONObject event) {
    String threadKey;
    String address;

    threadKey = safeString(event == null ? "" : event.opt("threadKey"));
    if (!TextUtils.isEmpty(threadKey)) {
      return MESSAGE_NOTIFICATION_BASE_ID + (threadKey.hashCode() & Integer.MAX_VALUE);
    }

    address = safeString(event == null ? "" : event.opt("address"));
    if (!TextUtils.isEmpty(address)) {
      return MESSAGE_NOTIFICATION_BASE_ID + (address.hashCode() & Integer.MAX_VALUE);
    }

    return MESSAGE_NOTIFICATION_BASE_ID;
  }

  private String buildNotificationTitle(JSONObject event) {
    String address;

    address = safeString(event == null ? "" : event.opt("address"));
    if (!TextUtils.isEmpty(address)) {
      return address;
    }

    return "New message";
  }

  private String buildNotificationText(JSONObject event) {
    String body;
    String subject;

    body = sanitizeNotificationText(safeString(event == null ? "" : event.opt("body")));
    if (!TextUtils.isEmpty(body)) {
      return body;
    }

    subject = sanitizeNotificationText(safeString(event == null ? "" : event.opt("subject")));
    if (!TextUtils.isEmpty(subject)) {
      return subject;
    }

    if (event != null && event.optBoolean("mms", false)) {
      return "New media message";
    }

    return "New message";
  }

  private String sanitizeNotificationText(String value) {
    if (TextUtils.isEmpty(value)) {
      return "";
    }

    return value.replace('\n', ' ').trim();
  }

  private String joinAddresses(JSONArray addresses) {
    StringBuilder builder;
    int index;
    String value;

    if (addresses == null || addresses.length() == 0) {
      return "";
    }

    builder = new StringBuilder();
    for (index = 0; index < addresses.length(); index++) {
      value = safeString(addresses.opt(index));
      if (TextUtils.isEmpty(value)) {
        continue;
      }

      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(value);
    }

    return builder.toString();
  }

  private JSONObject buildIncomingSmsEvent(Intent intent) {
    JSONObject event;
    SmsMessage[] messages;
    String address;
    StringBuilder body;
    long timestamp;
    int subscriptionId;
    int index;

    event = new JSONObject();
    messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    address = "";
    body = new StringBuilder();
    timestamp = System.currentTimeMillis();
    subscriptionId = readSubscriptionId(intent);

    if (messages != null && messages.length > 0) {
      address = messages[0].getDisplayOriginatingAddress();
      timestamp = messages[0].getTimestampMillis();

      for (index = 0; index < messages.length; index++) {
        if (messages[index] != null && messages[index].getMessageBody() != null) {
          body.append(messages[index].getMessageBody());
        }
      }
    }

    try {
      event.put("type", "incomingSms");
      event.put("action", intent == null ? "" : intent.getAction());
      event.put("address", address == null ? "" : address);
      event.put("body", body.toString());
      event.put("date", timestamp);
      event.put("subscriptionId", subscriptionId);
    } catch (JSONException ignored) {
    }

    return event;
  }

  private JSONObject buildIncomingMmsEvent(Context context, Intent intent, long previousLatestProviderId, long receivedAt) {
    JSONObject event;
    JSONObject latestMms;
    int subscriptionId;

    subscriptionId = readSubscriptionId(intent);
    latestMms = queryLatestMms(context, subscriptionId, previousLatestProviderId, receivedAt);
    if (latestMms != null) {
      return latestMms;
    }

    event = new JSONObject();
    try {
      event.put("type", "incomingMms");
      event.put("action", intent == null ? "" : intent.getAction());
      event.put("date", receivedAt > 0L ? receivedAt : System.currentTimeMillis());
      event.put("subscriptionId", subscriptionId);
    } catch (JSONException ignored) {
    }

    return event;
  }

  private long queryLatestIncomingMmsProviderId(Context context) {
    ContentResolver resolver;
    Cursor cursor;

    if (context == null) {
      return 0L;
    }

    resolver = context.getContentResolver();
    cursor = resolver.query(
      Uri.parse("content://mms"),
      new String[] { "_id" },
      "msg_box = 1",
      null,
      "date DESC"
    );

    if (cursor == null) {
      return 0L;
    }

    try {
      if (!cursor.moveToFirst()) {
        return 0L;
      }
      return cursor.getLong(cursor.getColumnIndex("_id"));
    } catch (Exception ignored) {
      return 0L;
    } finally {
      cursor.close();
    }
  }

  private JSONObject queryLatestMms(Context context, int subscriptionId, long previousLatestProviderId, long receivedAt) {
    ContentResolver resolver;
    Cursor cursor;
    long minAllowedDate;

    if (context == null) {
      return null;
    }

    minAllowedDate = receivedAt > 0L ? receivedAt - MMS_PROVIDER_RECENCY_WINDOW_MS : 0L;
    resolver = context.getContentResolver();
    cursor = resolver.query(
      Uri.parse("content://mms"),
      new String[] { "_id", "thread_id", "date", "date_sent", "msg_box", "read", "sub" },
      "msg_box = 1",
      null,
      "date DESC"
    );

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      if (cursor.getLong(cursor.getColumnIndex("_id")) == previousLatestProviderId) {
        return null;
      }

      if (minAllowedDate > 0L && normalizeMmsTimestamp(cursor.getLong(cursor.getColumnIndex("date"))) < minAllowedDate) {
        return null;
      }

      return buildIncomingMmsPayload(context, cursor, subscriptionId);
    } catch (Exception ignored) {
      return null;
    } finally {
      cursor.close();
    }
  }

  private void scheduleIncomingMmsRefresh(final Context context, final int subscriptionId, final long previousLatestProviderId, final long receivedAt, final int attempt, final BroadcastReceiver.PendingResult pendingResult) {
    long delayMs;

    delayMs = attempt <= 0 ? MMS_PROVIDER_REFRESH_INITIAL_DELAY_MS : MMS_PROVIDER_REFRESH_RETRY_DELAY_MS;
    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
      @Override
      public void run() {
        JSONObject refreshedEvent;
        long providerId;

        try {
          refreshedEvent = queryLatestMms(context, subscriptionId, previousLatestProviderId, receivedAt);
          if (refreshedEvent != null && refreshedEvent.optLong("providerId", 0L) > 0L) {
            providerId = refreshedEvent.optLong("providerId", 0L);
            Sms.publishEvent(refreshedEvent);
            Sms.publishProviderChangeEvent("mms", false, Uri.parse("content://mms/" + providerId), false);
            finishPendingResult(pendingResult);
            return;
          }

          if (attempt + 1 >= MMS_PROVIDER_REFRESH_MAX_ATTEMPTS) {
            Sms.publishProviderChangeEvent("mms", false, Uri.parse("content://mms"), false);
            finishPendingResult(pendingResult);
            return;
          }

          scheduleIncomingMmsRefresh(context, subscriptionId, previousLatestProviderId, receivedAt, attempt + 1, pendingResult);
        } catch (Throwable ignored) {
          finishPendingResult(pendingResult);
        }
      }
    }, delayMs);
  }

  private void finishPendingResult(BroadcastReceiver.PendingResult pendingResult) {
    if (pendingResult == null) {
      return;
    }
    try {
      pendingResult.finish();
    } catch (Exception ignored) {
    }
  }

  private JSONObject buildIncomingMmsPayload(Context context, Cursor cursor, int subscriptionId) throws JSONException {
    JSONObject event;
    long messageId;
    long threadId;
    long date;
    long dateSent;
    int msgBox;
    JSONObject parts;
    JSONArray addresses;

    messageId = cursor.getLong(cursor.getColumnIndex("_id"));
    threadId = cursor.getLong(cursor.getColumnIndex("thread_id"));
    date = normalizeMmsTimestamp(cursor.getLong(cursor.getColumnIndex("date")));
    dateSent = normalizeMmsTimestamp(cursor.getLong(cursor.getColumnIndex("date_sent")));
    msgBox = cursor.getInt(cursor.getColumnIndex("msg_box"));
    parts = loadMmsParts(context, messageId);
    addresses = loadMmsAddresses(context, messageId);

    event = new JSONObject();
    event.put("type", "incomingMms");
    event.put("id", "mms:" + messageId);
    event.put("providerId", messageId);
    event.put("threadKey", String.valueOf(threadId));
    event.put("threadId", threadId);
    event.put("kind", "mms");
    event.put("mms", true);
    event.put("body", parts.optString("body"));
    event.put("subject", safeString(cursor.getString(cursor.getColumnIndex("sub"))));
    event.put("addresses", addresses);
    event.put("address", addresses.length() > 0 ? addresses.optString(0) : "");
    event.put("attachments", parts.optJSONArray("attachments"));
    event.put("attachmentCount", parts.optJSONArray("attachments") == null ? 0 : parts.optJSONArray("attachments").length());
    event.put("date", date);
    event.put("dateSent", dateSent);
    event.put("sortDate", date > 0 ? date : dateSent);
    event.put("direction", msgBox == 1 ? "incoming" : "outgoing");
    event.put("box", msgBox);
    event.put("read", cursor.getInt(cursor.getColumnIndex("read")) == 1);
    event.put("subscriptionId", subscriptionId);
    return event;
  }

  private JSONObject loadMmsParts(Context context, long messageId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    JSONObject result;
    JSONArray attachments;
    StringBuilder bodyBuilder;

    resolver = context.getContentResolver();
    result = new JSONObject();
    attachments = new JSONArray();
    bodyBuilder = new StringBuilder();

    cursor = resolver.query(
      Uri.parse("content://mms/part"),
      new String[] { "_id", "ct", "name", "fn", "cl", "text", "_data" },
      "mid = ?",
      new String[] { String.valueOf(messageId) },
      null
    );

    if (cursor == null) {
      result.put("body", "");
      result.put("attachments", attachments);
      return result;
    }

    try {
      while (cursor.moveToNext()) {
        String partId;
        String contentType;
        String textValue;

        partId = safeString(cursor.getString(cursor.getColumnIndex("_id")));
        contentType = safeString(cursor.getString(cursor.getColumnIndex("ct")));
        textValue = safeString(cursor.getString(cursor.getColumnIndex("text")));

        if (contentType.startsWith("text/")) {
          if (!TextUtils.isEmpty(textValue)) {
            if (bodyBuilder.length() > 0) {
              bodyBuilder.append('\n');
            }

            bodyBuilder.append(textValue);
          }
        } else if (!"application/smil".equals(contentType)) {
          JSONObject attachment;

          attachment = new JSONObject();
          attachment.put("id", partId);
          attachment.put("uri", "content://mms/part/" + partId);
          attachment.put("contentType", contentType);
          attachment.put("name", firstNonEmpty(
            cursor.getString(cursor.getColumnIndex("fn")),
            cursor.getString(cursor.getColumnIndex("name")),
            cursor.getString(cursor.getColumnIndex("cl")),
            partId
          ));
          attachment.put("previewable", contentType.startsWith("image/"));
          attachments.put(attachment);
        }
      }
    } finally {
      cursor.close();
    }

    result.put("body", bodyBuilder.toString());
    result.put("attachments", attachments);
    return result;
  }

  private JSONArray loadMmsAddresses(Context context, long messageId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    JSONArray result;

    resolver = context.getContentResolver();
    result = new JSONArray();
    cursor = resolver.query(
      Uri.parse("content://mms/" + messageId + "/addr"),
      new String[] { "address" },
      null,
      null,
      null
    );

    if (cursor == null) {
      return result;
    }

    try {
      while (cursor.moveToNext()) {
        String address;

        address = safeString(cursor.getString(cursor.getColumnIndex("address")));
        if (TextUtils.isEmpty(address) || "insert-address-token".equals(address)) {
          continue;
        }

        result.put(address);
      }
    } finally {
      cursor.close();
    }

    return result;
  }

  private long normalizeMmsTimestamp(long value) {
    if (value <= 0L) {
      return value;
    }

    if (value < 100000000000L) {
      return value * 1000L;
    }

    return value;
  }

  private JSONObject buildStatusEvent(String type, Intent intent, int resultCode) {
    JSONObject event;

    event = new JSONObject();
    try {
      event.put("type", type);
      event.put("action", intent == null ? "" : intent.getAction());
      event.put("requestId", readStringExtra(intent, "requestId"));
      event.put("recipient", readStringExtra(intent, "recipient"));
      event.put("recipientIndex", readIntExtra(intent, "recipientIndex", -1));
      event.put("partIndex", readIntExtra(intent, "partIndex", -1));
      event.put("partCount", readIntExtra(intent, "partCount", 1));
      event.put("subscriptionId", readIntExtra(intent, "subscriptionId", -1));
      event.put("resultCode", resultCode);
      event.put("status", mapStatus(type, resultCode));
      event.put("timestamp", System.currentTimeMillis());
    } catch (JSONException ignored) {
    }

    return event;
  }

  private String mapStatus(String type, int resultCode) {
    if ("smsDeliveryStatus".equals(type)) {
      return resultCode == Activity.RESULT_OK ? "delivered" : "failed";
    }

    if (resultCode == Activity.RESULT_OK) {
      if ("mmsSentStatus".equals(type)) {
        return "sent";
      }

      return "sent";
    }

    if (resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
      return "generic_failure";
    }

    if (resultCode == SmsManager.RESULT_ERROR_NO_SERVICE) {
      return "no_service";
    }

    if (resultCode == SmsManager.RESULT_ERROR_NULL_PDU) {
      return "null_pdu";
    }

    if (resultCode == SmsManager.RESULT_ERROR_RADIO_OFF) {
      return "radio_off";
    }

    return "failed";
  }

  private int readSubscriptionId(Intent intent) {
    int value;

    value = readIntExtra(intent, "subscriptionId", -1);
    if (value > 0) {
      return value;
    }

    value = readIntExtra(intent, "subscription", -1);
    if (value > 0) {
      return value;
    }

    value = readIntExtra(intent, "subscription_id", -1);
    if (value > 0) {
      return value;
    }

    return readIntExtra(intent, "android.telephony.extra.SUBSCRIPTION_INDEX", -1);
  }

  private int readIntExtra(Intent intent, String key, int fallback) {
    Bundle extras;

    if (intent == null) {
      return fallback;
    }

    extras = intent.getExtras();
    if (extras == null || !extras.containsKey(key)) {
      return fallback;
    }

    try {
      return extras.getInt(key);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private String readStringExtra(Intent intent, String key) {
    Bundle extras;

    if (intent == null) {
      return "";
    }

    extras = intent.getExtras();
    if (extras == null || !extras.containsKey(key)) {
      return "";
    }

    try {
      return String.valueOf(extras.get(key));
    } catch (Exception ignored) {
      return "";
    }
  }

  private String safeString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String firstNonEmpty(String first, String second, String third, String fallback) {
    if (!TextUtils.isEmpty(first)) {
      return first;
    }

    if (!TextUtils.isEmpty(second)) {
      return second;
    }

    if (!TextUtils.isEmpty(third)) {
      return third;
    }

    return fallback;
  }
}
