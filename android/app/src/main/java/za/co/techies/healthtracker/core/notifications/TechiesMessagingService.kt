// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Set Up a Firebase Cloud Messaging Client App on Android. [Online]. Available at:
//    https://firebase.google.com/docs/cloud-messaging/android/client [Accessed 25 September 2026].
//   Google. 2024. Receive Messages in an Android App. [Online]. Available at:
//    https://firebase.google.com/docs/cloud-messaging/android/receive [Accessed 25 September 2026].
//   Google. 2024. Create and Manage Notification Channels. [Online]. Available at:
//    https://developer.android.com/develop/ui/views/notifications/channels
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.6 (Requirement 6:
//    Real-time notifications) and section 5.4.9 (Notification events).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import za.co.techies.healthtracker.MainActivity
import za.co.techies.healthtracker.R
import kotlin.random.Random

/**
 * Receives push messages from Firebase Cloud Messaging (Requirement 6).
 *
 * The server sends **data messages**, not notification messages. A notification message is drawn
 * by the system and only delivered to this class when the app is in the foreground, which would
 * mean the app has no say over how a missed-dose alert looks or where it leads when the phone is
 * locked in someone's pocket — which is precisely when it matters. A data message is always
 * delivered here, so one code path builds every notification.
 */
class TechiesMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val category = data[KeyCategory].orEmpty()
        val title = data[KeyTitle] ?: message.notification?.title ?: "Techies Health Tracker"
        val body = data[KeyBody] ?: message.notification?.body.orEmpty()

        Log.i(TAG, "Push received (category=$category)")

        NotificationPresenter(applicationContext).show(
            category = category,
            title = title,
            body = body,
            deepLink = data[KeyDeepLink]
        )
    }

    /**
     * Fires when Firebase issues a new registration token — on install, on reinstall, and when
     * the app's data is cleared.
     *
     * Nothing is sent from here. The token is useless to the server until it can be attached to
     * an authenticated user, and at this moment nobody may be signed in. `POST /api/auth/login`
     * carries the current token on every launch, which is the one place it is guaranteed to have
     * somebody to attach it to.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM registration token issued; it will be sent on the next login")
    }

    companion object {
        private const val TAG = "TechiesMessaging"

        // The payload keys the API's push sender uses. Kept here so the two sides have one
        // place to disagree rather than several.
        const val KeyCategory = "category"
        const val KeyTitle = "title"
        const val KeyBody = "body"
        const val KeyDeepLink = "deepLink"
    }
}

/**
 * Builds and posts the notification.
 *
 * Separate from the service so the same code runs whether a message arrived over the air or the
 * app raised something locally, and so it can be exercised without Firebase.
 */
class NotificationPresenter(private val context: Context) {

    /**
     * Channels are per category rather than one for everything. A missed-dose alert and a refill
     * reminder are not equally urgent, and Android only lets the user tune importance per
     * channel — one channel would mean silencing the refill nags also silences the alerts that
     * matter.
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Creating a channel that already exists updates its name and description rather than
        // failing, and this runs before every notification, so the names follow a language change
        // without the app having to delete and recreate the channels.
        NotificationChannels.all.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(
                    channel.id,
                    context.getString(channel.labelRes),
                    channel.importance
                ).apply {
                    description = context.getString(channel.descriptionRes)
                }
            )
        }
    }

    fun show(category: String, title: String, body: String, deepLink: String?) {
        ensureChannels()

        if (!hasPermission()) {
            // From Android 13 the user can refuse notifications outright. The server still has a
            // record of the event, and the in-app bell still shows it, so nothing is lost.
            Log.w(TAG, "POST_NOTIFICATIONS not granted; the event is still visible in the app")
            return
        }

        val channel = NotificationChannels.forCategory(category)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra(ExtraDeepLink, it) }
        }

        val pending = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            // Without this a message longer than one line is silently truncated, and a missed
            // dose alert naming the medication and the dependant is easily longer than one line.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(channel.compatPriority)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(Random.nextInt(), notification)
        }.onFailure {
            Log.w(TAG, "Could not post the notification", it)
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "NotificationPresenter"
        const val ExtraDeepLink = "techies.deepLink"
    }
}

/** The notification channels, matching the categories the API raises. */
data class TechiesChannel(
    val id: String,
    /**
     * Resource ids rather than text. Android shows these in system Settings, and a channel created
     * with an English name would keep it there after the caregiver switched language.
     */
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val importance: Int,
    val compatPriority: Int
)

object NotificationChannels {

    val DoseReminder = TechiesChannel(
        id = "dose_reminder",
        labelRes = R.string.channel_dose_reminder,
        descriptionRes = R.string.channel_dose_reminder_detail,
        importance = NotificationManager.IMPORTANCE_HIGH,
        compatPriority = NotificationCompat.PRIORITY_HIGH
    )

    val MissedDose = TechiesChannel(
        id = "missed_dose",
        labelRes = R.string.channel_missed_dose,
        descriptionRes = R.string.channel_missed_dose_detail,
        importance = NotificationManager.IMPORTANCE_HIGH,
        compatPriority = NotificationCompat.PRIORITY_HIGH
    )

    val RefillAlert = TechiesChannel(
        id = "refill_alert",
        labelRes = R.string.channel_refill_alert,
        descriptionRes = R.string.channel_refill_alert_detail,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        compatPriority = NotificationCompat.PRIORITY_DEFAULT
    )

    val AppointmentReminder = TechiesChannel(
        id = "appointment_reminder",
        labelRes = R.string.channel_appointment,
        descriptionRes = R.string.channel_appointment_detail,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        compatPriority = NotificationCompat.PRIORITY_DEFAULT
    )

    val all = listOf(DoseReminder, MissedDose, RefillAlert, AppointmentReminder)

    /**
     * Maps a server category onto a channel, falling back rather than dropping the message.
     * A category this build does not recognise is still worth showing — the alternative is a
     * silent failure whenever the API gains a new one before the app is updated.
     */
    fun forCategory(category: String): TechiesChannel = when (category) {
        "dose_reminder" -> DoseReminder
        "missed_dose" -> MissedDose
        "refill_alert" -> RefillAlert
        "appointment_reminder" -> AppointmentReminder
        else -> DoseReminder
    }
}
