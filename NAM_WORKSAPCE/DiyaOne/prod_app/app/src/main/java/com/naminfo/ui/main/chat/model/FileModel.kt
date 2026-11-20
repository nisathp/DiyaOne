package com.naminfo.ui.main.chat.model

import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.naminfo.DiyaOneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import com.naminfo.utils.FileUtils
import com.naminfo.utils.TimestampUtils
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.withContext
import com.naminfo.utils.FileUtils.Companion.getFileStorageCacheDir
import java.io.File

class FileModel
    @AnyThread
    constructor(
    var path: String,
    val fileName: String,
    val fileSize: Long,
    val fileCreationTimestamp: Long,
    val isEncrypted: Boolean,
    val originalPath: String,
    val isFromEphemeralMessage: Boolean,
    var isWaitingToBeDownloaded: Boolean = false,
    val flexboxLayoutWrapBefore: Boolean = false,
    private val onTransferCancelled: (() -> Unit)? = null,
    private val onClicked: ((model: FileModel) -> Unit)? = null
) {
    companion object {
        private const val TAG = "[File Model]"
    }

    val formattedFileSize = MutableLiveData<String>()

    val transferProgress = MutableLiveData<Int>()

    val transferProgressLabel = MutableLiveData<String>()

    val canCancelTransfer = MutableLiveData<Boolean>()

    val mediaPreview = MutableLiveData<String>()

    val mediaPreviewAvailable = MutableLiveData<Boolean>()

    var mimeType: FileUtils.MimeType = FileUtils.MimeType.Unknown

    var mimeTypeString: String = ""

    var isMedia: Boolean = false

    var isImage: Boolean = false

    var isVideoPreview: Boolean = false

    val audioVideoDuration = MutableLiveData<String>()

    var isPdf: Boolean = false

    var isAudio: Boolean = false

    val month = TimestampUtils.month(fileCreationTimestamp)

    val dateTime = TimestampUtils.toString(
        fileCreationTimestamp,
        shortDate = false,
        hideYear = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        updateTransferProgress(-1)
        canCancelTransfer.postValue(false)
        computeFileSize(fileSize)

        if (!isWaitingToBeDownloaded) {
            val extension = FileUtils.getExtensionFromFileName(path)
            isPdf = extension == "pdf"
            if (isPdf) {
                loadPdfPreview()
            }

            val mime = FileUtils.getMimeTypeFromExtension(extension)
            mimeTypeString = mime

            mimeType = FileUtils.getMimeType(mime)
            isImage = mimeType == FileUtils.MimeType.Image
            isVideoPreview = mimeType == FileUtils.MimeType.Video
            isAudio = mimeType == FileUtils.MimeType.Audio

            if (isImage) {
                mediaPreview.postValue(path)
                mediaPreviewAvailable.postValue(true)
            } else if (isVideoPreview) {
                loadVideoPreview()
            }

            if (isVideoPreview || isAudio) {
                getDuration()
            }
            Log.d(
                "$TAG File has already been downloaded, extension is [$extension], MIME is [$mime]"
            )
        } else {
            // Keep the media type while the attachment is waiting. This makes an image/video
            // show a compact tap-to-download placeholder instead of a generic file row.
            val extension = FileUtils.getExtensionFromFileName(fileName)
            mimeTypeString = FileUtils.getMimeTypeFromExtension(extension)
            mimeType = FileUtils.getMimeType(mimeTypeString)
            isPdf = false
            isImage = mimeType == FileUtils.MimeType.Image
            isVideoPreview = mimeType == FileUtils.MimeType.Video
            isAudio = mimeType == FileUtils.MimeType.Audio
        }

        isMedia = isVideoPreview || isImage
    }

    @AnyThread
    fun tryStartDownload(): Boolean {
        val current = transferProgress.value ?: -1
        if (current in 0..100) return false
        return downloadInFlight.compareAndSet(false, true)
    }

    @AnyThread
    fun destroy() {
        downloadInFlight.set(false)
        transferProgress.postValue(-1)
        transferProgressLabel.postValue("")
        if (isEncrypted) {
            Log.i("$TAG [VFS] Deleting plain file in cache: $path")
            scope.launch {
                runCatching { FileUtils.deleteFile(path) }
            }
        }
    }

    @AnyThread
    fun computeFileSize(fileSize: Long) {
        formattedFileSize.postValue(FileUtils.bytesToDisplayableSize(fileSize))
    }

    @AnyThread
    fun updateTransferProgress(percent: Int) {
        if (percent in 0..100) {
            downloadInFlight.set(true)
        } else {
            downloadInFlight.set(false)
        }
        transferProgress.postValue(percent)
        if (percent !in 0..100) {
            transferProgressLabel.postValue("")
        } else {
            transferProgressLabel.postValue("$percent%")
        }
    }

    @AnyThread
    fun setTransferCancelable(cancelable: Boolean) {
        canCancelTransfer.postValue(cancelable)
    }

    @UiThread
    fun cancelTransfer() {
        onTransferCancelled?.invoke()
    }

    @UiThread
    fun onClick() {
        onClicked?.invoke(this)
    }

    /** Turns a tapped placeholder into a usable local attachment once its download completes. */
    @AnyThread
    fun markDownloaded(localPath: String) {
        path = localPath
        isWaitingToBeDownloaded = false
        val extension = FileUtils.getExtensionFromFileName(localPath).ifEmpty {
            FileUtils.getExtensionFromFileName(fileName)
        }
        val mime = FileUtils.getMimeTypeFromExtension(extension)
        mimeTypeString = mime
        mimeType = FileUtils.getMimeType(mime)
        if (mimeType == FileUtils.MimeType.Image) {
            isImage = true
            isMedia = true
        } else if (mimeType == FileUtils.MimeType.Video) {
            isVideoPreview = true
            isMedia = true
        } else if (mimeType == FileUtils.MimeType.Audio) {
            isAudio = true
        } else if (extension == "pdf") {
            isPdf = true
        }
        updateTransferProgress(-1)
        if (isImage) {
            mediaPreview.postValue(localPath)
            mediaPreviewAvailable.postValue(true)
        } else if (isVideoPreview) {
            loadVideoPreview()
        } else if (isPdf) {
            loadPdfPreview()
        }
        if (isVideoPreview || isAudio) {
            getDuration()
        }
    }

    @AnyThread
    suspend fun deleteFile() {
        Log.i("$TAG Deleting file [$path]")
        FileUtils.deleteFile(path)
    }

    @AnyThread
    private fun loadPdfPreview() {
        if (!File(path).isFile) return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val pdfFileDescriptor = ParcelFileDescriptor.open(
                        File(path),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    if (pdfFileDescriptor == null) {
                        Log.e("$TAG Failed to get a file descriptor for PDF at [$path]")
                        return@withContext
                    }

                    val pdfRenderer = PdfRenderer(pdfFileDescriptor)
                    val pdfFirstPage = pdfRenderer.openPage(0)
                    val previewBitmap = createBitmap(pdfFirstPage.width, pdfFirstPage.height)
                    pdfFirstPage.render(
                        previewBitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )

                    val file = getFileStorageCacheDir("$fileName.jpg", true)
                    val previewPath = FileUtils.storeBitmap(previewBitmap, file)
                    Log.i("$TAG Preview of PDF file [$path] available at [$previewPath]")
                    mediaPreview.postValue(previewPath)
                    mediaPreviewAvailable.postValue(true)

                    previewBitmap.recycle()
                    pdfFirstPage.close()
                    pdfRenderer.close()
                    pdfFileDescriptor.close()
                } catch (e: Exception) {
                    Log.e("$TAG Failed to get image preview for PDF file [$path]: $e")
                }
            }
        }
    }

    @AnyThread
    private fun loadVideoPreview() {
        if (!File(path).isFile) return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Log.i("$TAG Try to create an image preview of video file [$path]")
                    val previewBitmap = ThumbnailUtils.createVideoThumbnail(
                        path,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                    if (previewBitmap != null) {
                        val file = getFileStorageCacheDir("$fileName.jpg", true)
                        val previewPath = FileUtils.storeBitmap(previewBitmap, file)
                        Log.i("$TAG Preview of video file [$path] available at [$previewPath]")
                        mediaPreview.postValue(previewPath)
                        mediaPreviewAvailable.postValue(true)
                    }
                } catch (e: Exception) {
                    Log.e("$TAG Failed to get image preview for file [$path]: $e")
                }
            }
        }
    }

    @AnyThread
    private fun getDuration() {
        if (!File(path).isFile) return
        scope.launch {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(coreContext.context, path.toUri())
                val durationInMs = retriever.extractMetadata(METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                val seconds = durationInMs / 1000
                val duration = TimestampUtils.durationToString(seconds)
                Log.d("$TAG Duration for file [$path] is $duration")
                audioVideoDuration.postValue(duration)
            } catch (e: Exception) {
                Log.e("$TAG Failed to get duration for file [$path]: $e")
            } finally {
                try {
                    retriever?.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
