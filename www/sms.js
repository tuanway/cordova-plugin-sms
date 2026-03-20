cordova.define("com.cordova.sms.sms", function(require, exports, module) {
  var exec;
  var eventHandlers;
  var isWatching;
  var watchErrorHandler;

  exec = require("cordova/exec");
  eventHandlers = {};
  isWatching = false;
  watchErrorHandler = null;

  function normalizeOptions(options, success, error) {
    if (typeof options === "function") {
      return {
        options: {},
        success: options,
        error: success
      };
    }

    return {
      options: options || {},
      success: success,
      error: error
    };
  }

  function normalizeRecipients(recipients) {
    if (!recipients) {
      return [];
    }

    if (typeof recipients === "string") {
      return [recipients];
    }

    if (Object.prototype.toString.call(recipients) === "[object Array]") {
      return recipients;
    }

    return [];
  }

  function normalizeAttachments(attachments) {
    if (!attachments) {
      return [];
    }

    if (Object.prototype.toString.call(attachments) === "[object Array]") {
      return attachments;
    }

    return [];
  }

  function normalizeAddresses(addresses) {
    if (!addresses) {
      return [];
    }

    if (typeof addresses === "string") {
      return [addresses];
    }

    if (Object.prototype.toString.call(addresses) === "[object Array]") {
      return addresses;
    }

    return [];
  }

  function getEventHandlerList(eventName) {
    if (!eventHandlers[eventName]) {
      eventHandlers[eventName] = [];
    }

    return eventHandlers[eventName];
  }

  function dispatchEvent(event) {
    var handlers;
    var wildcardHandlers;
    var index;

    handlers = [];
    wildcardHandlers = getEventHandlerList("*").slice(0);

    if (event && event.type) {
      handlers = getEventHandlerList(event.type).slice(0);
    }

    for (index = 0; index < handlers.length; index++) {
      handlers[index](event);
    }

    for (index = 0; index < wildcardHandlers.length; index++) {
      wildcardHandlers[index](event);
    }
  }

  function startNativeWatch(options, error) {
    if (isWatching) {
      return;
    }

    isWatching = true;
    watchErrorHandler = error;

    exec(
      function(event) {
        dispatchEvent(event);
      },
      function(nativeError) {
        isWatching = false;
        if (typeof watchErrorHandler === "function") {
          watchErrorHandler(nativeError);
        }
      },
      "Sms",
      "watch",
      [options || {}]
    );
  }

  module.exports = {
    hasPermissions: function(success, error) {
      exec(success, error, "Sms", "hasPermissions", []);
    },

    requestPermissions: function(success, error) {
      exec(success, error, "Sms", "requestPermissions", []);
    },

    isDefaultSmsApp: function(success, error) {
      exec(success, error, "Sms", "isDefaultSmsApp", []);
    },

    requestDefaultSmsApp: function(success, error) {
      exec(success, error, "Sms", "requestDefaultSmsApp", []);
    },

    requestDefaultSmsRole: function(success, error) {
      module.exports.requestDefaultSmsApp(success, error);
    },

    getSubscriptions: function(success, error) {
      exec(success, error, "Sms", "getSubscriptions", []);
    },

    startWatching: function(options, error) {
      if (typeof options === "function") {
        error = options;
        options = {};
      }

      startNativeWatch(options || {}, error);
    },

    watch: function(options, error) {
      module.exports.startWatching(options, error);
    },

    stopWatching: function(success, error) {
      isWatching = false;
      exec(success, error, "Sms", "unwatch", []);
    },

    startBackgroundWatch: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "startBackgroundWatch", [normalized.options]);
    },

    stopBackgroundWatch: function(success, error) {
      exec(success, error, "Sms", "stopBackgroundWatch", []);
    },

    getBackgroundWatchState: function(success, error) {
      exec(success, error, "Sms", "getBackgroundWatchState", []);
    },

    on: function(eventName, handler) {
      if (typeof handler !== "function" || !eventName) {
        return;
      }

      getEventHandlerList(eventName).push(handler);
    },

    off: function(eventName, handler) {
      var handlers;
      var index;

      if (!eventHandlers[eventName]) {
        return;
      }

      if (typeof handler !== "function") {
        eventHandlers[eventName] = [];
        return;
      }

      handlers = eventHandlers[eventName];
      for (index = handlers.length - 1; index >= 0; index--) {
        if (handlers[index] === handler) {
          handlers.splice(index, 1);
        }
      }
    },

    listThreads: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "listThreads", [normalized.options]);
    },

    searchThreads: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "searchThreads", [normalized.options]);
    },

    listMessages: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "listMessages", [normalized.options]);
    },

    searchMessages: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "searchMessages", [normalized.options]);
    },

    getThread: function(threadKey, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.threadKey = threadKey;
      exec(normalized.success, normalized.error, "Sms", "getThread", [normalized.options]);
    },

    sendSms: function(recipients, message, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.recipients = normalizeRecipients(recipients);
      normalized.options.message = message || "";
      exec(normalized.success, normalized.error, "Sms", "sendSms", [normalized.options]);
    },

    sendMms: function(recipients, message, attachments, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.recipients = normalizeRecipients(recipients);
      normalized.options.message = message || "";
      normalized.options.attachments = normalizeAttachments(attachments);
      exec(normalized.success, normalized.error, "Sms", "sendMms", [normalized.options]);
    },

    sendMmsIntent: function(recipients, message, attachments, options, success, error) {
      module.exports.sendMms(recipients, message, attachments, options, success, error);
    },

    markRead: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "markRead", [normalized.options]);
    },

    markUnread: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "markUnread", [normalized.options]);
    },

    deleteThread: function(threadKey, success, error) {
      exec(success, error, "Sms", "deleteThread", [{ threadKey: threadKey }]);
    },

    deleteMessage: function(messageId, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.messageId = messageId;
      exec(normalized.success, normalized.error, "Sms", "deleteMessage", [normalized.options]);
    },

    restoreMessage: function(messageId, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.messageId = messageId;
      exec(normalized.success, normalized.error, "Sms", "restoreMessage", [normalized.options]);
    },

    saveDraft: function(recipients, message, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.recipients = normalizeRecipients(recipients);
      normalized.options.message = message || "";
      exec(normalized.success, normalized.error, "Sms", "saveDraft", [normalized.options]);
    },

    listDrafts: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "listDrafts", [normalized.options]);
    },

    updateDraft: function(messageId, message, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.messageId = messageId;
      normalized.options.message = message || "";
      exec(normalized.success, normalized.error, "Sms", "updateDraft", [normalized.options]);
    },

    deleteDraft: function(messageId, success, error) {
      exec(success, error, "Sms", "deleteDraft", [{ messageId: messageId }]);
    },

    moveToDraft: function(messageId, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.messageId = messageId;
      exec(normalized.success, normalized.error, "Sms", "moveToDraft", [normalized.options]);
    },

    resendFailed: function(messageId, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.messageId = messageId;
      exec(normalized.success, normalized.error, "Sms", "resendFailed", [normalized.options]);
    },

    getContacts: function(options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      exec(normalized.success, normalized.error, "Sms", "getContacts", [normalized.options]);
    },

    resolveAddresses: function(addresses, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.addresses = normalizeAddresses(addresses);
      exec(normalized.success, normalized.error, "Sms", "resolveAddresses", [normalized.options]);
    },

    exportAttachment: function(attachment, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.attachment = attachment || {};
      exec(normalized.success, normalized.error, "Sms", "exportAttachment", [normalized.options]);
    },

    createAttachmentThumbnail: function(attachment, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.attachment = attachment || {};
      exec(normalized.success, normalized.error, "Sms", "createAttachmentThumbnail", [normalized.options]);
    },

    getThreadSettings: function(threadKey, success, error) {
      exec(success, error, "Sms", "getThreadSettings", [{ threadKey: threadKey }]);
    },

    setThreadSettings: function(threadKey, settings, options, success, error) {
      var normalized;

      normalized = normalizeOptions(options, success, error);
      normalized.options.threadKey = threadKey;
      normalized.options.settings = settings || {};
      exec(normalized.success, normalized.error, "Sms", "setThreadSettings", [normalized.options]);
    }
  };
});
