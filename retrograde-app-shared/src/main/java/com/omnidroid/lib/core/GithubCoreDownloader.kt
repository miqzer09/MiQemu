package com.omnidroid.lib.core

import android.net.Uri
import android.os.Build
import com.omnidroid.common.files.safeDelete
import com.omnidroid.lib.library.CoreID
import com.omnidroid.lib.storage.DirectoriesManager
import timber.log.Timber
import java.io.File

class GithubCoreDownloader(
    private val directoriesManager: DirectoriesManager,
    private val api: CoreUpdater.CoreManagerApi,
) {
    suspend fun retrieve(coreID: CoreID): File {
        Timber.i("Downloading core $coreID from github")

        val mainCoresDirectory = directoriesManager.getCoresDirectory()
        val coresDirectory =
            File(mainCoresDirectory, CORES_VERSION).apply {
                mkdirs()
            }

        val destFile = File(coresDirectory, coreID.libretroFileName)
        if (destFile.exists()) {
            if (coreID == CoreID.MUPEN64_PLUS_NEXT) {
                patchN64Widescreen(destFile)
            }
            return destFile
        }

        runCatching {
            deleteOutdatedCores(mainCoresDirectory)
        }

        // Use raw.githubusercontent.com so downloads work without relying on github.com redirects.
        val uri =
            BASE_URI.buildUpon()
                .appendEncodedPath("$CORES_VERSION/omnidroid_core_${coreID.coreName}/src/main/jniLibs/")
                .appendPath(Build.SUPPORTED_ABIS.first())
                .appendPath(coreID.libretroFileName)
                .build()

        try {
            downloadFile(uri, destFile)
            if (coreID == CoreID.MUPEN64_PLUS_NEXT) {
                patchN64Widescreen(destFile)
            }
            return destFile
        } catch (error: Throwable) {
            destFile.safeDelete()
            throw error
        }
    }

    private suspend fun downloadFile(
        uri: Uri,
        destFile: File,
    ) {
        val response = api.downloadFile(uri.toString())
        if (!response.isSuccessful) {
            val message = response.errorBody()?.use { it.string() } ?: "Download error"
            Timber.e("Download core response was unsuccessful: HTTP ${response.code()} $message")
            throw Exception(message)
        }
        val body = response.body() ?: throw Exception("Empty download body")
        body.use { responseBody ->
            responseBody.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (!destFile.exists() || destFile.length() == 0L) {
            destFile.safeDelete()
            throw Exception("Downloaded core file was empty")
        }
    }

    private fun deleteOutdatedCores(mainCoresDirectory: File) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != CORES_VERSION }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        // Keep in sync with omnidroid-app versionName / OmnidroidCores git tags.
        const val CORES_VERSION = "2.6.0"
        val BASE_URI = Uri.parse("https://raw.githubusercontent.com/Ahmed-Abousaif/OmnidroidCores/")

        private const val N64_ASPECT_OFFSET = 0x5c8bccL
        private const val N64_WIDTH_OFFSET = 0x5c8bc4L
        private const val N64_HEIGHT_OFFSET = 0x5c8bc8L
        private const val N64_GLIDEN64_ASPECT_OFFSET = 0x3bcda8L
        private const val N64_HANDLER_MOVW_OFFSET = 0x4765ccL
        private const val N64_HANDLER_MOVT_OFFSET = 0x4765d4L

        fun patchN64Widescreen(file: File) {
            try {
                if (!file.exists() || file.length() < N64_ASPECT_OFFSET + 4) return
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(N64_ASPECT_OFFSET)
                    val rawInt = Integer.reverseBytes(raf.readInt())
                    if (rawInt != 0x3fe38e39) {
                        raf.seek(N64_WIDTH_OFFSET)
                        raf.writeInt(Integer.reverseBytes(640))
                        raf.seek(N64_HEIGHT_OFFSET)
                        raf.writeInt(Integer.reverseBytes(480))
                        raf.seek(N64_ASPECT_OFFSET)
                        raf.writeInt(Integer.reverseBytes(0x3fe38e39))
                    }
                    if (file.length() > N64_GLIDEN64_ASPECT_OFFSET + 8) {
                        raf.seek(N64_GLIDEN64_ASPECT_OFFSET)
                        raf.writeInt(Integer.reverseBytes(0xe3081e39.toInt()))
                        raf.writeInt(Integer.reverseBytes(0xe3431fe3.toInt()))
                    }
                    if (file.length() > N64_HANDLER_MOVT_OFFSET + 4) {
                        raf.seek(N64_HANDLER_MOVW_OFFSET)
                        raf.writeInt(Integer.reverseBytes(0xe3081e39.toInt()))
                        raf.seek(N64_HANDLER_MOVT_OFFSET)
                        raf.writeInt(Integer.reverseBytes(0xe3431fe3.toInt()))
                    }
                    timber.log.Timber.i("Patched N64 core to 16:9 widescreen")
                }
            } catch (t: Throwable) {
                timber.log.Timber.w(t, "Failed to patch N64 core for widescreen")
            }
        }
    }
}
