package com.cordova.sms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action;
    JSONObject event;
    Uri insertedUri;

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
      Sms.publishEvent(event);
      return;
    }

    if (
      "android.provider.Telephony.WAP_PUSH_RECEIVED".equals(action) ||
      "android.provider.Telephony.WAP_PUSH_DELIVER".equals(action)
    ) {
      Sms.publishEvent(buildIncomingMmsEvent(context, intent));
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

  private JSONObject buildIncomingMmsEvent(Context context, Intent intent) {
    JSONObject event;
    JSONObject latestMms;

    latestMms = queryLatestMms(context, readSubscriptionId(intent));
    if (latestMms != null) {
      return latestMms;
    }

    event = new JSONObject();
    try {
      event.put("type", "incomingMms");
      event.put("action", intent == null ? "" : intent.getAction());
      event.put("date", System.currentTimeMillis());
      event.put("subscriptionId", readSubscriptionId(intent));
    } catch (JSONException ignored) {
    }

    return event;
  }

  private JSONObject queryLatestMms(Context context, int subscriptionId) {
    ContentResolver resolver;
    Cursor cursor;

    resolver = context.getContentResolver();
    cursor = resolver.query(
      Uri.parse("content://mms"),
      new String[] { "_id", "thread_id", "date", "date_sent", "msg_box", "read", "sub" },
      null,
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

      return buildIncomingMmsPayload(context, cursor, subscriptionId);
    } catch (Exception ignored) {
      return null;
    } finally {
      cursor.close();
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
