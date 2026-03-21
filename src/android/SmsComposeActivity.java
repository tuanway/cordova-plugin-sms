package com.cordova.sms;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

public class SmsComposeActivity extends Activity {
  public static final String EXTRA_EXTERNAL_ACTION = "com.cordova.sms.externalAction";
  public static final String EXTRA_EXTERNAL_DATA = "com.cordova.sms.externalData";
  public static final String EXTRA_EXTERNAL_ADDRESS = "com.cordova.sms.externalAddress";
  public static final String EXTRA_EXTERNAL_BODY = "com.cordova.sms.externalBody";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    forwardToLaunchActivity(this, getIntent());
    finish();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    forwardToLaunchActivity(this, intent);
    finish();
  }

  public static void forwardToLaunchActivity(Activity activity, Intent sourceIntent) {
    Intent launchIntent;
    Bundle extras;

    if (activity == null) {
      return;
    }

    launchIntent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
    if (launchIntent == null) {
      return;
    }

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

    if (sourceIntent != null) {
      extras = sourceIntent.getExtras();
      if (extras != null) {
        launchIntent.putExtras(new Bundle(extras));
      }
      if (sourceIntent.getData() != null) {
        launchIntent.setData(sourceIntent.getData());
      }
      launchIntent.putExtra(EXTRA_EXTERNAL_ACTION, safeString(sourceIntent.getAction()));
      launchIntent.putExtra(EXTRA_EXTERNAL_DATA, safeString(sourceIntent.getDataString()));
      launchIntent.putExtra(EXTRA_EXTERNAL_ADDRESS, extractAddress(sourceIntent));
      launchIntent.putExtra(EXTRA_EXTERNAL_BODY, extractBody(sourceIntent));
    }

    activity.startActivity(launchIntent);
  }

  public static String extractAddress(Intent intent) {
    Uri data;
    String address;
    int queryIndex;

    if (intent == null) {
      return "";
    }

    data = intent.getData();
    if (data == null) {
      return "";
    }

    address = safeString(data.getSchemeSpecificPart());
    queryIndex = address.indexOf('?');
    if (queryIndex >= 0) {
      address = address.substring(0, queryIndex);
    }

    return address;
  }

  public static String extractBody(Intent intent) {
    String body;

    if (intent == null) {
      return "";
    }

    body = safeString(intent.getStringExtra("sms_body"));
    if (!TextUtils.isEmpty(body)) {
      return body;
    }

    body = safeString(intent.getStringExtra(Intent.EXTRA_TEXT));
    if (!TextUtils.isEmpty(body)) {
      return body;
    }

    body = safeString(intent.getStringExtra("android.intent.extra.TEXT"));
    if (!TextUtils.isEmpty(body)) {
      return body;
    }

    return "";
  }

  private static String safeString(String value) {
    return value == null ? "" : value;
  }
}
