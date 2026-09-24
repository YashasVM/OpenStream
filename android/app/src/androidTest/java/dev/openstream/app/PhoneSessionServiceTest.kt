package dev.openstream.app

import android.Manifest
import android.os.Build
import android.app.NotificationManager
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSessionServiceTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray(),
    )

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Before
    fun clearPriorStopState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PhoneSessionService.PREFS_NAME, 0).edit()
            .putBoolean(PhoneSessionService.KEY_STOPPED, false)
            .commit()
    }

    @Test
    fun serviceKeepsTheSameOwnerAcrossActivityRecreationAndStopIsFinal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PhoneSessionService::class.java))
            as PhoneSessionService.LocalBinder
        val originalRuntime = binder.runtime()
        waitForOwnerState(binder, SessionOwnerState.Foreground(null))
        activity.moveToState(Lifecycle.State.CREATED)
        assertEquals(SessionOwnerState.Background(null), binder.lifecycleState())
        activity.moveToState(Lifecycle.State.RESUMED)
        assertEquals(SessionOwnerState.Foreground(null), binder.lifecycleState())
        activity.recreate()
        assertSame(originalRuntime, binder.runtime())

        binder.stop()
        assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
        binder.activityResumed()
        assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
        activity.close()
    }

    @Test
    fun stoppedActivityRecreationDoesNotRestartForegroundServiceOrSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        context.getSharedPreferences(PhoneSessionService.PREFS_NAME, 0).edit()
            .putBoolean(PhoneSessionService.KEY_STOPPED, true)
            .commit()

        ActivityScenario.launch(MainActivity::class.java).close()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(6_500)

        activity.onActivity { current ->
            val status = current.findViewById<android.widget.TextView>(R.id.statusText).text
            assertEquals(context.getString(R.string.status_stopped), status)
        }
        val notifications = notificationManager.activeNotifications
        assertEquals(emptyList<Int>(), notifications.map { it.id })
        assertEquals(true, context.getSharedPreferences(PhoneSessionService.PREFS_NAME, 0)
            .getBoolean(PhoneSessionService.KEY_STOPPED, false))
        activity.close()
    }

    @Test
    fun revokingCameraPermissionStopsAndReleasesTheOwnedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PhoneSessionService::class.java))
            as PhoneSessionService.LocalBinder
        binder.activityResumed(cameraPermissionGranted = false)

        assertEquals(SessionOwnerState.PermissionRequired, binder.lifecycleState())
        assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
        assertEquals(null, binder.runtime().reservationState.confirmedSourceInstanceId)
        activity.close()
    }

    @Test
    fun startWithoutCameraPermissionKeepsAnExplicitStopAndDoesNotStartTheOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binder = serviceRule.bindService(Intent(context, PhoneSessionService::class.java))
            as PhoneSessionService.LocalBinder
        binder.start(cameraPermissionGranted = false)

        assertEquals(SessionOwnerState.PermissionRequired, binder.lifecycleState())
        assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
        assertEquals(
            true,
            context.getSharedPreferences(PhoneSessionService.PREFS_NAME, 0)
                .getBoolean(PhoneSessionService.KEY_STOPPED, false),
        )
    }

    private fun waitForOwnerState(
        binder: PhoneSessionService.LocalBinder,
        expected: SessionOwnerState,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (binder.lifecycleState() != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertEquals(expected, binder.lifecycleState())
    }
}
