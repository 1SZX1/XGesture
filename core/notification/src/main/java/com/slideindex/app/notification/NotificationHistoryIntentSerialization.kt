package com.slideindex.app.notification

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import android.util.Log

internal object NotificationHistoryIntentSerialization {
    const val TAG = "NotifHistoryCapture"
    val URI_FLAGS = Intent.URI_INTENT_SCHEME or Intent.URI_ALLOW_UNSAFE

    /**
     * 同类序列化失败只在首次打印完整堆栈。
     *
     * 一条通知通常带 1 个 contentIntent + 3~4 个 action，如果每个都失败一次并打整段异常，
     * 每条通知就是 5 段堆栈（线上实测日志占比极高），纯属白烧 CPU。
     */
    private val loggedFailureKinds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun logSerializationFailure(kind: String, source: String, error: Throwable) {
        if (loggedFailureKinds.add(kind)) {
            Log.w(TAG, "serialize $kind failed source=$source（后续同类失败只记一行）", error)
        } else {
            Log.d(
                TAG,
                "serialize $kind failed source=$source reason=${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    fun serializeIntentUri(intent: Intent, source: String): String? {
        return runCatching {
            Intent(intent).toUri(URI_FLAGS)
        }.onSuccess { uri ->
            Log.d(TAG, "Captured URI $source: ${uri.take(120)}")
        }.onFailure { error ->
            logSerializationFailure("intent-uri", source, error)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun serializeIntentParcel(intent: Intent, source: String): String? {
        return runCatching {
            val parcel = Parcel.obtain()
            try {
                Intent(intent).writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        }.onSuccess {
            Log.d(TAG, "Captured parcel $source (${it.length} chars)")
        }.onFailure { error ->
            logSerializationFailure("intent-parcel", source, error)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun serializeExtras(intent: Intent, source: String): String? {
        val extras = intent.extras ?: return null
        if (extras.isEmpty) return null
        return serializeBundle(extras, "intent.$source")
    }

    fun serializePendingIntent(pendingIntent: PendingIntent, source: String): String? {
        // PendingIntent 内部持有 IIntentSender(Binder)，Parcel.marshall() 必然抛
        // "Tried to marshall a Parcel that contains objects (binders or FDs)"：
        // 这条路在结构上就不可能成功，而以前对每条通知的每个 action 都要失败一次并打全栈。
        // 调用方只把结果当"能不能重放点击"的诊断字段，直接返回 null 与原来等价，但不再烧 CPU。
        Log.d(
            TAG,
            "Skip PendingIntent parcel $source (binder-backed, creator=" +
                runCatching { pendingIntent.creatorPackage }.getOrNull() + ")",
        )
        return null
    }

    fun serializeBundle(bundle: Bundle?, source: String): String? {
        if (bundle == null || bundle.isEmpty) return null
        return runCatching {
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        }.onSuccess {
            Log.d(TAG, "Captured bundle $source (${it.length} chars)")
        }.onFailure { error ->
            logSerializationFailure("bundle", source, error)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun deserializeBundle(bundleBase64: String?): Bundle? {
        if (bundleBase64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(bundleBase64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Bundle.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to deserialize bundle", error)
        }.getOrNull()
    }

    fun deserializeIntentParcel(intentParcelBase64: String?): Intent? {
        if (intentParcelBase64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(intentParcelBase64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to deserialize intent parcel", error)
        }.getOrNull()
    }

    fun deserializePendingIntent(pendingIntentBase64: String?): PendingIntent? {
        if (pendingIntentBase64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(pendingIntentBase64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                PendingIntent.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to deserialize PendingIntent", error)
        }.getOrNull()
    }

    fun parseIntentUri(intentUri: String): Intent? {
        return runCatching {
            Intent.parseUri(intentUri, URI_FLAGS)
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse stored intent URI (intent scheme)", error)
        }.getOrNull() ?: runCatching {
            Intent.parseUri(
                intentUri,
                Intent.URI_ANDROID_APP_SCHEME or Intent.URI_ALLOW_UNSAFE,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse stored intent URI (android-app scheme)", error)
        }.getOrNull()
    }

    fun mergeExtras(intent: Intent, intentExtrasBase64: String?) {
        val extras = deserializeBundle(intentExtrasBase64) ?: return
        val current = intent.extras
        if (current == null || current.isEmpty) {
            intent.replaceExtras(extras)
            return
        }
        intent.putExtras(extras)
    }

    fun serializeNotificationExtras(extras: Bundle?): String? {
        if (extras == null || extras.isEmpty) return null
        if (extras.containsKey("android.mediaSession") || extras.containsKey("android.mediaRemoteControlClient")) {
            val filtered = filterSerializableExtras(extras) ?: return null
            return serializeBundle(filtered, "notification.extras.filtered")
        }
        serializeBundle(extras, "notification.extras")?.let { return it }
        val filtered = filterSerializableExtras(extras) ?: return null
        return serializeBundle(filtered, "notification.extras.filtered")
    }

    fun <T : android.os.Parcelable> getParcelableCompat(bundle: Bundle, key: String, clazz: Class<T>): T? {
        return com.slideindex.app.util.BundleParcelCompat.getParcelable(bundle, key, clazz)
    }

    private fun filterSerializableExtras(extras: Bundle?): Bundle? {
        if (extras == null || extras.isEmpty) return null
        val filtered = Bundle()
        for (key in extras.keySet()) {
            when (val value = com.slideindex.app.util.BundleParcelCompat.getValue(extras, key)) {
                null -> Unit
                is CharSequence -> filtered.putString(key, value.toString())
                is String -> filtered.putString(key, value)
                is Int -> filtered.putInt(key, value)
                is Long -> filtered.putLong(key, value)
                is Boolean -> filtered.putBoolean(key, value)
                is Bundle -> filtered.putBundle(key, value)
                is Intent -> filtered.putParcelable(key, Intent(value))
                is PendingIntent -> filtered.putParcelable(key, value)
            }
        }
        return filtered.takeIf { !it.isEmpty }
    }
}
