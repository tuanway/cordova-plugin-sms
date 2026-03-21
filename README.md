# cordova-plugin-sms-device

Standalone Android Cordova plugin scaffold for reading device SMS and MMS data, watching provider changes, searching threads/messages, resolving contacts, exporting attachments, sending SMS, and handing off or dispatching MMS.

## Current scope

- Runtime permission checks and requests
- Default-SMS-app detection and request flow
- Android default-SMS-app manifest components for `SMS_DELIVER`, `WAP_PUSH_DELIVER`, `SENDTO`, and `RESPOND_VIA_MESSAGE`
- Active SIM and subscription listing
- Foreground background-watch service for provider-change event continuity, including restart after reboot or app update when enabled
- Thread list reads from the Android SMS/MMS provider
- Thread and message search with `offset`, `limit`, `search`, `dateFrom`, `dateTo`, and `unreadOnly`
- MMS attachment metadata and image/content URIs for preview
- Incoming SMS and richer incoming MMS event delivery
- Live `providerChanged`, `messageChanged`, and `threadChanged` events while watching, with `changeType` values for inserted, updated, and deleted rows when the provider URI resolves cleanly
- SMS sending through `SmsManager`
- Sent and delivered status callbacks through the watch event stream
- `subscriptionId` or `simSlotIndex` selection for SMS send
- MMS compose intent handoff
- MMS direct send path when the caller provides a prepared `pduUri`
- Thread and message mutations: `markRead`, `markUnread`, `deleteThread`, `deleteMessage`, `restoreMessage`
- Draft operations: `saveDraft`, `listDrafts`, `updateDraft`, `deleteDraft`, `moveToDraft`
- Failed-SMS resend helper
- Contact search and address resolution
- Attachment export and thumbnail generation
- Local per-thread settings storage

## Not in scope

- iOS device message reads
- Full PDU composition for silent MMS sends from raw attachment arrays
- RCS/chat features

## Install

```bash
cordova plugin add /home/tuan/Shared/Source/git/cordova_sms
```

## JavaScript API

```js
cordova.plugins.sms.hasPermissions(success, error);
cordova.plugins.sms.requestPermissions(success, error);

cordova.plugins.sms.isDefaultSmsApp(success, error);
cordova.plugins.sms.requestDefaultSmsApp(success, error);

cordova.plugins.sms.getSubscriptions(success, error);

cordova.plugins.sms.on("incomingSms", handler);
cordova.plugins.sms.on("incomingMms", handler);
cordova.plugins.sms.on("providerChanged", handler);
cordova.plugins.sms.on("messageChanged", handler);
cordova.plugins.sms.on("threadChanged", handler);
cordova.plugins.sms.on("smsSentStatus", handler);
cordova.plugins.sms.on("smsDeliveryStatus", handler);
cordova.plugins.sms.on("mmsSentStatus", handler);
cordova.plugins.sms.startWatching(options, error);
cordova.plugins.sms.stopWatching(success, error);

cordova.plugins.sms.startBackgroundWatch(options, success, error);
cordova.plugins.sms.stopBackgroundWatch(success, error);
cordova.plugins.sms.getBackgroundWatchState(success, error);

cordova.plugins.sms.listThreads(options, success, error);
cordova.plugins.sms.searchThreads(options, success, error);
cordova.plugins.sms.listMessages(options, success, error);
cordova.plugins.sms.searchMessages(options, success, error);
cordova.plugins.sms.getThread(threadKey, options, success, error);

cordova.plugins.sms.sendSms(recipients, message, options, success, error);
cordova.plugins.sms.sendMms(recipients, message, attachments, options, success, error);

cordova.plugins.sms.markRead(options, success, error);
cordova.plugins.sms.markUnread(options, success, error);
cordova.plugins.sms.deleteThread(threadKey, success, error);
cordova.plugins.sms.deleteMessage(messageId, options, success, error);
cordova.plugins.sms.restoreMessage(messageIdOrBackupId, options, success, error);

cordova.plugins.sms.saveDraft(recipients, message, options, success, error);
cordova.plugins.sms.listDrafts(options, success, error);
cordova.plugins.sms.updateDraft(messageId, message, options, success, error);
cordova.plugins.sms.deleteDraft(messageId, success, error);
cordova.plugins.sms.moveToDraft(messageId, options, success, error);

cordova.plugins.sms.resendFailed(messageId, options, success, error);

cordova.plugins.sms.getContacts(options, success, error);
cordova.plugins.sms.resolveAddresses(addresses, options, success, error);

cordova.plugins.sms.exportAttachment(attachment, options, success, error);
cordova.plugins.sms.createAttachmentThumbnail(attachment, options, success, error);

cordova.plugins.sms.getThreadSettings(threadKey, success, error);
cordova.plugins.sms.setThreadSettings(threadKey, settings, options, success, error);
```

## Event types

- `incomingSms`
- `incomingMms`
- `providerChanged`
- `messageChanged`
- `threadChanged`
- `smsSentStatus`
- `smsDeliveryStatus`
- `mmsSentStatus`

`providerChanged`, `messageChanged`, and `threadChanged` include a `background` flag when they come from the foreground service. `messageChanged` and `threadChanged` also include `changeType` when the plugin can distinguish inserts, updates, and deletes from the observed provider URI.

## Important Android constraints

- Android will only grant the SMS role if the built app includes the required SMS-app manifest components. This plugin now contributes those components, but you still need to rebuild the Cordova app so they are merged into the final `AndroidManifest.xml`.
- `threadKey` is the Android `thread_id` serialized as a string.
- MMS reads depend on the platform message provider and `READ_SMS`.
- MMS direct send only works if the caller provides a prepared `pduUri`; raw attachment arrays still fall back to compose-intent handoff.
- On modern Android, `markRead`, `markUnread`, `deleteThread`, `deleteMessage`, `restoreMessage`, `saveDraft`, `updateDraft`, `deleteDraft`, and `moveToDraft` require the app to be the default SMS app.
- SIM and subscription reads use `READ_PHONE_STATE`.
- Contact resolution and contact search use `READ_CONTACTS`.

## Useful options

- `search`
- `offset`
- `limit`
- `order`
- `dateFrom`
- `dateTo`
- `unreadOnly`
- `resolveContacts`
- `subscriptionId`
- `simSlotIndex`
- `requestId`
- `trackDelivery`
- `pduUri`
- `configOverrides`
