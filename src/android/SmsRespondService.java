package com.cordova.sms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.text.TextUtils;

import java.util.ArrayList;

public class SmsRespondService extends Service {
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String body;
    ArrayList<String> recipients;
    int index;

    body = SmsComposeActivity.extractBody(intent);
    recipients = extractRecipients(intent);

    if (!TextUtils.isEmpty(body) && recipients.size() > 0) {
      for (index = 0; index < recipients.size(); index++) {
        if (!TextUtils.isEmpty(recipients.get(index))) {
          SmsManager.getDefault().sendTextMessage(recipients.get(index), null, body, null, null);
        }
      }
    } else {
      launchCompose(intent);
    }

    stopSelf(startId);
    return START_NOT_STICKY;
  }

  private void launchCompose(Intent sourceIntent) {
    Intent launchIntent;

    launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (launchIntent == null) {
      return;
    }

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (sourceIntent != null) {
      if (sourceIntent.getExtras() != null) {
        launchIntent.putExtras(sourceIntent.getExtras());
      }
      if (sourceIntent.getData() != null) {
        launchIntent.setData(sourceIntent.getData());
      }
      launchIntent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_ACTION, sourceIntent.getAction());
      launchIntent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_DATA, sourceIntent.getDataString());
      launchIntent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_ADDRESS, SmsComposeActivity.extractAddress(sourceIntent));
      launchIntent.putExtra(SmsComposeActivity.EXTRA_EXTERNAL_BODY, SmsComposeActivity.extractBody(sourceIntent));
    }

    startActivity(launchIntent);
  }

  private ArrayList<String> extractRecipients(Intent intent) {
    ArrayList<String> recipients;
    String raw;
    String[] parts;
    String value;
    int index;

    recipients = new ArrayList<String>();
    raw = SmsComposeActivity.extractAddress(intent);
    if (TextUtils.isEmpty(raw)) {
      return recipients;
    }

    parts = raw.split("[;,]");
    for (index = 0; index < parts.length; index++) {
      value = parts[index] == null ? "" : parts[index].trim();
      if (!TextUtils.isEmpty(value)) {
        recipients.add(value);
      }
    }

    return recipients;
  }
}
