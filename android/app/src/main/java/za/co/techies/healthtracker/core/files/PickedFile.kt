// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Open files using storage access framework. [Online]. Available at:
//    https://developer.android.com/guide/topics/providers/document-provider
//    [Accessed 25 September 2026].
//   Google. 2024. ContentResolver and content URIs. [Online]. Available at:
//    https://developer.android.com/reference/android/content/ContentResolver
//    [Accessed 25 September 2026].
//   Square. 2024. OkHttp — RequestBody. [Online]. Available at:
//    https://square.github.io/okhttp/4.x/okhttp/okhttp3/-request-body/ [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.9 (Additional Feature 2 —
//    Document Management) and section 4.11 (Screen 11 — Documents).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * A file the caregiver chose, described well enough to upload it.
 *
 * The [uri] is a content URI rather than a path. Android has not handed out real file paths for
 * user-chosen files since scoped storage, and a document picked from Drive or Downloads may not
 * exist on this device as a file at all — it is a stream the provider opens on demand.
 */
data class PickedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val contentType: String
)

/**
 * What the API will accept, mirrored on the device.
 *
 * Checked here as well as on the server so a caregiver on a slow connection is told immediately
 * rather than after pushing eleven megabytes over mobile data and getting a 400 back. The server
 * still enforces it: this copy is a courtesy, not the control.
 */
object UploadRules {

    const val MaximumFileSizeBytes: Long = 10L * 1024 * 1024

    /** JPEG, PNG, HEIC, WebP and PDF, matching DocumentService.AllowedContentTypes. */
    val AllowedContentTypes = setOf(
        "image/jpeg",
        "image/png",
        "image/heic",
        "image/webp",
        "application/pdf"
    )

    /** What to hand the file picker so unsupported types are not offered in the first place. */
    val PickerMimeTypes = AllowedContentTypes.toTypedArray()

    /** The document categories the API accepts, in the order the form offers them. */
    val DocumentTypes = listOf("prescription", "lab_result", "medical_report", "other")

    fun isAcceptableType(contentType: String?): Boolean =
        contentType != null && contentType.substringBefore(';').trim().lowercase() in AllowedContentTypes

    fun isAcceptableSize(bytes: Long): Boolean = bytes in 1..MaximumFileSizeBytes
}

/**
 * Reads a chosen URI's name, size and type without copying the file.
 *
 * Returns null when the provider will not describe it, which happens with a URI that has been
 * revoked between picking and reading. Failing quietly here lets the screen say "that file could
 * not be read" instead of crashing on a stream that was never going to open.
 */
fun ContentResolver.describe(uri: Uri): PickedFile? {
    val type = getType(uri) ?: return null

    val cursor: Cursor = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    }.getOrNull() ?: return null

    return cursor.use {
        if (!it.moveToFirst()) return null

        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

        PickedFile(
            uri = uri,
            // A provider is allowed to report neither, so neither is assumed to be there.
            displayName = if (nameIndex >= 0 && !it.isNull(nameIndex)) {
                it.getString(nameIndex)
            } else {
                "document"
            },
            sizeBytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L,
            contentType = type
        )
    }
}

/**
 * Streams a content URI straight into the request.
 *
 * Deliberately not `readBytes()`. Ten megabytes of photo in a ByteArray, plus OkHttp's own copy of
 * it, is the kind of allocation that survives fine on a test device and gets the process killed on
 * a cheap phone that is already under memory pressure. Streaming keeps it to one small buffer.
 */
class ContentUriRequestBody(
    private val contentResolver: ContentResolver,
    private val file: PickedFile
) : RequestBody() {

    override fun contentType(): MediaType? = file.contentType.toMediaTypeOrNull()

    override fun contentLength(): Long = if (file.sizeBytes > 0) file.sizeBytes else -1L

    override fun writeTo(sink: BufferedSink) {
        val stream = contentResolver.openInputStream(file.uri)
            ?: throw IOException("The chosen file could not be opened: ${file.uri}")

        stream.use { input ->
            input.source().use { source -> sink.writeAll(source) }
        }

        Log.d(TAG, "Streamed ${file.sizeBytes} bytes from ${file.uri}")
    }

    private companion object {
        const val TAG = "ContentUriRequestBody"
    }
}
