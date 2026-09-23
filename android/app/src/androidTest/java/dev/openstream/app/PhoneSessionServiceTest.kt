package dev.openstream.app

import android.Manifest
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
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
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
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
    fun revokingCameraPermissionStopsAndReleasesTheOwnedSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PhoneSessionService::class.java))
            as PhoneSessionService.LocalBinder
        try {
            instrumentation.uiAutomation.executeShellCommand(
                "pm revoke ${context.packageName} ${Manifest.permission.CAMERA}",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).readBytes() }
            binder.activityResumed()

            assertEquals(SessionOwnerState.PermissionRequired, binder.lifecycleState())
            assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
            assertEquals(null, binder.runtime().reservationState.confirmedSourceInstanceId)
        } finally {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.CAMERA}",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).readBytes() }
            activity.close()
        }
    }

    @Test
    fun startWithoutCameraPermissionKeepsAnExplicitStopAndDoesNotStartTheOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val binder = serviceRule.bindService(Intent(context, PhoneSessionService::class.java))
            as PhoneSessionService.LocalBinder
        binder.stop()
        try {
            instrumentation.uiAutomation.executeShellCommand(
                "pm revoke ${context.packageName} ${Manifest.permission.CAMERA}",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).readBytes() }
            binder.start()

            assertEquals(SessionOwnerState.PermissionRequired, binder.lifecycleState())
            assertEquals(PhoneSessionStatus.Stopped, binder.runtime().phoneSessionState.snapshot.status)
            assertEquals(
                true,
                context.getSharedPreferences(PhoneSessionService.PREFS_NAME, 0)
                    .getBoolean(PhoneSessionService.KEY_STOPPED, false),
            )
        } finally {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.CAMERA}",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).readBytes() }
            activity.close()
        }
    }
}
