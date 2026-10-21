package dev.openstream.app.irl

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Foreground service owning IRL capture, encoding and publishing. Stub; owned by the service workstream. */
class IrlStreamService : Service() {
    inner class LocalBinder : Binder() {
        val api: IrlServiceApi get() = TODO()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    companion object {
        const val ACTION_STOP = "dev.openstream.app.irl.STOP"

        /** Starts (not yet foreground) so the activity can bind; call while visible. */
        fun intent(context: Context): Intent = Intent(context, IrlStreamService::class.java)
    }
}
