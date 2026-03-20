package com.cordova.sms;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Sms extends CordovaPlugin {
  private static final String ACTION_HAS_PERMISSIONS = "hasPermissions";
  private static final String ACTION_REQUEST_PERMISSIONS = "requestPermissions";
  private static final String ACTION_IS_DEFAULT_SMS_APP = "isDefaultSmsApp";
  private static final String ACTION_REQUEST_DEFAULT_SMS_APP = "requestDefaultSmsApp";
  private static final String ACTION_GET_SUBSCRIPTIONS = "getSubscriptions";
  private static final String ACTION_WATCH = "watch";
  private static final String ACTION_UNWATCH = "unwatch";
  private static final String ACTION_START_BACKGROUND_WATCH = "startBackgroundWatch";
  private static final String ACTION_STOP_BACKGROUND_WATCH = "stopBackgroundWatch";
  private static final String ACTION_GET_BACKGROUND_WATCH_STATE = "getBackgroundWatchState";
  private static final String ACTION_LIST_THREADS = "listThreads";
  private static final String ACTION_SEARCH_THREADS = "searchThreads";
  private static final String ACTION_LIST_MESSAGES = "listMessages";
  private static final String ACTION_SEARCH_MESSAGES = "searchMessages";
  private static final String ACTION_GET_THREAD = "getThread";
  private static final String ACTION_SEND_SMS = "sendSms";
  private static final String ACTION_SEND_MMS = "sendMms";
  private static final String ACTION_MARK_READ = "markRead";
  private static final String ACTION_MARK_UNREAD = "markUnread";
  private static final String ACTION_DELETE_THREAD = "deleteThread";
  private static final String ACTION_DELETE_MESSAGE = "deleteMessage";
  private static final String ACTION_RESTORE_MESSAGE = "restoreMessage";
  private static final String ACTION_SAVE_DRAFT = "saveDraft";
  private static final String ACTION_LIST_DRAFTS = "listDrafts";
  private static final String ACTION_UPDATE_DRAFT = "updateDraft";
  private static final String ACTION_DELETE_DRAFT = "deleteDraft";
  private static final String ACTION_MOVE_TO_DRAFT = "moveToDraft";
  private static final String ACTION_RESEND_FAILED = "resendFailed";
  private static final String ACTION_GET_CONTACTS = "getContacts";
  private static final String ACTION_RESOLVE_ADDRESSES = "resolveAddresses";
  private static final String ACTION_EXPORT_ATTACHMENT = "exportAttachment";
  private static final String ACTION_CREATE_ATTACHMENT_THUMBNAIL = "createAttachmentThumbnail";
  private static final String ACTION_GET_THREAD_SETTINGS = "getThreadSettings";
  private static final String ACTION_SET_THREAD_SETTINGS = "setThreadSettings";

  public static final String BROADCAST_ACTION_SMS_SENT = "com.cordova.sms.SMS_SENT";
  public static final String BROADCAST_ACTION_SMS_DELIVERED = "com.cordova.sms.SMS_DELIVERED";
  public static final String BROADCAST_ACTION_MMS_SENT = "com.cordova.sms.MMS_SENT";

  private static final int REQUEST_SMS_PERMISSIONS = 9100;
  private static final int REQUEST_DEFAULT_SMS_APP = 9101;

  public static final String PREFS_NAME = "com.cordova.sms";
  public static final String PREF_KEY_DELETED_SMS = "deletedSms";
  public static final String PREF_KEY_THREAD_SETTINGS = "threadSettings";
  public static final String PREF_KEY_BACKGROUND_WATCH = "backgroundWatch";
  public static final String PREF_KEY_BACKGROUND_WATCH_OPTIONS = "backgroundWatchOptions";

  private static final String[] READ_PERMISSIONS = new String[] {
    Manifest.permission.READ_SMS
  };

  private static final String[] SEND_PERMISSIONS = new String[] {
    Manifest.permission.SEND_SMS
  };

  private static final String[] PHONE_STATE_PERMISSIONS = new String[] {
    Manifest.permission.READ_PHONE_STATE
  };

  private static final String[] CONTACTS_PERMISSIONS = new String[] {
    Manifest.permission.READ_CONTACTS
  };

  private static final String[] REQUIRED_PERMISSIONS = new String[] {
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.RECEIVE_MMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CONTACTS
  };

  private static final Object EVENT_LOCK = new Object();
  private static final ArrayList<JSONObject> PENDING_EVENTS = new ArrayList<JSONObject>();
  private static Sms activeInstance;

  private CallbackContext permissionCallbackContext;
  private CallbackContext defaultSmsAppCallbackContext;
  private CallbackContext watchCallbackContext;
  private ContentObserver smsObserver;
  private ContentObserver mmsObserver;
  private ContentObserver conversationObserver;
  private String lastThreadChangeKey;
  private String lastSmsChangeKey;
  private String lastMmsChangeKey;
  private final HashMap<String, JSONObject> messageSnapshots = new HashMap<String, JSONObject>();
  private final HashMap<String, JSONObject> threadSnapshots = new HashMap<String, JSONObject>();

  @Override
  protected void pluginInitialize() {
    super.pluginInitialize();

    synchronized (EVENT_LOCK) {
      activeInstance = this;
    }
  }

  @Override
  public void onReset() {
    stopWatchingInternal();

    synchronized (EVENT_LOCK) {
      if (activeInstance == this) {
        activeInstance = null;
      }
    }

    super.onReset();
  }

  @Override
  public void onDestroy() {
    stopWatchingInternal();

    synchronized (EVENT_LOCK) {
      if (activeInstance == this) {
        activeInstance = null;
      }
    }

    super.onDestroy();
  }

  public static void publishEvent(JSONObject event) {
    Sms instance;

    synchronized (EVENT_LOCK) {
      instance = activeInstance;
      if (instance == null) {
        PENDING_EVENTS.add(copyJson(event));
        return;
      }
    }

    instance.dispatchOrQueueEvent(event);
  }

  @Override
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
    if (ACTION_HAS_PERMISSIONS.equals(action)) {
      callbackContext.success(buildPermissionState());
      return true;
    }

    if (ACTION_REQUEST_PERMISSIONS.equals(action)) {
      requestPermissions(callbackContext);
      return true;
    }

    if (ACTION_IS_DEFAULT_SMS_APP.equals(action)) {
      callbackContext.success(buildDefaultSmsAppState());
      return true;
    }

    if (ACTION_REQUEST_DEFAULT_SMS_APP.equals(action)) {
      requestDefaultSmsApp(callbackContext);
      return true;
    }

    if (ACTION_GET_SUBSCRIPTIONS.equals(action)) {
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleGetSubscriptions(callbackContext);
        }
      });
      return true;
    }

    if (ACTION_WATCH.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          handleWatch(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_UNWATCH.equals(action)) {
      cordova.getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          handleUnwatch(callbackContext);
        }
      });
      return true;
    }

    if (ACTION_START_BACKGROUND_WATCH.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          handleStartBackgroundWatch(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_STOP_BACKGROUND_WATCH.equals(action)) {
      cordova.getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          handleStopBackgroundWatch(callbackContext);
        }
      });
      return true;
    }

    if (ACTION_GET_BACKGROUND_WATCH_STATE.equals(action)) {
      callbackContext.success(buildBackgroundWatchState());
      return true;
    }

    if (ACTION_LIST_THREADS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleListThreads(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_SEARCH_THREADS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleSearchThreads(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_LIST_MESSAGES.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleListMessages(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_SEARCH_MESSAGES.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleSearchMessages(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_GET_THREAD.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleGetThread(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_SEND_SMS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleSendSms(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_SEND_MMS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleSendMms(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_MARK_READ.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleMarkRead(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_MARK_UNREAD.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleMarkUnread(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_DELETE_THREAD.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleDeleteThread(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_DELETE_MESSAGE.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleDeleteMessage(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_RESTORE_MESSAGE.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleRestoreMessage(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_SAVE_DRAFT.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleSaveDraft(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_LIST_DRAFTS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleListDrafts(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_UPDATE_DRAFT.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleUpdateDraft(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_DELETE_DRAFT.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleDeleteDraft(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_MOVE_TO_DRAFT.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleMoveToDraft(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_RESEND_FAILED.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleResendFailed(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_GET_CONTACTS.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleGetContacts(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_RESOLVE_ADDRESSES.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleResolveAddresses(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_EXPORT_ATTACHMENT.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleExportAttachment(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_CREATE_ATTACHMENT_THUMBNAIL.equals(action)) {
      final JSONObject options = getOptions(args);
      cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          handleCreateAttachmentThumbnail(options, callbackContext);
        }
      });
      return true;
    }

    if (ACTION_GET_THREAD_SETTINGS.equals(action)) {
      final JSONObject options = getOptions(args);
      callbackContext.success(getThreadSettings(options));
      return true;
    }

    if (ACTION_SET_THREAD_SETTINGS.equals(action)) {
      final JSONObject options = getOptions(args);
      callbackContext.success(setThreadSettings(options));
      return true;
    }

    return false;
  }

  @Override
  public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
    if (requestCode != REQUEST_SMS_PERMISSIONS) {
      return;
    }

    if (this.permissionCallbackContext == null) {
      return;
    }

    this.permissionCallbackContext.success(buildPermissionState());
    this.permissionCallbackContext = null;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (requestCode != REQUEST_DEFAULT_SMS_APP) {
      super.onActivityResult(requestCode, resultCode, intent);
      return;
    }

    if (this.defaultSmsAppCallbackContext == null) {
      return;
    }

    this.defaultSmsAppCallbackContext.success(buildDefaultSmsAppState());
    this.defaultSmsAppCallbackContext = null;
  }

  private JSONObject getOptions(JSONArray args) {
    if (args == null || args.length() == 0 || args.isNull(0)) {
      return new JSONObject();
    }

    return args.optJSONObject(0) == null ? new JSONObject() : args.optJSONObject(0);
  }

  private void requestPermissions(CallbackContext callbackContext) {
    if (hasPermissions(REQUIRED_PERMISSIONS)) {
      callbackContext.success(buildPermissionState());
      return;
    }

    this.permissionCallbackContext = callbackContext;
    PermissionHelper.requestPermissions(this, REQUEST_SMS_PERMISSIONS, REQUIRED_PERMISSIONS);
  }

  private void requestDefaultSmsApp(CallbackContext callbackContext) {
    Intent intent;

    if (isDefaultSmsApp()) {
      callbackContext.success(buildDefaultSmsAppState());
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= 29) {
        RoleManager roleManager;

        roleManager = (RoleManager) cordova.getActivity().getSystemService(RoleManager.class);
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
          this.defaultSmsAppCallbackContext = callbackContext;
          intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
          cordova.startActivityForResult(this, intent, REQUEST_DEFAULT_SMS_APP);
          return;
        }
      }

      if (Build.VERSION.SDK_INT >= 19) {
        this.defaultSmsAppCallbackContext = callbackContext;
        intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, cordova.getActivity().getPackageName());
        cordova.startActivityForResult(this, intent, REQUEST_DEFAULT_SMS_APP);
        return;
      }

      callbackContext.success(buildDefaultSmsAppState());
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private boolean hasPermissions(String[] permissions) {
    int index;

    for (index = 0; index < permissions.length; index++) {
      if (!PermissionHelper.hasPermission(this, permissions[index])) {
        return false;
      }
    }

    return true;
  }

  private JSONObject buildPermissionState() {
    JSONObject result;
    boolean readSms;
    boolean receiveSms;
    boolean receiveMms;
    boolean sendSms;
    boolean readPhoneState;
    boolean readContacts;

    result = new JSONObject();
    readSms = PermissionHelper.hasPermission(this, Manifest.permission.READ_SMS);
    receiveSms = PermissionHelper.hasPermission(this, Manifest.permission.RECEIVE_SMS);
    receiveMms = PermissionHelper.hasPermission(this, Manifest.permission.RECEIVE_MMS);
    sendSms = PermissionHelper.hasPermission(this, Manifest.permission.SEND_SMS);
    readPhoneState = PermissionHelper.hasPermission(this, Manifest.permission.READ_PHONE_STATE);
    readContacts = PermissionHelper.hasPermission(this, Manifest.permission.READ_CONTACTS);

    try {
      result.put("readSms", readSms);
      result.put("receiveSms", receiveSms);
      result.put("receiveMms", receiveMms);
      result.put("sendSms", sendSms);
      result.put("readPhoneState", readPhoneState);
      result.put("readContacts", readContacts);
      result.put("all", readSms && receiveSms && receiveMms && sendSms && readPhoneState && readContacts);
    } catch (JSONException ignored) {
    }

    return result;
  }

  private JSONObject buildDefaultSmsAppState() {
    JSONObject result;
    String packageName;
    String defaultPackage;

    result = new JSONObject();
    packageName = cordova.getActivity().getPackageName();
    defaultPackage = getDefaultSmsPackage();

    try {
      result.put("packageName", packageName);
      result.put("defaultPackage", defaultPackage);
      result.put("isDefault", packageName.equals(defaultPackage));
    } catch (JSONException ignored) {
    }

    return result;
  }

  private String getDefaultSmsPackage() {
    if (Build.VERSION.SDK_INT < 19) {
      return cordova.getActivity().getPackageName();
    }

    return safeString(Telephony.Sms.getDefaultSmsPackage(cordova.getActivity()));
  }

  private boolean isDefaultSmsApp() {
    return cordova.getActivity().getPackageName().equals(getDefaultSmsPackage());
  }

  private void handleGetSubscriptions(CallbackContext callbackContext) {
    if (Build.VERSION.SDK_INT < 22) {
      callbackContext.success(new JSONArray());
      return;
    }

    if (!hasPermissions(PHONE_STATE_PERMISSIONS)) {
      callbackContext.error("READ_PHONE_STATE permission is required.");
      return;
    }

    try {
      callbackContext.success(listSubscriptions());
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleStartBackgroundWatch(JSONObject options, CallbackContext callbackContext) {
    Intent intent;
    JSONObject normalizedOptions;

    try {
      normalizedOptions = options == null ? new JSONObject() : new JSONObject(options.toString());
      intent = SmsWatchService.buildStartIntent(cordova.getActivity(), normalizedOptions);
      if (Build.VERSION.SDK_INT >= 26) {
        cordova.getActivity().startForegroundService(intent);
      } else {
        cordova.getActivity().startService(intent);
      }

      writeBooleanPref(PREF_KEY_BACKGROUND_WATCH, true);
      writeJsonPref(PREF_KEY_BACKGROUND_WATCH_OPTIONS, normalizedOptions);
      callbackContext.success(buildBackgroundWatchState());
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleStopBackgroundWatch(CallbackContext callbackContext) {
    try {
      cordova.getActivity().startService(SmsWatchService.buildStopIntent(cordova.getActivity()));
      writeBooleanPref(PREF_KEY_BACKGROUND_WATCH, false);
      writeJsonPref(PREF_KEY_BACKGROUND_WATCH_OPTIONS, new JSONObject());
      callbackContext.success(buildBackgroundWatchState());
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private JSONObject buildBackgroundWatchState() {
    JSONObject result;

    result = new JSONObject();
    try {
      result.put("enabled", readBooleanPref(PREF_KEY_BACKGROUND_WATCH, false));
      result.put("options", readJsonPref(PREF_KEY_BACKGROUND_WATCH_OPTIONS));
    } catch (JSONException ignored) {
    }

    return result;
  }

  private void handleWatch(JSONObject options, CallbackContext callbackContext) {
    PluginResult result;

    stopWatchingInternal();
    this.watchCallbackContext = callbackContext;

    result = new PluginResult(PluginResult.Status.NO_RESULT);
    result.setKeepCallback(true);
    callbackContext.sendPluginResult(result);

    registerContentObservers();
    flushPendingEvents();
  }

  private void handleUnwatch(CallbackContext callbackContext) {
    stopWatchingInternal();
    callbackContext.success(buildWatchingState(false));
  }

  private JSONObject buildWatchingState(boolean watching) {
    JSONObject result;

    result = new JSONObject();
    try {
      result.put("watching", watching);
    } catch (JSONException ignored) {
    }

    return result;
  }

  private void stopWatchingInternal() {
    CallbackContext callbackContext;
    PluginResult result;

    unregisterContentObservers();
    resetChangeTracking();

    callbackContext = null;
    synchronized (EVENT_LOCK) {
      if (this.watchCallbackContext != null) {
        callbackContext = this.watchCallbackContext;
        this.watchCallbackContext = null;
      }
    }

    if (callbackContext != null) {
      result = new PluginResult(PluginResult.Status.NO_RESULT);
      result.setKeepCallback(false);
      callbackContext.sendPluginResult(result);
    }
  }

  private void resetChangeTracking() {
    this.lastThreadChangeKey = null;
    this.lastSmsChangeKey = null;
    this.lastMmsChangeKey = null;
    this.messageSnapshots.clear();
    this.threadSnapshots.clear();
  }

  private void registerContentObservers() {
    final ContentResolver resolver;
    final Handler handler;

    unregisterContentObservers();

    resolver = cordova.getActivity().getContentResolver();
    handler = new Handler(Looper.getMainLooper());

    this.smsObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("sms", selfChange, uri == null ? Telephony.Sms.CONTENT_URI : uri);
      }

      @Override
      public void onChange(boolean selfChange) {
        onChange(selfChange, Telephony.Sms.CONTENT_URI);
      }
    };

    this.mmsObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("mms", selfChange, uri == null ? Uri.parse("content://mms") : uri);
      }

      @Override
      public void onChange(boolean selfChange) {
        onChange(selfChange, Uri.parse("content://mms"));
      }
    };

    this.conversationObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        publishProviderChange("threads", selfChange, uri == null ? Uri.parse("content://mms-sms/conversations") : uri);
      }

      @Override
      public void onChange(boolean selfChange) {
        onChange(selfChange, Uri.parse("content://mms-sms/conversations"));
      }
    };

    resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, this.smsObserver);
    resolver.registerContentObserver(Uri.parse("content://mms"), true, this.mmsObserver);
    resolver.registerContentObserver(Uri.parse("content://mms-sms/conversations"), true, this.conversationObserver);
  }

  private void unregisterContentObservers() {
    ContentResolver resolver;

    resolver = cordova.getActivity().getContentResolver();

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
    publishProviderChangeEvent(source, selfChange, uri, false);
  }

  public static void publishProviderChangeEvent(String source, boolean selfChange, Uri uri, boolean background) {
    JSONObject event;
    Sms instance;

    event = new JSONObject();
    try {
      event.put("type", "providerChanged");
      event.put("source", source);
      event.put("selfChange", selfChange);
      event.put("uri", uri == null ? "" : uri.toString());
      event.put("timestamp", System.currentTimeMillis());
      event.put("background", background);
    } catch (JSONException ignored) {
    }

    synchronized (EVENT_LOCK) {
      instance = activeInstance;
      if (instance == null) {
        PENDING_EVENTS.add(copyJson(event));
        return;
      }
    }

    instance.dispatchOrQueueEvent(event);
    instance.publishDetailedProviderEvents(source, uri, background);
  }

  private void publishDetailedProviderEvents(String source, Uri uri, boolean background) {
    JSONObject message;
    JSONObject threadReferenceMessage;

    message = null;
    threadReferenceMessage = null;
    try {
      if ("sms".equals(source) || "mms".equals(source)) {
        message = queryChangedMessageForSource(source, uri);
        if (message != null) {
          threadReferenceMessage = copyJson(message);
        } else {
          threadReferenceMessage = copyJson(this.messageSnapshots.get(resolveMessageSnapshotKey(source, uri, null)));
        }

        emitMessageChange(source, uri, message, background);
      }

      emitThreadChange(source, uri, queryChangedThreadForSource(source, uri, threadReferenceMessage), threadReferenceMessage, background);
    } catch (Exception ignored) {
    }
  }

  private JSONObject queryChangedMessageForSource(String source, Uri uri) throws JSONException {
    long providerId;

    if (!"sms".equals(source) && !"mms".equals(source)) {
      return null;
    }

    providerId = parseProviderIdFromUri(uri);
    if (providerId > 0L) {
      return querySingleMessage(source, providerId);
    }

    return queryLatestMessageForSource(source);
  }

  private JSONObject queryChangedThreadForSource(String source, Uri uri, JSONObject message) throws JSONException {
    long threadId;

    threadId = 0L;
    if ("sms".equals(source) || "mms".equals(source)) {
      if (message != null) {
        threadId = parseThreadId(message.optString("threadKey"));
      }
    } else {
      threadId = parseProviderIdFromUri(uri);
    }

    if (threadId > 0L) {
      return querySingleThread(threadId, new JSONObject());
    }

    return queryLatestThreadChange();
  }

  private JSONObject queryLatestMessageForSource(String source) throws JSONException {
    List<JSONObject> items;

    if ("sms".equals(source)) {
      items = querySmsMessages(0L);
    } else if ("mms".equals(source)) {
      items = queryMmsMessages(0L);
    } else {
      return null;
    }

    return items.isEmpty() ? null : items.get(0);
  }

  private JSONObject queryLatestThreadChange() throws JSONException {
    List<JSONObject> items;

    items = queryThreadItems(new JSONObject().put("limit", 1));
    return items.isEmpty() ? null : items.get(0);
  }

  private void emitMessageChange(String source, Uri uri, JSONObject message, boolean background) throws JSONException {
    JSONObject event;
    JSONObject previousMessage;
    JSONObject payload;
    String changeType;
    String eventKey;
    String messageId;

    messageId = resolveMessageSnapshotKey(source, uri, message);
    if (TextUtils.isEmpty(messageId)) {
      return;
    }

    previousMessage = this.messageSnapshots.get(messageId);
    payload = message == null ? copyJson(previousMessage) : copyJson(message);
    changeType = resolveChangeType(previousMessage, message, buildMessageSnapshotSignature(previousMessage), buildMessageSnapshotSignature(message));
    if (TextUtils.isEmpty(changeType)) {
      return;
    }

    eventKey = messageId + "|" + changeType + "|" + buildMessageSnapshotSignature(payload);
    if ("sms".equals(source) && eventKey.equals(this.lastSmsChangeKey)) {
      return;
    }

    if ("mms".equals(source) && eventKey.equals(this.lastMmsChangeKey)) {
      return;
    }

    if ("deleted".equals(changeType)) {
      this.messageSnapshots.remove(messageId);
    } else {
      this.messageSnapshots.put(messageId, copyJson(message));
    }

    if ("sms".equals(source)) {
      this.lastSmsChangeKey = eventKey;
    } else if ("mms".equals(source)) {
      this.lastMmsChangeKey = eventKey;
    }

    event = new JSONObject();
    event.put("type", "messageChanged");
    event.put("source", source);
    event.put("changeType", changeType);
    event.put("background", background);
    event.put("message", payload);
    event.put("messageId", payload.optString("id"));
    event.put("providerId", payload.optLong("providerId", parseProviderIdFromUri(uri)));
    event.put("kind", payload.optString("kind", source));
    event.put("threadKey", payload.optString("threadKey"));
    event.put("timestamp", System.currentTimeMillis());
    dispatchOrQueueEvent(event);
  }

  private void emitThreadChange(String source, Uri uri, JSONObject thread, JSONObject relatedMessage, boolean background) throws JSONException {
    JSONObject event;
    JSONObject previousThread;
    JSONObject payload;
    String changeType;
    String eventKey;
    String threadKey;

    threadKey = resolveThreadSnapshotKey(uri, thread, relatedMessage);
    if (TextUtils.isEmpty(threadKey)) {
      return;
    }

    previousThread = this.threadSnapshots.get(threadKey);
    payload = thread == null ? copyJson(previousThread) : copyJson(thread);
    changeType = resolveChangeType(previousThread, thread, buildThreadSnapshotSignature(previousThread), buildThreadSnapshotSignature(thread));
    if (TextUtils.isEmpty(changeType)) {
      return;
    }

    eventKey = threadKey + "|" + changeType + "|" + buildThreadSnapshotSignature(payload);
    if (eventKey.equals(this.lastThreadChangeKey)) {
      return;
    }

    if ("deleted".equals(changeType)) {
      this.threadSnapshots.remove(threadKey);
    } else {
      this.threadSnapshots.put(threadKey, copyJson(thread));
    }

    this.lastThreadChangeKey = eventKey;
    event = new JSONObject();
    event.put("type", "threadChanged");
    event.put("source", source);
    event.put("changeType", changeType);
    event.put("background", background);
    event.put("thread", payload);
    event.put("threadKey", threadKey);
    event.put("timestamp", System.currentTimeMillis());
    dispatchOrQueueEvent(event);
  }

  private void dispatchOrQueueEvent(JSONObject event) {
    CallbackContext callbackContext;

    callbackContext = null;
    synchronized (EVENT_LOCK) {
      if (this.watchCallbackContext == null) {
        PENDING_EVENTS.add(copyJson(event));
        return;
      }

      callbackContext = this.watchCallbackContext;
    }

    sendEvent(callbackContext, event);
  }

  private void flushPendingEvents() {
    ArrayList<JSONObject> pendingEvents;
    CallbackContext callbackContext;
    int index;

    pendingEvents = new ArrayList<JSONObject>();
    callbackContext = null;

    synchronized (EVENT_LOCK) {
      if (this.watchCallbackContext == null || PENDING_EVENTS.isEmpty()) {
        return;
      }

      callbackContext = this.watchCallbackContext;
      pendingEvents.addAll(PENDING_EVENTS);
      PENDING_EVENTS.clear();
    }

    for (index = 0; index < pendingEvents.size(); index++) {
      sendEvent(callbackContext, pendingEvents.get(index));
    }
  }

  private void sendEvent(CallbackContext callbackContext, JSONObject event) {
    PluginResult result;

    result = new PluginResult(PluginResult.Status.OK, event);
    result.setKeepCallback(true);
    callbackContext.sendPluginResult(result);
  }

  private void handleListThreads(JSONObject options, CallbackContext callbackContext) {
    JSONArray threads;

    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    try {
      threads = queryThreads(options);
      callbackContext.success(threads);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSearchThreads(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    try {
      callbackContext.success(buildPagedResult(queryThreadItems(options), options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleListMessages(JSONObject options, CallbackContext callbackContext) {
    JSONArray messages;

    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    try {
      messages = queryMessages(options);
      callbackContext.success(messages);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSearchMessages(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    try {
      callbackContext.success(buildPagedResult(queryMessageItems(options), options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleGetThread(JSONObject options, CallbackContext callbackContext) {
    JSONObject result;
    String threadKey;

    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    threadKey = safeString(options.opt("threadKey"));
    if (TextUtils.isEmpty(threadKey)) {
      callbackContext.error("threadKey is required.");
      return;
    }

    try {
      result = buildThreadDetails(threadKey, options);
      callbackContext.success(result);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSendSms(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(SEND_PERMISSIONS)) {
      callbackContext.error("SEND_SMS permission is required.");
      return;
    }

    try {
      callbackContext.success(sendSmsInternal(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSendMms(JSONObject options, CallbackContext callbackContext) {
    String pduUri;
    String locationUrl;
    String requestId;
    int subscriptionId;
    Bundle configOverrides;
    SmsManager smsManager;

    pduUri = safeString(options.opt("pduUri"));
    if (TextUtils.isEmpty(pduUri) || Build.VERSION.SDK_INT < 21) {
      handleSendMmsIntent(options, callbackContext);
      return;
    }

    if (!hasPermissions(SEND_PERMISSIONS)) {
      callbackContext.error("SEND_SMS permission is required.");
      return;
    }

    requestId = firstNonEmpty(safeString(options.opt("requestId")), UUID.randomUUID().toString());
    subscriptionId = resolveRequestedSubscriptionId(options);
    locationUrl = safeString(options.opt("locationUrl"));
    configOverrides = jsonToBundle(options.optJSONObject("configOverrides"));

    try {
      smsManager = createSmsManager(subscriptionId);
      smsManager.sendMultimediaMessage(
        cordova.getActivity(),
        Uri.parse(pduUri),
        TextUtils.isEmpty(locationUrl) ? null : locationUrl,
        configOverrides,
        buildStatusPendingIntent(BROADCAST_ACTION_MMS_SENT, requestId, "", 0, 0, 1, subscriptionId)
      );
      callbackContext.success(buildSendResult("mms", 1, 0, requestId, subscriptionId));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSendMmsIntent(JSONObject options, CallbackContext callbackContext) {
    JSONArray attachments;
    List<String> recipients;
    String message;
    Intent intent;
    ArrayList<Uri> streams;
    int index;
    int subscriptionId;

    attachments = options.optJSONArray("attachments");
    recipients = normalizeRecipients(options.optJSONArray("recipients"));
    message = safeString(options.opt("message"));
    streams = new ArrayList<Uri>();
    subscriptionId = resolveRequestedSubscriptionId(options);

    if (attachments != null) {
      for (index = 0; index < attachments.length(); index++) {
        JSONObject attachment;
        String uriValue;

        attachment = attachments.optJSONObject(index);
        if (attachment == null) {
          continue;
        }

        uriValue = safeString(attachment.opt("uri"));
        if (TextUtils.isEmpty(uriValue)) {
          continue;
        }

        streams.add(Uri.parse(uriValue));
      }
    }

    intent = buildMmsIntent(recipients, message, streams, attachments, subscriptionId);

    try {
      cordova.getActivity().startActivity(intent);
      callbackContext.success(buildSendResult("mms_intent", recipients.size(), streams.size(), "", subscriptionId));
    } catch (ActivityNotFoundException exception) {
      callbackContext.error("No compatible messaging activity found.");
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleMarkRead(JSONObject options, CallbackContext callbackContext) {
    boolean read;
    JSONObject result;

    if (!ensureWriteAccess(callbackContext, "markRead")) {
      return;
    }

    read = options.optBoolean("read", true);

    try {
      result = markRead(options, read);
      callbackContext.success(result);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleMarkUnread(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "markUnread")) {
      return;
    }

    try {
      callbackContext.success(markRead(options, false));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleDeleteThread(JSONObject options, CallbackContext callbackContext) {
    long threadId;
    int deletedRows;
    JSONObject result;

    if (!ensureWriteAccess(callbackContext, "deleteThread")) {
      return;
    }

    threadId = parseThreadId(options);
    if (threadId <= 0L) {
      callbackContext.error("threadKey is required.");
      return;
    }

    try {
      deletedRows = cordova.getActivity().getContentResolver().delete(
        Uri.parse("content://mms-sms/conversations/" + threadId),
        null,
        null
      );

      result = new JSONObject();
      result.put("threadKey", String.valueOf(threadId));
      result.put("deletedRows", deletedRows);
      callbackContext.success(result);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleDeleteMessage(JSONObject options, CallbackContext callbackContext) {
    MessageReference reference;
    int deletedRows;
    JSONObject result;
    String backupId;

    if (!ensureWriteAccess(callbackContext, "deleteMessage")) {
      return;
    }

    try {
      reference = parseMessageReference(options);
      backupId = "";
      if ("sms".equals(reference.kind)) {
        backupId = backupDeletedSms(reference.providerId);
      }

      deletedRows = cordova.getActivity().getContentResolver().delete(reference.uri, null, null);
      result = new JSONObject();
      result.put("messageId", reference.messageId);
      result.put("kind", reference.kind);
      result.put("deletedRows", deletedRows);
      result.put("backupId", backupId);
      callbackContext.success(result);
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleRestoreMessage(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "restoreMessage")) {
      return;
    }

    try {
      callbackContext.success(restoreDeletedSms(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleSaveDraft(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "saveDraft")) {
      return;
    }

    try {
      callbackContext.success(saveDraftInternal(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleListDrafts(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(READ_PERMISSIONS)) {
      callbackContext.error("READ_SMS permission is required.");
      return;
    }

    try {
      callbackContext.success(buildPagedResult(queryDraftItems(options), options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleUpdateDraft(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "updateDraft")) {
      return;
    }

    try {
      callbackContext.success(updateDraft(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleDeleteDraft(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "deleteDraft")) {
      return;
    }

    try {
      callbackContext.success(deleteDraft(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleMoveToDraft(JSONObject options, CallbackContext callbackContext) {
    if (!ensureWriteAccess(callbackContext, "moveToDraft")) {
      return;
    }

    try {
      callbackContext.success(moveToDraft(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleResendFailed(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(SEND_PERMISSIONS)) {
      callbackContext.error("SEND_SMS permission is required.");
      return;
    }

    try {
      callbackContext.success(resendFailedMessage(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleGetContacts(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(CONTACTS_PERMISSIONS)) {
      callbackContext.error("READ_CONTACTS permission is required.");
      return;
    }

    try {
      callbackContext.success(searchContacts(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleResolveAddresses(JSONObject options, CallbackContext callbackContext) {
    if (!hasPermissions(CONTACTS_PERMISSIONS)) {
      callbackContext.error("READ_CONTACTS permission is required.");
      return;
    }

    try {
      callbackContext.success(resolveAddressList(options.optJSONArray("addresses")));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleExportAttachment(JSONObject options, CallbackContext callbackContext) {
    try {
      callbackContext.success(exportAttachment(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private void handleCreateAttachmentThumbnail(JSONObject options, CallbackContext callbackContext) {
    try {
      callbackContext.success(createAttachmentThumbnail(options));
    } catch (Exception exception) {
      callbackContext.error(exception.getMessage());
    }
  }

  private boolean ensureWriteAccess(CallbackContext callbackContext, String actionName) {
    if (Build.VERSION.SDK_INT >= 19 && !isDefaultSmsApp()) {
      callbackContext.error("Default SMS app role is required for " + actionName + ".");
      return false;
    }

    return true;
  }

  private JSONObject markRead(JSONObject options, boolean read) throws JSONException {
    MessageReference messageReference;
    long threadId;
    ContentValues values;
    int smsUpdated;
    int mmsUpdated;
    JSONObject result;

    values = new ContentValues();
    values.put("read", read ? 1 : 0);
    values.put("seen", read ? 1 : 0);
    result = new JSONObject();

    messageReference = parseMessageReference(options, false);
    if (messageReference != null) {
      smsUpdated = cordova.getActivity().getContentResolver().update(messageReference.uri, values, null, null);
      result.put("messageId", messageReference.messageId);
      result.put("updatedRows", smsUpdated);
      return result;
    }

    threadId = parseThreadId(options);
    if (threadId <= 0L) {
      throw new IllegalArgumentException("threadKey or messageId is required.");
    }

    smsUpdated = cordova.getActivity().getContentResolver().update(
      Telephony.Sms.CONTENT_URI,
      values,
      "thread_id = ?",
      new String[] { String.valueOf(threadId) }
    );

    mmsUpdated = cordova.getActivity().getContentResolver().update(
      Uri.parse("content://mms"),
      values,
      "thread_id = ?",
      new String[] { String.valueOf(threadId) }
    );

    result.put("threadKey", String.valueOf(threadId));
    result.put("updatedRows", smsUpdated + mmsUpdated);
    result.put("read", read);
    return result;
  }

  private Intent buildMmsIntent(List<String> recipients, String message, ArrayList<Uri> streams, JSONArray attachmentData, int subscriptionId) {
    Intent intent;
    String mimeType;
    String addressList;

    if (streams.size() > 1) {
      intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
      intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
    } else {
      intent = new Intent(Intent.ACTION_SEND);
      if (streams.size() == 1) {
        intent.putExtra(Intent.EXTRA_STREAM, streams.get(0));
      }
    }

    mimeType = resolveAttachmentMimeType(attachmentData);
    addressList = joinStringList(recipients, ";");

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setType(TextUtils.isEmpty(mimeType) ? "*/*" : mimeType);

    if (!TextUtils.isEmpty(addressList)) {
      intent.putExtra("address", addressList);
    }

    if (!TextUtils.isEmpty(message)) {
      intent.putExtra("sms_body", message);
      intent.putExtra(Intent.EXTRA_TEXT, message);
    }

    if (subscriptionId > 0) {
      intent.putExtra("subscription", subscriptionId);
      intent.putExtra("subscription_id", subscriptionId);
      intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subscriptionId);
    }

    return Intent.createChooser(intent, "Send message");
  }

  private JSONObject buildSendResult(String mode, int recipientCount, int attachmentCount, String requestId, int subscriptionId) {
    JSONObject result;

    result = new JSONObject();

    try {
      result.put("mode", mode);
      result.put("recipientCount", recipientCount);
      result.put("attachmentCount", attachmentCount);
      result.put("requestId", requestId);
      result.put("subscriptionId", subscriptionId);
    } catch (JSONException ignored) {
    }

    return result;
  }

  private PendingIntent buildStatusPendingIntent(String action, String requestId, String recipient, int recipientIndex, int partIndex, int partCount, int subscriptionId) {
    Intent intent;

    intent = new Intent(cordova.getActivity(), SmsReceiver.class);
    intent.setAction(action);
    intent.putExtra("requestId", requestId);
    intent.putExtra("recipient", recipient);
    intent.putExtra("recipientIndex", recipientIndex);
    intent.putExtra("partIndex", partIndex);
    intent.putExtra("partCount", partCount);
    intent.putExtra("subscriptionId", subscriptionId);

    return PendingIntent.getBroadcast(
      cordova.getActivity(),
      buildRequestCode(action, requestId, recipient, recipientIndex, partIndex),
      intent,
      buildPendingIntentFlags()
    );
  }

  private ArrayList<PendingIntent> buildStatusIntentList(String action, String requestId, String recipient, int recipientIndex, int partCount, int subscriptionId) {
    ArrayList<PendingIntent> pendingIntents;
    int index;

    pendingIntents = new ArrayList<PendingIntent>();
    for (index = 0; index < partCount; index++) {
      pendingIntents.add(buildStatusPendingIntent(action, requestId, recipient, recipientIndex, index, partCount, subscriptionId));
    }

    return pendingIntents;
  }

  private int buildPendingIntentFlags() {
    int flags;

    flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }

    return flags;
  }

  private int buildRequestCode(String action, String requestId, String recipient, int recipientIndex, int partIndex) {
    String value;

    value = action + "|" + requestId + "|" + recipient + "|" + recipientIndex + "|" + partIndex;
    return value.hashCode();
  }

  private SmsManager createSmsManager(int subscriptionId) {
    if (subscriptionId > 0 && Build.VERSION.SDK_INT >= 22) {
      return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
    }

    return SmsManager.getDefault();
  }

  private int resolveRequestedSubscriptionId(JSONObject options) {
    int subscriptionId;
    int simSlotIndex;

    subscriptionId = options.optInt("subscriptionId", -1);
    if (subscriptionId > 0) {
      return subscriptionId;
    }

    simSlotIndex = options.optInt("simSlotIndex", -1);
    if (simSlotIndex >= 0) {
      return resolveSubscriptionIdForSlot(simSlotIndex);
    }

    return -1;
  }

  private int resolveSubscriptionIdForSlot(int simSlotIndex) {
    SubscriptionManager manager;
    List<SubscriptionInfo> subscriptions;
    int index;

    if (Build.VERSION.SDK_INT < 22 || !hasPermissions(PHONE_STATE_PERMISSIONS)) {
      return -1;
    }

    manager = SubscriptionManager.from(cordova.getActivity());
    subscriptions = manager.getActiveSubscriptionInfoList();
    if (subscriptions == null) {
      return -1;
    }

    for (index = 0; index < subscriptions.size(); index++) {
      if (subscriptions.get(index).getSimSlotIndex() == simSlotIndex) {
        return subscriptions.get(index).getSubscriptionId();
      }
    }

    return -1;
  }

  private JSONArray listSubscriptions() throws JSONException {
    JSONArray result;
    SubscriptionManager manager;
    List<SubscriptionInfo> subscriptions;
    int index;

    result = new JSONArray();
    if (Build.VERSION.SDK_INT < 22) {
      return result;
    }

    manager = SubscriptionManager.from(cordova.getActivity());
    subscriptions = manager.getActiveSubscriptionInfoList();
    if (subscriptions == null) {
      return result;
    }

    for (index = 0; index < subscriptions.size(); index++) {
      JSONObject item;

      item = new JSONObject();
      item.put("subscriptionId", subscriptions.get(index).getSubscriptionId());
      item.put("simSlotIndex", subscriptions.get(index).getSimSlotIndex());
      item.put("displayName", safeCharSequence(subscriptions.get(index).getDisplayName()));
      item.put("carrierName", safeCharSequence(subscriptions.get(index).getCarrierName()));
      item.put("countryIso", safeString(subscriptions.get(index).getCountryIso()));
      item.put("number", safeSubscriptionNumber(subscriptions.get(index)));
      result.put(item);
    }

    return result;
  }

  private JSONArray queryThreads(JSONObject options) throws JSONException {
    return toJsonArray(applyPaging(queryThreadItems(options), options));
  }

  private List<JSONObject> queryThreadItems(JSONObject options) throws JSONException {
    ContentResolver resolver;
    Uri uri;
    Cursor cursor;
    ArrayList<JSONObject> threads;
    int limit;

    resolver = cordova.getActivity().getContentResolver();
    uri = Uri.parse("content://mms-sms/conversations?simple=true");
    threads = new ArrayList<JSONObject>();
    limit = Math.max(0, options.optInt("limit", 0));

    cursor = resolver.query(
      uri,
      new String[] { "_id", "date", "message_count", "recipient_ids", "snippet", "read" },
      null,
      null,
      "date DESC"
    );

    if (cursor == null) {
      return threads;
    }

    try {
      while (cursor.moveToNext()) {
        JSONObject row;

        row = buildThreadRow(cursor, options);
        if (!matchesThreadOptions(row, options)) {
          continue;
        }

        threads.add(row);
        if (limit > 0 && threads.size() >= limit) {
          break;
        }
      }
    } finally {
      cursor.close();
    }

    return threads;
  }

  private JSONObject buildThreadRow(Cursor cursor, JSONObject options) throws JSONException {
    JSONObject row;
    long threadId;

    threadId = cursor.getLong(cursor.getColumnIndex("_id"));
    row = new JSONObject();
    row.put("threadKey", String.valueOf(threadId));
    row.put("threadId", threadId);
    row.put("date", cursor.getLong(cursor.getColumnIndex("date")));
    row.put("messageCount", cursor.getInt(cursor.getColumnIndex("message_count")));
    row.put("snippet", safeString(cursor.getString(cursor.getColumnIndex("snippet"))));
    row.put("read", cursor.getInt(cursor.getColumnIndex("read")) == 1);
    row.put("addresses", resolveCanonicalAddresses(safeString(cursor.getString(cursor.getColumnIndex("recipient_ids")))));
    row.put("settings", getStoredThreadSettings(String.valueOf(threadId)));
    enrichThreadContacts(row, options);
    return row;
  }

  private JSONObject querySingleThread(long threadId, JSONObject options) throws JSONException {
    Cursor cursor;

    if (threadId <= 0L) {
      return null;
    }

    cursor = cordova.getActivity().getContentResolver().query(
      Uri.parse("content://mms-sms/conversations?simple=true"),
      new String[] { "_id", "date", "message_count", "recipient_ids", "snippet", "read" },
      "_id = ?",
      new String[] { String.valueOf(threadId) },
      null
    );

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      return buildThreadRow(cursor, options == null ? new JSONObject() : options);
    } finally {
      cursor.close();
    }
  }

  private JSONArray queryMessages(JSONObject options) throws JSONException {
    return toJsonArray(applyPaging(queryMessageItems(options), options));
  }

  private List<JSONObject> queryMessageItems(JSONObject options) throws JSONException {
    ArrayList<JSONObject> items;
    long threadId;
    String order;

    threadId = parseThreadId(options);
    order = safeString(options.opt("order"));
    if (TextUtils.isEmpty(order)) {
      order = "asc";
    }

    items = new ArrayList<JSONObject>();
    items.addAll(querySmsMessages(threadId));
    items.addAll(queryMmsMessages(threadId));

    filterMessageItems(items, options);
    sortMessages(items, order);
    return items;
  }

  private JSONObject buildThreadDetails(String threadKey, JSONObject options) throws JSONException {
    JSONArray threads;
    JSONObject thread;
    JSONObject messageOptions;
    int index;

    thread = null;
    threads = queryThreads(new JSONObject().put("limit", 0));

    for (index = 0; index < threads.length(); index++) {
      JSONObject candidate;

      candidate = threads.optJSONObject(index);
      if (candidate != null && threadKey.equals(candidate.optString("threadKey"))) {
        thread = candidate;
        break;
      }
    }

    if (thread == null) {
      thread = new JSONObject();
      thread.put("threadKey", threadKey);
      thread.put("threadId", parseThreadId(threadKey));
      thread.put("addresses", new JSONArray());
      thread.put("settings", getStoredThreadSettings(threadKey));
    }

    messageOptions = new JSONObject(options.toString());
    messageOptions.put("threadKey", threadKey);
    thread.put("messages", queryMessages(messageOptions));
    thread.put("drafts", toJsonArray(queryDraftItems(new JSONObject().put("threadKey", threadKey))));
    enrichThreadContacts(thread, options);

    return thread;
  }

  private List<JSONObject> queryDraftItems(JSONObject options) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    ArrayList<JSONObject> drafts;
    String selection;
    String[] selectionArgs;

    resolver = cordova.getActivity().getContentResolver();
    drafts = new ArrayList<JSONObject>();
    selection = null;
    selectionArgs = null;

    if (parseThreadId(options) > 0L) {
      selection = "thread_id = ?";
      selectionArgs = new String[] { String.valueOf(parseThreadId(options)) };
    }

    cursor = resolver.query(
      Telephony.Sms.Draft.CONTENT_URI,
      new String[] { "_id", "thread_id", "address", "body", "date", "read", "status", "type" },
      selection,
      selectionArgs,
      "date DESC"
    );

    if (cursor == null) {
      return drafts;
    }

    try {
      while (cursor.moveToNext()) {
        JSONObject row;

        row = buildSmsRow(cursor);
        row.put("draft", true);
        row.put("direction", "draft");
        enrichMessageContacts(row, options);
        if (matchesMessageOptions(row, options)) {
          drafts.add(row);
        }
      }
    } finally {
      cursor.close();
    }

    sortMessages(drafts, safeString(options.opt("order")));
    return drafts;
  }

  private void filterMessageItems(List<JSONObject> items, JSONObject options) throws JSONException {
    int index;

    for (index = items.size() - 1; index >= 0; index--) {
      enrichMessageContacts(items.get(index), options);
      if (!matchesMessageOptions(items.get(index), options)) {
        items.remove(index);
      }
    }
  }

  private boolean matchesThreadOptions(JSONObject row, JSONObject options) {
    String search;
    long date;
    long dateFrom;
    long dateTo;
    boolean unreadOnly;

    search = normalizeSearch(options.optString("search"));
    date = row.optLong("date", 0L);
    dateFrom = options.optLong("dateFrom", 0L);
    dateTo = options.optLong("dateTo", 0L);
    unreadOnly = options.optBoolean("unreadOnly", false);

    if (unreadOnly && row.optBoolean("read", true)) {
      return false;
    }

    if (dateFrom > 0L && date < dateFrom) {
      return false;
    }

    if (dateTo > 0L && date > dateTo) {
      return false;
    }

    if (TextUtils.isEmpty(search)) {
      return true;
    }

    return matchesSearchText(search, row.optString("snippet"))
      || matchesSearchText(search, joinJsonArray(row.optJSONArray("addresses"), " "))
      || matchesSearchText(search, extractContactNames(row.optJSONArray("contacts")));
  }

  private boolean matchesMessageOptions(JSONObject row, JSONObject options) {
    String search;
    String kind;
    long date;
    long dateFrom;
    long dateTo;
    boolean unreadOnly;

    search = normalizeSearch(options.optString("search"));
    kind = safeString(options.opt("kind"));
    date = row.optLong("sortDate", row.optLong("date", 0L));
    dateFrom = options.optLong("dateFrom", 0L);
    dateTo = options.optLong("dateTo", 0L);
    unreadOnly = options.optBoolean("unreadOnly", false);

    if (!TextUtils.isEmpty(kind) && !kind.equals(row.optString("kind"))) {
      return false;
    }

    if (unreadOnly && row.optBoolean("read", true)) {
      return false;
    }

    if (dateFrom > 0L && date < dateFrom) {
      return false;
    }

    if (dateTo > 0L && date > dateTo) {
      return false;
    }

    if (TextUtils.isEmpty(search)) {
      return true;
    }

    return matchesSearchText(search, row.optString("body"))
      || matchesSearchText(search, row.optString("subject"))
      || matchesSearchText(search, row.optString("address"))
      || matchesSearchText(search, joinJsonArray(row.optJSONArray("addresses"), " "))
      || matchesSearchText(search, extractContactNames(row.optJSONArray("contacts")));
  }

  private String normalizeSearch(String value) {
    return TextUtils.isEmpty(value) ? "" : value.toLowerCase(Locale.US).trim();
  }

  private boolean matchesSearchText(String search, String value) {
    String normalizedValue;
    String normalizedSearchAddress;
    String normalizedValueAddress;

    if (TextUtils.isEmpty(search) || TextUtils.isEmpty(value)) {
      return false;
    }

    normalizedValue = normalizeSearch(value);
    if (normalizedValue.contains(search)) {
      return true;
    }

    normalizedSearchAddress = normalizeAddress(search);
    normalizedValueAddress = normalizeAddress(value);
    return !TextUtils.isEmpty(normalizedSearchAddress)
      && !TextUtils.isEmpty(normalizedValueAddress)
      && normalizedValueAddress.contains(normalizedSearchAddress);
  }

  private String extractContactNames(JSONArray contacts) {
    StringBuilder builder;
    int index;

    if (contacts == null || contacts.length() == 0) {
      return "";
    }

    builder = new StringBuilder();
    for (index = 0; index < contacts.length(); index++) {
      JSONObject contact;

      contact = contacts.optJSONObject(index);
      if (contact == null || TextUtils.isEmpty(contact.optString("displayName"))) {
        continue;
      }

      if (builder.length() > 0) {
        builder.append(' ');
      }

      builder.append(contact.optString("displayName"));
    }

    return builder.toString();
  }

  private boolean matchesContactSearch(String search, JSONObject contact) {
    if (contact == null) {
      return false;
    }

    if (TextUtils.isEmpty(normalizeSearch(search))) {
      return true;
    }

    return matchesSearchText(normalizeSearch(search), contact.optString("displayName"))
      || matchesSearchText(normalizeSearch(search), contact.optString("number"))
      || matchesSearchText(normalizeSearch(search), contact.optString("normalizedNumber"))
      || matchesSearchText(normalizeSearch(search), contact.optString("address"));
  }

  private List<JSONObject> applyPaging(List<JSONObject> items, JSONObject options) {
    int offset;
    int limit;
    int fromIndex;
    int toIndex;

    offset = Math.max(0, options.optInt("offset", 0));
    limit = Math.max(0, options.optInt("limit", 0));
    fromIndex = Math.min(offset, items.size());

    if (limit == 0) {
      return new ArrayList<JSONObject>(items.subList(fromIndex, items.size()));
    }

    toIndex = Math.min(fromIndex + limit, items.size());
    return new ArrayList<JSONObject>(items.subList(fromIndex, toIndex));
  }

  private JSONObject buildPagedResult(List<JSONObject> items, JSONObject options) throws JSONException {
    JSONObject result;
    int offset;
    int limit;
    List<JSONObject> pagedItems;

    result = new JSONObject();
    offset = Math.max(0, options.optInt("offset", 0));
    limit = Math.max(0, options.optInt("limit", 0));
    pagedItems = applyPaging(items, options);

    result.put("items", toJsonArray(pagedItems));
    result.put("total", items.size());
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("hasMore", limit > 0 && offset + pagedItems.size() < items.size());
    result.put("nextOffset", limit > 0 && offset + pagedItems.size() < items.size() ? offset + pagedItems.size() : -1);
    return result;
  }

  private JSONArray toJsonArray(List<JSONObject> items) {
    JSONArray result;
    int index;

    result = new JSONArray();
    for (index = 0; index < items.size(); index++) {
      result.put(items.get(index));
    }

    return result;
  }

  private ArrayList<JSONObject> querySmsMessages(long threadId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    ArrayList<JSONObject> messages;
    String selection;
    String[] selectionArgs;

    resolver = cordova.getActivity().getContentResolver();
    messages = new ArrayList<JSONObject>();
    selection = null;
    selectionArgs = null;

    if (threadId > 0) {
      selection = "thread_id = ?";
      selectionArgs = new String[] { String.valueOf(threadId) };
    }

    cursor = resolver.query(
      Telephony.Sms.CONTENT_URI,
      new String[] { "_id", "thread_id", "address", "body", "date", "date_sent", "type", "read", "status" },
      selection,
      selectionArgs,
      "date DESC"
    );

    if (cursor == null) {
      return messages;
    }

    try {
      while (cursor.moveToNext()) {
        JSONObject row;

        row = buildSmsRow(cursor);
        messages.add(row);
      }
    } finally {
      cursor.close();
    }

    return messages;
  }

  private ArrayList<JSONObject> queryMmsMessages(long threadId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    ArrayList<JSONObject> messages;
    String selection;
    String[] selectionArgs;

    resolver = cordova.getActivity().getContentResolver();
    messages = new ArrayList<JSONObject>();
    selection = null;
    selectionArgs = null;

    if (threadId > 0) {
      selection = "thread_id = ?";
      selectionArgs = new String[] { String.valueOf(threadId) };
    }

    cursor = resolver.query(
      Uri.parse("content://mms"),
      new String[] { "_id", "thread_id", "date", "date_sent", "msg_box", "read", "sub" },
      selection,
      selectionArgs,
      "date DESC"
    );

    if (cursor == null) {
      return messages;
    }

    try {
      while (cursor.moveToNext()) {
        JSONObject row;

        row = buildMmsRow(cursor);
        messages.add(row);
      }
    } finally {
      cursor.close();
    }

    return messages;
  }

  private JSONObject buildSmsRow(Cursor cursor) throws JSONException {
    JSONObject row;
    JSONArray addresses;
    String address;
    long smsId;
    long smsThreadId;
    int type;
    long date;
    long dateSent;
    boolean outgoing;

    smsId = cursor.getLong(cursor.getColumnIndex("_id"));
    smsThreadId = cursor.getLong(cursor.getColumnIndex("thread_id"));
    type = cursor.getInt(cursor.getColumnIndex("type"));
    date = cursor.getLong(cursor.getColumnIndex("date"));
    dateSent = cursor.getLong(cursor.getColumnIndex("date_sent"));
    outgoing = isOutgoingSmsType(type);
    address = normalizeAddress(cursor.getString(cursor.getColumnIndex("address")));
    addresses = new JSONArray();
    if (!TextUtils.isEmpty(address)) {
      addresses.put(address);
    }

    row = new JSONObject();
    row.put("id", "sms:" + smsId);
    row.put("providerId", smsId);
    row.put("threadKey", String.valueOf(smsThreadId));
    row.put("threadId", smsThreadId);
    row.put("kind", "sms");
    row.put("mms", false);
    row.put("body", safeString(cursor.getString(cursor.getColumnIndex("body"))));
    row.put("address", address);
    row.put("addresses", addresses);
    row.put("date", date);
    row.put("dateSent", dateSent);
    row.put("sortDate", date > 0 ? date : dateSent);
    row.put("direction", outgoing ? "outgoing" : "incoming");
    row.put("box", type);
    row.put("read", cursor.getInt(cursor.getColumnIndex("read")) == 1);
    row.put("status", cursor.getInt(cursor.getColumnIndex("status")));
    return row;
  }

  private JSONObject buildMmsRow(Cursor cursor) throws JSONException {
    JSONObject row;
    JSONObject textAndAttachments;
    JSONArray addresses;
    long messageId;
    long messageThreadId;
    long date;
    long dateSent;
    int msgBox;

    messageId = cursor.getLong(cursor.getColumnIndex("_id"));
    messageThreadId = cursor.getLong(cursor.getColumnIndex("thread_id"));
    date = normalizeMmsTimestamp(cursor.getLong(cursor.getColumnIndex("date")));
    dateSent = normalizeMmsTimestamp(cursor.getLong(cursor.getColumnIndex("date_sent")));
    msgBox = cursor.getInt(cursor.getColumnIndex("msg_box"));
    textAndAttachments = loadMmsParts(messageId);
    addresses = loadMmsAddresses(messageId);

    row = new JSONObject();
    row.put("id", "mms:" + messageId);
    row.put("providerId", messageId);
    row.put("threadKey", String.valueOf(messageThreadId));
    row.put("threadId", messageThreadId);
    row.put("kind", "mms");
    row.put("mms", true);
    row.put("body", safeString(textAndAttachments.opt("body")));
    row.put("subject", safeString(cursor.getString(cursor.getColumnIndex("sub"))));
    row.put("addresses", addresses);
    row.put("address", addresses.length() > 0 ? addresses.optString(0) : "");
    row.put("attachments", textAndAttachments.optJSONArray("attachments"));
    row.put("attachmentCount", textAndAttachments.optJSONArray("attachments") == null ? 0 : textAndAttachments.optJSONArray("attachments").length());
    row.put("date", date);
    row.put("dateSent", dateSent);
    row.put("sortDate", date > 0 ? date : dateSent);
    row.put("direction", msgBox == 1 ? "incoming" : "outgoing");
    row.put("box", msgBox);
    row.put("read", cursor.getInt(cursor.getColumnIndex("read")) == 1);
    return row;
  }

  private JSONObject querySingleMessage(String source, long providerId) throws JSONException {
    if ("sms".equals(source)) {
      return querySingleSmsById(providerId);
    }

    if ("mms".equals(source)) {
      return querySingleMmsById(providerId);
    }

    return null;
  }

  private JSONObject querySingleMmsById(long providerId) throws JSONException {
    Cursor cursor;

    if (providerId <= 0L) {
      return null;
    }

    cursor = cordova.getActivity().getContentResolver().query(
      Uri.parse("content://mms"),
      new String[] { "_id", "thread_id", "date", "date_sent", "msg_box", "read", "sub" },
      "_id = ?",
      new String[] { String.valueOf(providerId) },
      null
    );

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      return buildMmsRow(cursor);
    } finally {
      cursor.close();
    }
  }

  private JSONObject loadMmsParts(long messageId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    JSONObject result;
    JSONArray attachments;
    StringBuilder bodyBuilder;

    resolver = cordova.getActivity().getContentResolver();
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
        String dataPath;

        partId = safeString(cursor.getString(cursor.getColumnIndex("_id")));
        contentType = safeString(cursor.getString(cursor.getColumnIndex("ct")));
        textValue = safeString(cursor.getString(cursor.getColumnIndex("text")));
        dataPath = safeString(cursor.getString(cursor.getColumnIndex("_data")));

        if (contentType.startsWith("text/")) {
          String bodyText;

          bodyText = textValue;
          if (TextUtils.isEmpty(bodyText) && !TextUtils.isEmpty(dataPath)) {
            bodyText = readContentText(Uri.parse("content://mms/part/" + partId));
          }

          if (!TextUtils.isEmpty(bodyText)) {
            if (bodyBuilder.length() > 0) {
              bodyBuilder.append('\n');
            }

            bodyBuilder.append(bodyText);
          }
        } else if (!"application/smil".equals(contentType)) {
          JSONObject attachment;
          Uri uri;

          uri = Uri.parse("content://mms/part/" + partId);
          attachment = new JSONObject();
          attachment.put("id", partId);
          attachment.put("uri", uri.toString());
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

  private JSONArray loadMmsAddresses(long messageId) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    LinkedHashSet<String> unique;
    JSONArray result;

    resolver = cordova.getActivity().getContentResolver();
    unique = new LinkedHashSet<String>();
    result = new JSONArray();

    cursor = resolver.query(
      Uri.parse("content://mms/" + messageId + "/addr"),
      new String[] { "address", "type" },
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

        address = normalizeAddress(cursor.getString(cursor.getColumnIndex("address")));
        if (TextUtils.isEmpty(address) || "insert-address-token".equals(address)) {
          continue;
        }

        unique.add(address);
      }
    } finally {
      cursor.close();
    }

    for (String value : unique) {
      result.put(value);
    }

    return result;
  }

  private JSONArray resolveCanonicalAddresses(String recipientIds) throws JSONException {
    ContentResolver resolver;
    JSONArray result;
    LinkedHashSet<String> unique;
    String[] ids;
    int index;

    result = new JSONArray();
    if (TextUtils.isEmpty(recipientIds)) {
      return result;
    }

    ids = recipientIds.trim().split("\\s+");
    resolver = cordova.getActivity().getContentResolver();
    unique = new LinkedHashSet<String>();

    for (index = 0; index < ids.length; index++) {
      Cursor cursor;
      Uri uri;

      if (TextUtils.isEmpty(ids[index])) {
        continue;
      }

      uri = Uri.parse("content://mms-sms/canonical-address/" + ids[index]);
      cursor = resolver.query(uri, new String[] { "address" }, null, null, null);
      if (cursor == null) {
        continue;
      }

      try {
        if (cursor.moveToFirst()) {
          String address;

          address = normalizeAddress(cursor.getString(cursor.getColumnIndex("address")));
          if (!TextUtils.isEmpty(address) && !unique.contains(address)) {
            unique.add(address);
            result.put(address);
          }
        }
      } finally {
        cursor.close();
      }
    }

    return result;
  }

  private void enrichThreadContacts(JSONObject row, JSONObject options) throws JSONException {
    JSONArray contacts;

    if (!shouldResolveContacts(options)) {
      return;
    }

    contacts = resolveAddressList(row.optJSONArray("addresses"));
    row.put("contacts", contacts);
    if (contacts.length() > 0) {
      row.put("contact", contacts.optJSONObject(0));
    }
  }

  private void enrichMessageContacts(JSONObject row, JSONObject options) throws JSONException {
    JSONArray addresses;
    JSONArray contacts;

    if (!shouldResolveContacts(options)) {
      return;
    }

    addresses = row.optJSONArray("addresses");
    if (addresses == null) {
      addresses = new JSONArray();
      if (!TextUtils.isEmpty(row.optString("address"))) {
        addresses.put(row.optString("address"));
      }
    }

    contacts = resolveAddressList(addresses);
    row.put("contacts", contacts);
    if (contacts.length() > 0) {
      row.put("contact", contacts.optJSONObject(0));
    }
  }

  private boolean shouldResolveContacts(JSONObject options) {
    return hasPermissions(CONTACTS_PERMISSIONS)
      && (options.optBoolean("resolveContacts", false) || !TextUtils.isEmpty(options.optString("search")));
  }

  private JSONArray resolveAddressList(JSONArray addresses) throws JSONException {
    JSONArray result;
    LinkedHashSet<String> seen;
    int index;

    result = new JSONArray();
    seen = new LinkedHashSet<String>();
    if (addresses == null) {
      return result;
    }

    for (index = 0; index < addresses.length(); index++) {
      String address;
      JSONObject contact;

      address = normalizeAddress(addresses.opt(index));
      if (TextUtils.isEmpty(address)) {
        continue;
      }

      if (seen.contains(address)) {
        continue;
      }

      contact = lookupContactByAddress(address);
      if (contact != null) {
        seen.add(address);
        result.put(contact);
      }
    }

    return result;
  }

  private JSONArray searchContacts(JSONObject options) throws JSONException {
    ContentResolver resolver;
    Cursor cursor;
    JSONArray result;
    LinkedHashSet<String> seen;
    String query;
    String normalizedQuery;
    String selection;
    ArrayList<String> selectionArgs;
    int limit;

    resolver = cordova.getActivity().getContentResolver();
    result = new JSONArray();
    seen = new LinkedHashSet<String>();
    query = safeString(options.opt("search"));
    normalizedQuery = normalizeAddress(query);
    selection = null;
    selectionArgs = new ArrayList<String>();
    limit = Math.max(0, options.optInt("limit", 50));

    if (!TextUtils.isEmpty(query)) {
      selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
        + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
      selectionArgs.add("%" + query + "%");
      selectionArgs.add("%" + query + "%");
      if (!TextUtils.isEmpty(normalizedQuery)) {
        selection += " OR " + ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER + " LIKE ?";
        selectionArgs.add("%" + normalizedQuery + "%");
      }
    }

    cursor = resolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      new String[] {
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
        ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
      },
      selection,
      selectionArgs.isEmpty() ? null : selectionArgs.toArray(new String[selectionArgs.size()]),
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    );

    if (cursor == null) {
      return result;
    }

    try {
      while (cursor.moveToNext()) {
        JSONObject contact;
        String key;

        contact = buildContactFromCursor(cursor);
        if (!matchesContactSearch(query, contact)) {
          continue;
        }

        key = contact.optString("contactId") + "|" + contact.optString("number");
        if (seen.contains(key)) {
          continue;
        }

        seen.add(key);
        result.put(contact);
        if (limit > 0 && result.length() >= limit) {
          break;
        }
      }
    } finally {
      cursor.close();
    }

    return result;
  }

  private JSONObject lookupContactByAddress(String address) {
    ContentResolver resolver;
    Cursor cursor;
    String normalizedAddress;

    resolver = cordova.getActivity().getContentResolver();
    normalizedAddress = normalizeAddress(address);
    cursor = lookupContactCursor(resolver, address);
    if (cursor == null && !TextUtils.isEmpty(normalizedAddress) && !normalizedAddress.equals(address)) {
      cursor = lookupContactCursor(resolver, normalizedAddress);
    }

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      return buildContactFromCursor(cursor, normalizedAddress);
    } catch (Exception ignored) {
      return null;
    } finally {
      cursor.close();
    }
  }

  private Cursor lookupContactCursor(ContentResolver resolver, String address) {
    Cursor cursor;

    if (TextUtils.isEmpty(address)) {
      return null;
    }

    cursor = resolver.query(
      Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
      new String[] {
        ContactsContract.PhoneLookup._ID,
        ContactsContract.PhoneLookup.DISPLAY_NAME,
        ContactsContract.PhoneLookup.NUMBER,
        ContactsContract.PhoneLookup.NORMALIZED_NUMBER,
        ContactsContract.PhoneLookup.PHOTO_URI,
        ContactsContract.PhoneLookup.LOOKUP_KEY
      },
      null,
      null,
      null
    );
    return cursor;
  }

  private JSONObject buildContactFromCursor(Cursor cursor) throws JSONException {
    return buildContactFromCursor(cursor, "");
  }

  private JSONObject buildContactFromCursor(Cursor cursor, String address) throws JSONException {
    JSONObject result;
    String number;
    String normalizedNumber;
    String lookupAddress;

    result = new JSONObject();
    number = normalizeAddress(cursor.getString(2));
    normalizedNumber = firstNonEmpty(
      normalizeAddress(cursor.getString(3)),
      number
    );
    lookupAddress = firstNonEmpty(normalizeAddress(address), normalizedNumber);
    result.put("contactId", safeString(cursor.getString(0)));
    result.put("displayName", safeString(cursor.getString(1)));
    result.put("number", number);
    result.put("normalizedNumber", normalizedNumber);
    result.put("photoUri", safeString(cursor.getString(4)));
    result.put("lookupKey", safeString(cursor.getString(5)));
    result.put("address", lookupAddress);
    return result;
  }

  private String joinJsonArray(JSONArray values, String separator) {
    StringBuilder builder;
    int index;

    if (values == null || values.length() == 0) {
      return "";
    }

    builder = new StringBuilder();
    for (index = 0; index < values.length(); index++) {
      if (TextUtils.isEmpty(values.optString(index))) {
        continue;
      }

      if (builder.length() > 0) {
        builder.append(separator);
      }

      builder.append(values.optString(index));
    }

    return builder.toString();
  }

  private JSONObject getThreadSettings(JSONObject options) {
    return getStoredThreadSettings(String.valueOf(parseThreadId(options)));
  }

  private JSONObject setThreadSettings(JSONObject options) {
    JSONObject allSettings;
    JSONObject settings;
    String threadKey;
    boolean merge;

    allSettings = readJsonPref(PREF_KEY_THREAD_SETTINGS);
    settings = options.optJSONObject("settings");
    threadKey = safeString(options.opt("threadKey"));
    merge = options.optBoolean("merge", true);

    if (TextUtils.isEmpty(threadKey) || settings == null) {
      return new JSONObject();
    }

    if (merge && allSettings.optJSONObject(threadKey) != null) {
      settings = mergeJson(allSettings.optJSONObject(threadKey), settings);
    }

    try {
      allSettings.put(threadKey, settings);
    } catch (JSONException ignored) {
    }

    writeJsonPref(PREF_KEY_THREAD_SETTINGS, allSettings);
    return settings;
  }

  private JSONObject getStoredThreadSettings(String threadKey) {
    JSONObject allSettings;

    if (TextUtils.isEmpty(threadKey) || "0".equals(threadKey)) {
      return new JSONObject();
    }

    allSettings = readJsonPref(PREF_KEY_THREAD_SETTINGS);
    return allSettings.optJSONObject(threadKey) == null ? new JSONObject() : allSettings.optJSONObject(threadKey);
  }

  private List<String> resolveAddressesForThread(long threadId) {
    ContentResolver resolver;
    Cursor cursor;
    JSONArray addresses;
    ArrayList<String> result;
    String recipientIds;
    int index;

    resolver = cordova.getActivity().getContentResolver();
    result = new ArrayList<String>();
    cursor = resolver.query(
      Uri.parse("content://mms-sms/conversations?simple=true"),
      new String[] { "recipient_ids" },
      "_id = ?",
      new String[] { String.valueOf(threadId) },
      null
    );

    if (cursor == null) {
      return result;
    }

    try {
      if (!cursor.moveToFirst()) {
        return result;
      }

      recipientIds = safeString(cursor.getString(cursor.getColumnIndex("recipient_ids")));
    } finally {
      cursor.close();
    }

    try {
      addresses = resolveCanonicalAddresses(recipientIds);
      for (index = 0; index < addresses.length(); index++) {
        if (!TextUtils.isEmpty(addresses.optString(index))) {
          result.add(addresses.optString(index));
        }
      }
    } catch (Exception ignored) {
    }

    return result;
  }

  private long resolveOrCreateThreadId(List<String> recipients) {
    ContentResolver resolver;
    Uri.Builder builder;
    Cursor cursor;

    resolver = cordova.getActivity().getContentResolver();
    builder = Uri.parse("content://mms-sms/threadID").buildUpon();

    for (int index = 0; index < recipients.size(); index++) {
      builder.appendQueryParameter("recipient", recipients.get(index));
    }

    cursor = resolver.query(builder.build(), new String[] { "_id" }, null, null, null);
    if (cursor == null) {
      return 0L;
    }

    try {
      if (!cursor.moveToFirst()) {
        return 0L;
      }

      return cursor.getLong(cursor.getColumnIndex("_id"));
    } finally {
      cursor.close();
    }
  }

  private long parseThreadId(JSONObject options) {
    String threadKey;

    threadKey = safeString(options.opt("threadKey"));
    if (!TextUtils.isEmpty(threadKey)) {
      return parseThreadId(threadKey);
    }

    return options.optLong("threadId", 0L);
  }

  private long parseThreadId(String threadKey) {
    try {
      return Long.parseLong(threadKey);
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private MessageReference parseMessageReference(JSONObject options) {
    return parseMessageReference(options, true);
  }

  private MessageReference parseMessageReference(JSONObject options, boolean required) {
    String messageId;
    String kind;
    long providerId;

    messageId = firstNonEmpty(safeString(options.opt("messageId")), safeString(options.opt("id")));
    kind = safeString(options.opt("kind"));
    providerId = options.optLong("providerId", 0L);

    if (!TextUtils.isEmpty(messageId)) {
      if (messageId.startsWith("sms:")) {
        return new MessageReference("sms", parseLastPathId(Uri.parse(messageId.replace("sms:", "content://sms/"))), messageId, Uri.parse("content://sms/" + messageId.substring(4)));
      }

      if (messageId.startsWith("mms:")) {
        return new MessageReference("mms", parseLastPathId(Uri.parse(messageId.replace("mms:", "content://mms/"))), messageId, Uri.parse("content://mms/" + messageId.substring(4)));
      }
    }

    if (!TextUtils.isEmpty(kind) && providerId > 0L) {
      if (!"sms".equals(kind) && !"mms".equals(kind)) {
        throw new IllegalArgumentException("kind must be sms or mms.");
      }

      return new MessageReference(kind, providerId, kind + ":" + providerId, Uri.parse("content://" + kind + "/" + providerId));
    }

    if (required) {
      throw new IllegalArgumentException("messageId or kind/providerId is required.");
    }

    return null;
  }

  private void sortMessages(List<JSONObject> items, final String order) {
    Collections.sort(items, new Comparator<JSONObject>() {
      @Override
      public int compare(JSONObject left, JSONObject right) {
        long leftValue;
        long rightValue;

        leftValue = left.optLong("sortDate", left.optLong("date", 0L));
        rightValue = right.optLong("sortDate", right.optLong("date", 0L));

        if ("desc".equalsIgnoreCase(order)) {
          return compareLong(rightValue, leftValue);
        }

        return compareLong(leftValue, rightValue);
      }
    });
  }

  private int compareLong(long left, long right) {
    if (left == right) {
      return 0;
    }

    return left < right ? -1 : 1;
  }

  private boolean isOutgoingSmsType(int type) {
    return type == Telephony.Sms.MESSAGE_TYPE_SENT
      || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
      || type == Telephony.Sms.MESSAGE_TYPE_FAILED
      || type == Telephony.Sms.MESSAGE_TYPE_QUEUED;
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

  private String resolveAttachmentMimeType(JSONArray attachments) {
    String fallback;
    int index;

    if (attachments == null || attachments.length() == 0) {
      return "text/plain";
    }

    fallback = null;
    for (index = 0; index < attachments.length(); index++) {
      JSONObject attachment;
      String contentType;

      attachment = attachments.optJSONObject(index);
      if (attachment == null) {
        continue;
      }

      contentType = safeString(attachment.opt("contentType"));
      if (TextUtils.isEmpty(contentType)) {
        continue;
      }

      if (fallback == null) {
        fallback = contentType;
      }

      if (contentType.startsWith("image/")) {
        return "image/*";
      }
    }

    return fallback == null ? "*/*" : fallback;
  }

  private Bundle jsonToBundle(JSONObject value) {
    Bundle result;
    JSONArray names;
    int index;

    if (value == null) {
      return null;
    }

    result = new Bundle();
    names = value.names();
    if (names == null) {
      return result;
    }

    for (index = 0; index < names.length(); index++) {
      String key;
      Object item;

      key = names.optString(index);
      item = value.opt(key);

      if (item instanceof Boolean) {
        result.putBoolean(key, ((Boolean) item).booleanValue());
      } else if (item instanceof Integer) {
        result.putInt(key, ((Integer) item).intValue());
      } else if (item instanceof Long) {
        result.putLong(key, ((Long) item).longValue());
      } else if (item instanceof Double) {
        result.putDouble(key, ((Double) item).doubleValue());
      } else if (item != null) {
        result.putString(key, String.valueOf(item));
      }
    }

    return result;
  }

  private JSONObject updateDraft(JSONObject options) throws JSONException {
    MessageReference reference;
    ContentValues values;
    long threadId;
    int updatedRows;

    reference = parseMessageReference(options, false);
    if (reference == null) {
      threadId = parseThreadId(options);
      if (threadId > 0L) {
        reference = findDraftReferenceByThread(threadId);
      }
    }

    if (reference == null) {
      return saveDraftInternal(options);
    }

    values = new ContentValues();
    values.put("body", safeString(options.opt("message")));
    values.put("date", System.currentTimeMillis());
    updatedRows = cordova.getActivity().getContentResolver().update(reference.uri, values, null, null);

    return querySingleDraftById(reference.providerId, updatedRows);
  }

  private JSONObject deleteDraft(JSONObject options) throws JSONException {
    MessageReference reference;
    JSONObject result;
    int deletedRows;

    reference = parseMessageReference(options, false);
    if (reference == null) {
      long threadId;

      threadId = parseThreadId(options);
      if (threadId <= 0L) {
        throw new IllegalArgumentException("messageId or threadKey is required.");
      }

      deletedRows = cordova.getActivity().getContentResolver().delete(
        Telephony.Sms.Draft.CONTENT_URI,
        "thread_id = ?",
        new String[] { String.valueOf(threadId) }
      );

      result = new JSONObject();
      result.put("threadKey", String.valueOf(threadId));
      result.put("deletedRows", deletedRows);
      return result;
    }

    deletedRows = cordova.getActivity().getContentResolver().delete(reference.uri, null, null);
    result = new JSONObject();
    result.put("messageId", reference.messageId);
    result.put("deletedRows", deletedRows);
    return result;
  }

  private JSONObject moveToDraft(JSONObject options) throws JSONException {
    JSONObject original;
    JSONObject draft;
    JSONObject saveOptions;
    boolean deleteOriginal;

    original = querySingleSmsById(extractProviderId(options));
    if (original == null) {
      throw new IllegalArgumentException("SMS message not found.");
    }

    saveOptions = new JSONObject();
    saveOptions.put("threadKey", original.optString("threadKey"));
    saveOptions.put("message", original.optString("body"));
    saveOptions.put("recipients", original.optJSONArray("addresses"));
    saveOptions.put("replaceExisting", options.optBoolean("replaceExisting", true));
    draft = saveDraftInternal(saveOptions);

    deleteOriginal = options.optBoolean("deleteOriginal", true);
    if (deleteOriginal) {
      backupDeletedSms(original.optLong("providerId", 0L));
      cordova.getActivity().getContentResolver().delete(Uri.parse("content://sms/" + original.optLong("providerId", 0L)), null, null);
    }

    return draft;
  }

  private JSONObject resendFailedMessage(JSONObject options) throws JSONException {
    JSONObject original;
    JSONObject sendOptions;

    original = querySingleSmsById(extractProviderId(options));
    if (original == null) {
      throw new IllegalArgumentException("SMS message not found.");
    }

    sendOptions = new JSONObject();
    sendOptions.put("recipients", original.optJSONArray("addresses"));
    sendOptions.put("message", original.optString("body"));
    if (options.has("subscriptionId")) {
      sendOptions.put("subscriptionId", options.optInt("subscriptionId", -1));
    }

    if (options.has("simSlotIndex")) {
      sendOptions.put("simSlotIndex", options.optInt("simSlotIndex", -1));
    }

    if (options.has("requestId")) {
      sendOptions.put("requestId", options.optString("requestId"));
    }

    return sendSmsInternal(sendOptions);
  }

  private JSONObject saveDraftInternal(JSONObject options) throws JSONException {
    List<String> recipients;
    String message;
    long threadId;
    Uri insertedUri;
    ContentValues values;
    JSONObject result;
    long draftId;

    recipients = normalizeRecipients(options.optJSONArray("recipients"));
    message = safeString(options.opt("message"));
    threadId = parseThreadId(options);

    if (threadId <= 0L) {
      if (recipients.isEmpty()) {
        throw new IllegalArgumentException("recipients or threadKey is required.");
      }

      threadId = resolveOrCreateThreadId(recipients);
    }

    if (recipients.isEmpty()) {
      recipients = resolveAddressesForThread(threadId);
    }

    if (recipients.isEmpty()) {
      throw new IllegalArgumentException("Unable to resolve recipients for draft.");
    }

    if (options.optBoolean("replaceExisting", true)) {
      deleteDraftsByThread(threadId);
    }

    values = new ContentValues();
    values.put("thread_id", threadId);
    values.put("address", recipients.get(0));
    values.put("body", message);
    values.put("date", System.currentTimeMillis());
    values.put("read", 1);
    values.put("seen", 1);
    values.put("type", Telephony.Sms.MESSAGE_TYPE_DRAFT);

    insertedUri = cordova.getActivity().getContentResolver().insert(Telephony.Sms.Draft.CONTENT_URI, values);
    draftId = parseLastPathId(insertedUri);

    result = new JSONObject();
    result.put("id", draftId > 0 ? "sms:" + draftId : "");
    result.put("providerId", draftId);
    result.put("threadKey", String.valueOf(threadId));
    result.put("address", recipients.get(0));
    result.put("recipients", toJsonArray(recipients));
    result.put("body", message);
    result.put("date", values.getAsLong("date"));
    result.put("draft", true);
    return result;
  }

  private JSONObject sendSmsInternal(JSONObject options) throws JSONException {
    List<String> recipients;
    String message;
    SmsManager smsManager;
    String requestId;
    int subscriptionId;
    int index;
    boolean trackDelivery;

    recipients = normalizeRecipients(options.optJSONArray("recipients"));
    message = safeString(options.opt("message"));
    requestId = firstNonEmpty(safeString(options.opt("requestId")), UUID.randomUUID().toString());
    subscriptionId = resolveRequestedSubscriptionId(options);
    trackDelivery = options.optBoolean("trackDelivery", true);

    if (recipients.isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required.");
    }

    if (TextUtils.isEmpty(message)) {
      throw new IllegalArgumentException("Message body is required.");
    }

    smsManager = createSmsManager(subscriptionId);

    for (index = 0; index < recipients.size(); index++) {
      ArrayList<String> parts;

      parts = smsManager.divideMessage(message);
      if (parts.size() > 1) {
        smsManager.sendMultipartTextMessage(
          recipients.get(index),
          null,
          parts,
          buildStatusIntentList(BROADCAST_ACTION_SMS_SENT, requestId, recipients.get(index), index, parts.size(), subscriptionId),
          trackDelivery ? buildStatusIntentList(BROADCAST_ACTION_SMS_DELIVERED, requestId, recipients.get(index), index, parts.size(), subscriptionId) : null
        );
      } else {
        smsManager.sendTextMessage(
          recipients.get(index),
          null,
          message,
          buildStatusPendingIntent(BROADCAST_ACTION_SMS_SENT, requestId, recipients.get(index), index, 0, 1, subscriptionId),
          trackDelivery ? buildStatusPendingIntent(BROADCAST_ACTION_SMS_DELIVERED, requestId, recipients.get(index), index, 0, 1, subscriptionId) : null
        );
      }
    }

    return buildSendResult("sms", recipients.size(), 0, requestId, subscriptionId);
  }

  private String backupDeletedSms(long providerId) throws JSONException {
    JSONObject backup;
    JSONObject deletedMessages;
    String backupId;

    backup = querySingleSmsById(providerId);
    if (backup == null) {
      return "";
    }

    backupId = UUID.randomUUID().toString();
    backup.put("backupId", backupId);
    backup.put("originalMessageId", "sms:" + providerId);
    deletedMessages = readJsonPref(PREF_KEY_DELETED_SMS);
    deletedMessages.put(backupId, backup);
    writeJsonPref(PREF_KEY_DELETED_SMS, deletedMessages);
    return backupId;
  }

  private JSONObject restoreDeletedSms(JSONObject options) throws JSONException {
    JSONObject deletedMessages;
    JSONObject backup;
    Uri insertedUri;
    ContentValues values;
    String backupKey;
    JSONObject result;

    deletedMessages = readJsonPref(PREF_KEY_DELETED_SMS);
    backupKey = firstNonEmpty(safeString(options.opt("backupId")), safeString(options.opt("messageId")));
    backup = deletedMessages.optJSONObject(backupKey);

    if (backup == null && !TextUtils.isEmpty(backupKey)) {
      backup = findDeletedBackupByOriginalId(deletedMessages, backupKey);
      backupKey = backup == null ? backupKey : backup.optString("backupId");
    }

    if (backup == null) {
      throw new IllegalArgumentException("Deleted SMS backup not found.");
    }

    values = new ContentValues();
    values.put("thread_id", backup.optLong("threadId", 0L));
    values.put("address", backup.optString("address"));
    values.put("body", backup.optString("body"));
    values.put("date", backup.optLong("date", System.currentTimeMillis()));
    values.put("date_sent", backup.optLong("dateSent", 0L));
    values.put("read", backup.optBoolean("read", true) ? 1 : 0);
    values.put("seen", backup.optBoolean("read", true) ? 1 : 0);
    values.put("status", backup.optInt("status", 0));
    values.put("type", backup.optInt("box", Telephony.Sms.MESSAGE_TYPE_INBOX));

    insertedUri = cordova.getActivity().getContentResolver().insert(resolveSmsUriForType(backup.optInt("box", Telephony.Sms.MESSAGE_TYPE_INBOX)), values);
    deletedMessages.remove(backupKey);
    writeJsonPref(PREF_KEY_DELETED_SMS, deletedMessages);

    result = querySingleSmsById(parseLastPathId(insertedUri));
    return result == null ? new JSONObject() : result;
  }

  private JSONObject findDeletedBackupByOriginalId(JSONObject deletedMessages, String messageId) {
    JSONArray names;
    int index;

    names = deletedMessages.names();
    if (names == null) {
      return null;
    }

    for (index = 0; index < names.length(); index++) {
      JSONObject backup;

      backup = deletedMessages.optJSONObject(names.optString(index));
      if (backup != null && messageId.equals(backup.optString("originalMessageId"))) {
        return backup;
      }
    }

    return null;
  }

  private Uri resolveSmsUriForType(int type) {
    if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
      return Telephony.Sms.Sent.CONTENT_URI;
    }

    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) {
      return Telephony.Sms.Draft.CONTENT_URI;
    }

    return Telephony.Sms.Inbox.CONTENT_URI;
  }

  private void deleteDraftsByThread(long threadId) {
    if (threadId <= 0L) {
      return;
    }

    cordova.getActivity().getContentResolver().delete(
      Telephony.Sms.Draft.CONTENT_URI,
      "thread_id = ?",
      new String[] { String.valueOf(threadId) }
    );
  }

  private MessageReference findDraftReferenceByThread(long threadId) {
    Cursor cursor;

    cursor = cordova.getActivity().getContentResolver().query(
      Telephony.Sms.Draft.CONTENT_URI,
      new String[] { "_id" },
      "thread_id = ?",
      new String[] { String.valueOf(threadId) },
      "date DESC"
    );

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      return new MessageReference("sms", cursor.getLong(cursor.getColumnIndex("_id")), "sms:" + cursor.getLong(cursor.getColumnIndex("_id")), Uri.parse("content://sms/" + cursor.getLong(cursor.getColumnIndex("_id"))));
    } finally {
      cursor.close();
    }
  }

  private JSONObject querySingleDraftById(long providerId, int updatedRows) throws JSONException {
    JSONObject draft;

    draft = querySingleSmsById(providerId);
    if (draft == null) {
      draft = new JSONObject();
      draft.put("providerId", providerId);
      draft.put("updatedRows", updatedRows);
      return draft;
    }

    draft.put("updatedRows", updatedRows);
    draft.put("draft", true);
    return draft;
  }

  private JSONObject querySingleSmsById(long providerId) throws JSONException {
    Cursor cursor;

    if (providerId <= 0L) {
      return null;
    }

    cursor = cordova.getActivity().getContentResolver().query(
      Telephony.Sms.CONTENT_URI,
      new String[] { "_id", "thread_id", "address", "body", "date", "date_sent", "type", "read", "status" },
      "_id = ?",
      new String[] { String.valueOf(providerId) },
      null
    );

    if (cursor == null) {
      return null;
    }

    try {
      if (!cursor.moveToFirst()) {
        return null;
      }

      return buildSmsRow(cursor);
    } finally {
      cursor.close();
    }
  }

  private long extractProviderId(JSONObject options) {
    MessageReference reference;

    reference = parseMessageReference(options, false);
    if (reference != null) {
      return reference.providerId;
    }

    return options.optLong("providerId", 0L);
  }

  private JSONObject exportAttachment(JSONObject options) throws Exception {
    JSONObject attachment;
    Uri uri;
    String name;
    File targetFile;
    long bytesCopied;
    JSONObject result;

    attachment = options.optJSONObject("attachment");
    if (attachment == null) {
      attachment = options;
    }

    uri = Uri.parse(firstNonEmpty(safeString(attachment.opt("uri")), safeString(options.opt("uri"))));
    name = sanitizeFilename(firstNonEmpty(safeString(attachment.opt("name")), safeString(options.opt("name"))));
    targetFile = createCacheFile("exports", TextUtils.isEmpty(name) ? "attachment.bin" : name);
    bytesCopied = copyUriToFile(uri, targetFile);

    result = new JSONObject();
    result.put("path", targetFile.getAbsolutePath());
    result.put("fileUri", Uri.fromFile(targetFile).toString());
    result.put("sourceUri", uri.toString());
    result.put("bytes", bytesCopied);
    result.put("contentType", safeString(cordova.getActivity().getContentResolver().getType(uri)));
    return result;
  }

  private JSONObject createAttachmentThumbnail(JSONObject options) throws Exception {
    JSONObject attachment;
    Uri uri;
    int maxWidth;
    int maxHeight;
    int quality;
    BitmapFactory.Options bounds;
    BitmapFactory.Options decodeOptions;
    Bitmap bitmap;
    File targetFile;
    FileOutputStream outputStream;
    JSONObject result;

    attachment = options.optJSONObject("attachment");
    if (attachment == null) {
      attachment = options;
    }

    uri = Uri.parse(firstNonEmpty(safeString(attachment.opt("uri")), safeString(options.opt("uri"))));
    maxWidth = Math.max(1, options.optInt("maxWidth", 240));
    maxHeight = Math.max(1, options.optInt("maxHeight", 240));
    quality = Math.max(1, Math.min(100, options.optInt("quality", 80)));

    bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    decodeBitmapBounds(uri, bounds);

    decodeOptions = new BitmapFactory.Options();
    decodeOptions.inSampleSize = calculateInSampleSize(bounds, maxWidth, maxHeight);
    bitmap = decodeBitmap(uri, decodeOptions);
    if (bitmap == null) {
      throw new IllegalArgumentException("Unable to decode attachment image.");
    }

    targetFile = createCacheFile("thumbnails", UUID.randomUUID().toString() + ".jpg");
    outputStream = new FileOutputStream(targetFile);
    try {
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
      outputStream.flush();
    } finally {
      outputStream.close();
      bitmap.recycle();
    }

    result = new JSONObject();
    result.put("path", targetFile.getAbsolutePath());
    result.put("fileUri", Uri.fromFile(targetFile).toString());
    result.put("width", bounds.outWidth);
    result.put("height", bounds.outHeight);
    return result;
  }

  private void decodeBitmapBounds(Uri uri, BitmapFactory.Options options) throws Exception {
    InputStream stream;

    stream = cordova.getActivity().getContentResolver().openInputStream(uri);
    if (stream == null) {
      throw new IllegalArgumentException("Attachment URI could not be opened.");
    }

    try {
      BitmapFactory.decodeStream(stream, null, options);
    } finally {
      stream.close();
    }
  }

  private Bitmap decodeBitmap(Uri uri, BitmapFactory.Options options) throws Exception {
    InputStream stream;

    stream = cordova.getActivity().getContentResolver().openInputStream(uri);
    if (stream == null) {
      return null;
    }

    try {
      return BitmapFactory.decodeStream(stream, null, options);
    } finally {
      stream.close();
    }
  }

  private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
    int height;
    int width;
    int inSampleSize;

    height = options.outHeight;
    width = options.outWidth;
    inSampleSize = 1;

    while (height / inSampleSize > reqHeight || width / inSampleSize > reqWidth) {
      inSampleSize *= 2;
    }

    return Math.max(1, inSampleSize);
  }

  private long copyUriToFile(Uri uri, File targetFile) throws Exception {
    InputStream inputStream;
    FileOutputStream outputStream;
    byte[] buffer;
    int count;
    long total;

    inputStream = cordova.getActivity().getContentResolver().openInputStream(uri);
    if (inputStream == null) {
      throw new IllegalArgumentException("Attachment URI could not be opened.");
    }

    outputStream = new FileOutputStream(targetFile);
    buffer = new byte[8192];
    total = 0L;

    try {
      while ((count = inputStream.read(buffer)) > 0) {
        outputStream.write(buffer, 0, count);
        total += count;
      }
      outputStream.flush();
    } finally {
      inputStream.close();
      outputStream.close();
    }

    return total;
  }

  private File createCacheFile(String directoryName, String fileName) {
    File directory;

    directory = new File(cordova.getActivity().getCacheDir(), "cordova_sms/" + directoryName);
    if (!directory.exists()) {
      directory.mkdirs();
    }

    return new File(directory, fileName);
  }

  private String sanitizeFilename(String value) {
    if (TextUtils.isEmpty(value)) {
      return "";
    }

    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private SharedPreferences getPrefs() {
    return cordova.getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private JSONObject readJsonPref(String key) {
    try {
      return new JSONObject(getPrefs().getString(key, "{}"));
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  private void writeJsonPref(String key, JSONObject value) {
    getPrefs().edit().putString(key, value == null ? "{}" : value.toString()).apply();
  }

  private boolean readBooleanPref(String key, boolean fallback) {
    return getPrefs().getBoolean(key, fallback);
  }

  private void writeBooleanPref(String key, boolean value) {
    getPrefs().edit().putBoolean(key, value).apply();
  }

  private JSONObject mergeJson(JSONObject base, JSONObject overlay) {
    JSONObject merged;
    JSONArray names;
    int index;

    merged = copyJson(base);
    names = overlay.names();
    if (names == null) {
      return merged;
    }

    try {
      for (index = 0; index < names.length(); index++) {
        String key;

        key = names.optString(index);
        merged.put(key, overlay.opt(key));
      }
    } catch (JSONException ignored) {
    }

    return merged;
  }

  private List<String> normalizeRecipients(JSONArray recipients) {
    ArrayList<String> result;
    LinkedHashSet<String> seen;
    int index;
    String value;

    result = new ArrayList<String>();
    seen = new LinkedHashSet<String>();
    if (recipients == null) {
      return result;
    }

    for (index = 0; index < recipients.length(); index++) {
      value = normalizeAddress(recipients.opt(index));
      if (!TextUtils.isEmpty(value) && !seen.contains(value)) {
        seen.add(value);
        result.add(value);
      }
    }

    return result;
  }

  private String normalizeAddress(Object value) {
    boolean looksLikePhoneAddress;
    String result;
    String normalizedNumber;

    result = safeString(value).trim();
    if (TextUtils.isEmpty(result)) {
      return "";
    }

    if (result.startsWith("tel:")) {
      result = result.substring(4).trim();
    }

    if (result.startsWith("mailto:")) {
      result = result.substring(7).trim();
    }

    if (result.indexOf('@') >= 0) {
      return result.toLowerCase(Locale.US);
    }

    looksLikePhoneAddress = result.indexOf('+') >= 0 || containsDigits(result);
    if (!looksLikePhoneAddress) {
      return result.toLowerCase(Locale.US);
    }

    normalizedNumber = PhoneNumberUtils.normalizeNumber(result);
    if (!TextUtils.isEmpty(normalizedNumber)) {
      return normalizedNumber;
    }

    return result.toLowerCase(Locale.US);
  }

  private boolean containsDigits(String value) {
    int index;

    if (TextUtils.isEmpty(value)) {
      return false;
    }

    for (index = 0; index < value.length(); index++) {
      if (Character.isDigit(value.charAt(index))) {
        return true;
      }
    }

    return false;
  }

  private long parseProviderIdFromUri(Uri uri) {
    if (uri == null) {
      return 0L;
    }

    return parseLastPathId(uri);
  }

  private String resolveMessageSnapshotKey(String source, Uri uri, JSONObject message) {
    long providerId;

    if (message != null && !TextUtils.isEmpty(message.optString("id"))) {
      return message.optString("id");
    }

    providerId = parseProviderIdFromUri(uri);
    if (providerId <= 0L || (!"sms".equals(source) && !"mms".equals(source))) {
      return "";
    }

    return source + ":" + providerId;
  }

  private String resolveThreadSnapshotKey(Uri uri, JSONObject thread, JSONObject relatedMessage) {
    long threadId;

    if (thread != null && !TextUtils.isEmpty(thread.optString("threadKey"))) {
      return thread.optString("threadKey");
    }

    if (relatedMessage != null && !TextUtils.isEmpty(relatedMessage.optString("threadKey"))) {
      return relatedMessage.optString("threadKey");
    }

    threadId = parseProviderIdFromUri(uri);
    return threadId > 0L ? String.valueOf(threadId) : "";
  }

  private String resolveChangeType(JSONObject previousValue, JSONObject currentValue, String previousSignature, String currentSignature) {
    if (previousValue == null && currentValue == null) {
      return "";
    }

    if (previousValue == null) {
      return "inserted";
    }

    if (currentValue == null) {
      return "deleted";
    }

    if (!safeString(previousSignature).equals(safeString(currentSignature))) {
      return "updated";
    }

    return "";
  }

  private String buildMessageSnapshotSignature(JSONObject message) {
    if (message == null) {
      return "";
    }

    return safeString(message.opt("id"))
      + "|" + safeString(message.opt("threadKey"))
      + "|" + safeString(message.opt("sortDate"))
      + "|" + safeString(message.opt("date"))
      + "|" + safeString(message.opt("read"))
      + "|" + safeString(message.opt("status"))
      + "|" + safeString(message.opt("body"))
      + "|" + safeString(message.opt("subject"))
      + "|" + safeString(message.opt("address"))
      + "|" + safeString(message.opt("attachmentCount"));
  }

  private String buildThreadSnapshotSignature(JSONObject thread) {
    if (thread == null) {
      return "";
    }

    return safeString(thread.opt("threadKey"))
      + "|" + safeString(thread.opt("date"))
      + "|" + safeString(thread.opt("messageCount"))
      + "|" + safeString(thread.opt("read"))
      + "|" + safeString(thread.opt("snippet"))
      + "|" + joinJsonArray(thread.optJSONArray("addresses"), "|");
  }

  private JSONArray toJsonArray(List<String> values) {
    JSONArray result;
    int index;

    result = new JSONArray();
    for (index = 0; index < values.size(); index++) {
      result.put(values.get(index));
    }

    return result;
  }

  private String joinStringList(List<String> values, String separator) {
    StringBuilder builder;
    int index;

    builder = new StringBuilder();
    for (index = 0; index < values.size(); index++) {
      if (TextUtils.isEmpty(values.get(index))) {
        continue;
      }

      if (builder.length() > 0) {
        builder.append(separator);
      }

      builder.append(values.get(index));
    }

    return builder.toString();
  }

  private String readContentText(Uri uri) {
    ContentResolver resolver;
    InputStream stream;
    BufferedReader reader;
    StringBuilder builder;
    String line;

    resolver = cordova.getActivity().getContentResolver();
    stream = null;
    reader = null;
    builder = new StringBuilder();

    try {
      stream = resolver.openInputStream(uri);
      if (stream == null) {
        return "";
      }

      reader = new BufferedReader(new InputStreamReader(stream));
      while ((line = reader.readLine()) != null) {
        if (builder.length() > 0) {
          builder.append('\n');
        }

        builder.append(line);
      }

      return builder.toString();
    } catch (Exception ignored) {
      return "";
    } finally {
      try {
        if (reader != null) {
          reader.close();
        }
      } catch (Exception ignored) {
      }

      try {
        if (stream != null) {
          stream.close();
        }
      } catch (Exception ignored) {
      }
    }
  }

  private long parseLastPathId(Uri uri) {
    if (uri == null || TextUtils.isEmpty(uri.getLastPathSegment())) {
      return 0L;
    }

    try {
      return Long.parseLong(uri.getLastPathSegment());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private String safeString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String safeCharSequence(CharSequence value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String safeSubscriptionNumber(SubscriptionInfo info) {
    try {
      return info == null ? "" : safeString(info.getNumber());
    } catch (Exception ignored) {
      return "";
    }
  }

  private String firstNonEmpty(String first, String second) {
    if (!TextUtils.isEmpty(first)) {
      return first;
    }

    return second;
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

  private static JSONObject copyJson(JSONObject value) {
    try {
      return value == null ? new JSONObject() : new JSONObject(value.toString());
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  private static final class MessageReference {
    private final String kind;
    private final long providerId;
    private final String messageId;
    private final Uri uri;

    private MessageReference(String kind, long providerId, String messageId, Uri uri) {
      this.kind = kind;
      this.providerId = providerId;
      this.messageId = messageId;
      this.uri = uri;
    }
  }
}
