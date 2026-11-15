package com.spotifyclone.app.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.spotifyclone.app.api.GitHubReleaseApi
import com.spotifyclone.app.model.GitHubRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AppUpdateManager(private val context: Context) {

    private val gitHubApi = GitHubReleaseApi.create()
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    // User can customize the GitHub owner if desired
    var repoOwner: String = "your-github-username"
    var repoName: String = "Music-app"

    /**
     * Check GitHub Releases for newer version of Music-app.
     * If newer version found, prompt user with an AlertDialog.
     */
    fun checkForUpdates(activity: Activity, isManual: Boolean = false) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    gitHubApi.getLatestRelease(repoOwner, repoName)
                }

                if (!response.isSuccessful || response.body() == null) {
                    if (isManual) {
                        Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val release = response.body()!!
                val latestVersionCode = extractVersionCode(release.tagName)
                val currentVersionCode = getCurrentVersionCode()

                Log.d("AppUpdateManager", "Current version: $currentVersionCode, Latest release: $latestVersionCode (${release.tagName})")

                // Find the .apk file in assets
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

                if (latestVersionCode > currentVersionCode && apkAsset != null) {
                    showUpdateDialog(activity, release, apkAsset.downloadUrl)
                } else if (isManual) {
                    Toast.makeText(context, "Your app is up to date!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("AppUpdateManager", "Failed to check for updates", e)
                if (isManual) {
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, release: GitHubRelease, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("A new update (${release.tagName}) is available with the latest NewPipe fixes. Download and install now?")
            .setPositiveButton("Install Update") { _, _ ->
                downloadAndInstallApk(activity, apkUrl)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallApk(activity: Activity, downloadUrl: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setMessage("Downloading update APK...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val updateDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val apkFile = File(updateDir, "Music-app-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder().url(downloadUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(context, "Failed to download update", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val body = response.body!!
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalRead * 100) / contentLength).toInt()
                                withContext(Dispatchers.Main) {
                                    progressDialog.progress = progress
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    launchSystemInstaller(activity, apkFile)
                }

            } catch (e: Exception) {
                Log.e("AppUpdateManager", "Failed downloading APK", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchSystemInstaller(activity: Activity, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Error launching installer", e)
            Toast.makeText(context, "Cannot open package installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            1L
        }
    }

    private fun extractVersionCode(tagName: String): Long {
        // e.g., "v2", "2", "v1.2.3" -> returns 2 or parsed version
        val clean = tagName.removePrefix("v").trim()
        val digits = clean.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 1L
    }
}
