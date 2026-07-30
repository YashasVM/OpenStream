package dev.openstream.app.update

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdater(
    private val activity: Activity,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val downloadManager = activity.getSystemService(DownloadManager::class.java)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val updatePrefs = activity.getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE)
    private var pendingDownloadId: Long = NO_DOWNLOAD
    private var pendingRelease: ReleaseUpdate? = null
    private var registered = false
    private var verifyingDownloadId: Long = NO_DOWNLOAD
    private var updateDialog: Dialog? = null
    private var downloadProgressRunnable: Runnable? = null
    private val disposed = AtomicBoolean(false)

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID,
                NO_DOWNLOAD,
            )
            if (completedId != pendingDownloadId) return
            verifyDownloadedApk()
        }
    }

    fun register() {
        if (disposed.get() || registered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(downloadReceiver, filter)
        }
        registered = true
        restorePendingDownload()
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(downloadReceiver) }
        registered = false
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        unregister()
        stopProgressPolling()
        updateDialog?.dismiss()
        updateDialog = null
        executor.shutdownNow()
    }

    fun checkForUpdates(showAlreadyCurrent: Boolean = false) {
        if (disposed.get()) return
        if (pendingDownloadId != NO_DOWNLOAD) {
            resumePendingInstallIfAllowed()
            return
        }
        submitUpdateWork {
            val result = runCatching { fetchLatestRelease() }
            runWhenActivityIsActive {
                result
                    .onSuccess { release ->
                        if (release.isNewerThan(currentVersionName(), currentVersionCode())) {
                            showUpdatePrompt(release)
                        } else if (showAlreadyCurrent) {
                            Toast.makeText(activity, "OpenStream is up to date", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Update check failed", error)
                        if (showAlreadyCurrent) {
                            Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    fun resumePendingInstallIfAllowed() {
        if (disposed.get() || pendingDownloadId == NO_DOWNLOAD) return
        when (downloadSnapshot(pendingDownloadId)?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> verifyDownloadedApk()
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> pendingRelease?.let(::showDownloadProgress)
            DownloadManager.STATUS_FAILED -> showDownloadFailure(pendingDownloadId)
            null -> showDownloadFailure(pendingDownloadId)
        }
    }

    private fun fetchLatestRelease(): ReleaseUpdate {
        val json = fetchJson(RELEASE_API_URL)
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var metadataUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.optString("name")) {
                ANDROID_APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                ANDROID_UPDATE_METADATA_ASSET -> metadataUrl = asset.optString("browser_download_url")
            }
        }

        val metadata = metadataUrl
            ?.let { runCatching { fetchJson(it) }.getOrNull() }
        return ReleaseUpdate(
            tagName = json.optString("tag_name"),
            name = json.optString("name"),
            versionCode = metadata?.optLong("versionCode")?.takeIf { it > 0 },
            apkUrl = apkUrl ?: error("Release asset $ANDROID_APK_ASSET was not found"),
            apkSha256 = metadata?.optString("apkSha256")
                ?.lowercase()
                ?.takeIf { it.matches(SHA256_HEX) }
                ?: error("Release metadata did not contain a valid APK SHA-256 digest"),
        )
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OpenStream-Android-Updater")
        }

        connection.inputStream.bufferedReader().use { reader ->
            return JSONObject(reader.readText())
        }
    }



    private fun downloadApk(release: ReleaseUpdate) {
        if (disposed.get() || pendingDownloadId != NO_DOWNLOAD) return
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("OpenStream ${release.displayVersion}")
            .setDescription("Downloading OpenStream update")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "openstream-${release.displayVersion}.apk",
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        pendingRelease = release
        pendingDownloadId = downloadManager.enqueue(request)
        persistPendingDownload(release, pendingDownloadId)
        showDownloadProgress(release)
    }

    private fun showUpdatePrompt(release: ReleaseUpdate) {
        val dialog = updateDialog ?: Dialog(activity, R.style.MinimalDialogTheme).also { updateDialog = it }
        dialog.setContentView(R.layout.dialog_custom_update)
        dialog.setCancelable(true)

        val title = dialog.findViewById<TextView>(R.id.dialogUpdateTitle)
        val message = dialog.findViewById<TextView>(R.id.dialogUpdateMessage)
        val progress = dialog.findViewById<ProgressBar>(R.id.dialogUpdateProgress)
        val progressText = dialog.findViewById<TextView>(R.id.dialogUpdateProgressText)
        val actionBtn = dialog.findViewById<TextView>(R.id.dialogUpdateAction)
        val dismissBtn = dialog.findViewById<TextView>(R.id.dialogUpdateDismiss)

        title.text = "Update available"
        message.text = "OpenStream ${release.displayVersion} is ready to download. You can install it after the download is verified."
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
        actionBtn.visibility = View.VISIBLE
        actionBtn.text = "Download"
        dismissBtn.text = "Later"
        dismissBtn.visibility = View.VISIBLE

        actionBtn.setOnClickListener {
            downloadApk(release)
        }
        dismissBtn.setOnClickListener {
            dialog.dismiss()
        }
        showDialog(dialog)
    }

    private fun verifyDownloadedApk() {
        if (!isSuccessfulDownload()) {
            showDownloadFailure(pendingDownloadId)
            return
        }

        val downloadId = pendingDownloadId
        val release = pendingRelease ?: run {
            showVerificationFailure(downloadId)
            return
        }
        if (verifyingDownloadId == downloadId) return
        verifyingDownloadId = downloadId

        submitUpdateWork {
            val verified = hasExpectedApkDigest(downloadId, release.apkSha256)
            runWhenActivityIsActive {
                if (verifyingDownloadId != downloadId) return@runWhenActivityIsActive
                verifyingDownloadId = NO_DOWNLOAD
                if (pendingDownloadId != downloadId) return@runWhenActivityIsActive
                if (!verified) {
                    showVerificationFailure(downloadId)
                    return@runWhenActivityIsActive
                }
                showInstallReadyPrompt(release, downloadId)
            }
        }
    }

    private fun showDownloadProgress(release: ReleaseUpdate) {
        val dialog = updateDialog ?: Dialog(activity, R.style.MinimalDialogTheme).also { updateDialog = it }
        dialog.setContentView(R.layout.dialog_custom_update)
        dialog.setCancelable(false)
        dialog.findViewById<TextView>(R.id.dialogUpdateTitle).text = "Downloading update"
        dialog.findViewById<TextView>(R.id.dialogUpdateMessage).text =
            "Downloading OpenStream ${release.displayVersion}. Keep this screen open to follow progress."
        dialog.findViewById<ProgressBar>(R.id.dialogUpdateProgress).apply {
            visibility = View.VISIBLE
            isIndeterminate = true
        }
        dialog.findViewById<TextView>(R.id.dialogUpdateProgressText).apply {
            visibility = View.VISIBLE
            text = "Preparing download…"
        }
        dialog.findViewById<TextView>(R.id.dialogUpdateAction).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.dialogUpdateDismiss).visibility = View.GONE
        showDialog(dialog)
        startProgressPolling()
    }

    private fun showInstallReadyPrompt(release: ReleaseUpdate, downloadId: Long) {
        stopProgressPolling()
        val dialog = updateDialog ?: Dialog(activity, R.style.MinimalDialogTheme).also { updateDialog = it }
        dialog.setContentView(R.layout.dialog_custom_update)
        dialog.setCancelable(true)
        dialog.findViewById<TextView>(R.id.dialogUpdateTitle).text = "Update ready"
        dialog.findViewById<TextView>(R.id.dialogUpdateMessage).text =
            "OpenStream ${release.displayVersion} was downloaded and verified. Install it when you are ready."
        dialog.findViewById<ProgressBar>(R.id.dialogUpdateProgress).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.dialogUpdateProgressText).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.dialogUpdateAction).apply {
            visibility = View.VISIBLE
            text = "Install update"
            setOnClickListener {
                dialog.dismiss()
                requestPackageInstall(downloadId)
            }
        }
        dialog.findViewById<TextView>(R.id.dialogUpdateDismiss).apply {
            visibility = View.VISIBLE
            text = "Later"
            setOnClickListener { dialog.dismiss() }
        }
        showDialog(dialog)
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        val poll = object : Runnable {
            override fun run() {
                if (disposed.get() || pendingDownloadId == NO_DOWNLOAD) return
                when (val snapshot = downloadSnapshot(pendingDownloadId)) {
                    null -> {
                        showDownloadFailure(pendingDownloadId)
                        return
                    }
                    else -> when (snapshot.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            verifyDownloadedApk()
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            showDownloadFailure(pendingDownloadId)
                            return
                        }
                        else -> renderDownloadProgress(snapshot)
                    }
                }
                uiHandler.postDelayed(this, DOWNLOAD_PROGRESS_POLL_MS)
            }
        }
        downloadProgressRunnable = poll
        uiHandler.post(poll)
    }

    private fun stopProgressPolling() {
        downloadProgressRunnable?.let(uiHandler::removeCallbacks)
        downloadProgressRunnable = null
    }

    private fun renderDownloadProgress(snapshot: DownloadSnapshot) {
        val dialog = updateDialog ?: return
        val progress = dialog.findViewById<ProgressBar>(R.id.dialogUpdateProgress) ?: return
        val progressText = dialog.findViewById<TextView>(R.id.dialogUpdateProgressText) ?: return
        val total = snapshot.totalBytes
        progress.isIndeterminate = total <= 0L
        if (total > 0L) {
            progress.max = PROGRESS_MAX
            progress.progress = ((snapshot.downloadedBytes * PROGRESS_MAX) / total)
                .coerceIn(0L, PROGRESS_MAX.toLong())
                .toInt()
            progressText.text = "${Formatter.formatFileSize(activity, snapshot.downloadedBytes)} of " +
                "${Formatter.formatFileSize(activity, total)}"
        } else {
            progressText.text = "Downloading…"
        }
    }

    private fun downloadSnapshot(downloadId: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DownloadSnapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ),
                totalBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                ),
            )
        }
    }

    private fun restorePendingDownload() {
        if (pendingDownloadId != NO_DOWNLOAD) return
        val downloadId = updatePrefs.getLong(PREF_DOWNLOAD_ID, NO_DOWNLOAD)
        if (downloadId == NO_DOWNLOAD) return
        val sha256 = updatePrefs.getString(PREF_RELEASE_SHA256, null)
        if (sha256.isNullOrBlank()) {
            clearPendingDownload()
            return
        }
        val release = ReleaseUpdate(
            tagName = updatePrefs.getString(PREF_RELEASE_TAG, "").orEmpty(),
            name = updatePrefs.getString(PREF_RELEASE_NAME, "").orEmpty(),
            versionCode = updatePrefs.getLong(PREF_RELEASE_VERSION_CODE, 0L).takeIf { it > 0L },
            apkUrl = updatePrefs.getString(PREF_RELEASE_URL, "").orEmpty(),
            apkSha256 = sha256,
        )
        if (!release.isNewerThan(currentVersionName(), currentVersionCode())) {
            clearPendingDownload()
            return
        }
        pendingDownloadId = downloadId
        pendingRelease = release
        when (downloadSnapshot(downloadId)?.status) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> showDownloadProgress(pendingRelease!!)
            DownloadManager.STATUS_SUCCESSFUL -> verifyDownloadedApk()
            else -> showDownloadFailure(downloadId)
        }
    }

    private fun persistPendingDownload(release: ReleaseUpdate, downloadId: Long) {
        updatePrefs.edit()
            .putLong(PREF_DOWNLOAD_ID, downloadId)
            .putString(PREF_RELEASE_TAG, release.tagName)
            .putString(PREF_RELEASE_NAME, release.name)
            .putLong(PREF_RELEASE_VERSION_CODE, release.versionCode ?: 0L)
            .putString(PREF_RELEASE_URL, release.apkUrl)
            .putString(PREF_RELEASE_SHA256, release.apkSha256)
            .apply()
    }

    private fun clearPendingDownload() {
        pendingDownloadId = NO_DOWNLOAD
        pendingRelease = null
        updatePrefs.edit().clear().apply()
    }

    private fun showDialog(dialog: Dialog) {
        if (!dialog.isShowing) dialog.show()
        val width = (activity.resources.displayMetrics.widthPixels * DIALOG_WIDTH_FRACTION).toInt()
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun requestPackageInstall(downloadId: Long) {
        if (!canRequestPackageInstall()) {
            showInstallPermissionPrompt()
            return
        }

        val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
        if (apkUri == null) {
            Toast.makeText(activity, "Downloaded APK was not found", Toast.LENGTH_LONG).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(installIntent)
    }

    private fun showVerificationFailure(downloadId: Long) {
        showUpdateFailure(downloadId, "Update verification failed")
    }

    private fun showDownloadFailure(downloadId: Long) {
        showUpdateFailure(downloadId, "Update download failed")
    }

    private fun showUpdateFailure(downloadId: Long, message: String) {
        stopProgressPolling()
        updateDialog?.dismiss()
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        if (downloadId != NO_DOWNLOAD) {
            runCatching { downloadManager.remove(downloadId) }
                .onFailure { error -> Log.w(TAG, "Could not delete unverified update", error) }
        }
        if (pendingDownloadId == downloadId) {
            clearPendingDownload()
        }
    }

    private fun isSuccessfulDownload(): Boolean {
        val query = DownloadManager.Query().setFilterById(pendingDownloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            return cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
        }
        return false
    }

    private fun hasExpectedApkDigest(downloadId: Long, expected: String): Boolean {
        val apkUri = downloadManager.getUriForDownloadedFile(downloadId) ?: return false
        val actual = runCatching {
            activity.contentResolver.openInputStream(apkUri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        }.getOrNull()
        return actual != null && actual.equals(expected, ignoreCase = true)
    }

    private fun submitUpdateWork(work: () -> Unit) {
        if (disposed.get()) return
        try {
            executor.execute(work)
        } catch (_: RejectedExecutionException) {
            // dispose() may race a delayed UI callback or broadcast receiver.
        }
    }

    private fun runWhenActivityIsActive(action: () -> Unit) {
        activity.runOnUiThread {
            if (disposed.get() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            action()
        }
    }

    private fun showInstallPermissionPrompt() {
        val dialog = Dialog(activity, R.style.MinimalDialogTheme)
        dialog.setContentView(R.layout.dialog_custom_permission)
        dialog.setCancelable(true)

        val actionBtn = dialog.findViewById<TextView>(R.id.dialogPermissionAction)
        val dismissBtn = dialog.findViewById<TextView>(R.id.dialogPermissionDismiss)

        actionBtn.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
            activity.startActivity(intent)
        }
        dismissBtn.setOnClickListener { dialog.dismiss() }
        showDialog(dialog)
    }

    private fun canRequestPackageInstall(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
    }

    private fun currentVersionName(): String {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return packageInfo.versionName ?: "0.0.0"
    }

    private fun currentVersionCode(): Long {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private data class ReleaseUpdate(
        val tagName: String,
        val name: String,
        val versionCode: Long?,
        val apkUrl: String,
        val apkSha256: String,
    ) {
        val displayVersion: String = tagName.ifBlank { name }.removePrefix("v")

        fun isNewerThan(currentVersion: String, currentVersionCode: Long): Boolean {
            versionCode?.let { latestCode ->
                return latestCode > currentVersionCode
            }
            return compareVersions(displayVersion, currentVersion.removePrefix("v")) > 0
        }
    }

    private data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    companion object {
        private const val TAG = "OpenStreamUpdater"
        private const val NO_DOWNLOAD = -1L
        private const val RELEASE_API_URL = "https://api.github.com/repos/YashasVM/OpenStream/releases/latest"
        private const val ANDROID_APK_ASSET = "openstream-android.apk"
        private const val ANDROID_UPDATE_METADATA_ASSET = "openstream-android-update.json"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DOWNLOAD_PROGRESS_POLL_MS = 500L
        private const val PROGRESS_MAX = 1_000
        private const val DIALOG_WIDTH_FRACTION = 0.92f
        private const val UPDATE_PREFS_NAME = "openstream_update"
        private const val PREF_DOWNLOAD_ID = "download_id"
        private const val PREF_RELEASE_TAG = "release_tag"
        private const val PREF_RELEASE_NAME = "release_name"
        private const val PREF_RELEASE_VERSION_CODE = "release_version_code"
        private const val PREF_RELEASE_URL = "release_url"
        private const val PREF_RELEASE_SHA256 = "release_sha256"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        private fun compareVersions(candidate: String, current: String): Int {
            val candidateParts = VersionParts.parse(candidate)
            val currentParts = VersionParts.parse(current)
            for (i in 0 until maxOf(candidateParts.numbers.size, currentParts.numbers.size)) {
                val candidateNumber = candidateParts.numbers.getOrElse(i) { 0 }
                val currentNumber = currentParts.numbers.getOrElse(i) { 0 }
                if (candidateNumber != currentNumber) {
                    return candidateNumber.compareTo(currentNumber)
                }
            }
            if (candidateParts.preRelease == currentParts.preRelease) return 0
            if (candidateParts.preRelease == null) return 1
            if (currentParts.preRelease == null) return -1
            return candidateParts.preRelease.compareTo(currentParts.preRelease)
        }

        private data class VersionParts(
            val numbers: List<Int>,
            val preRelease: String?,
        ) {
            companion object {
                fun parse(version: String): VersionParts {
                    val cleaned = version.trim().removePrefix("v")
                    val baseAndPreRelease = cleaned.split("-", limit = 2)
                    val numbers = baseAndPreRelease
                        .firstOrNull()
                        .orEmpty()
                        .split(".")
                        .mapNotNull { it.toIntOrNull() }
                    return VersionParts(
                        numbers = numbers,
                        preRelease = baseAndPreRelease.getOrNull(1),
                    )
                }
            }
        }
    }
}
