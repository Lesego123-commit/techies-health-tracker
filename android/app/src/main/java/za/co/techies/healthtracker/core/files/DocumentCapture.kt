// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. FileProvider. [Online]. Available at:
//    https://developer.android.com/reference/androidx/core/content/FileProvider
//    [Accessed 25 September 2026].
//   Google. 2024. Take photos — ActivityResultContracts.TakePicture. [Online]. Available at:
//    https://developer.android.com/training/camera/photobasics [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.9 (Additional Feature 2 —
//    Document Management / Capturing).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.files

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Somewhere for the camera to put a captured prescription.
 *
 * Requirement 3.9 asks for capture as well as upload, and the camera app writes to a URI the
 * caller supplies rather than handing one back. That URI has to be readable by another process,
 * which is what [FileProvider] is for: it grants the camera temporary access to one file without
 * the app holding any storage permission, and without the photo landing in the shared gallery
 * where a prescription would sit alongside someone's holiday pictures.
 */
object DocumentCapture {

    /** Subdirectory of the cache, declared in res/xml/file_paths.xml. */
    private const val CaptureDirectory = "captures"

    /**
     * A fresh URI for the camera to write into.
     *
     * In the cache rather than files/: once uploaded the local copy has no further use, and the
     * system may reclaim it. A capture the caregiver abandons therefore cleans itself up.
     */
    fun newImageUri(context: Context): Uri {
        val directory = File(context.cacheDir, CaptureDirectory).apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")

        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /**
     * Deletes captures left behind by uploads that were cancelled or failed.
     *
     * Small, but this is a medication app: a photograph of someone's prescription should not stay
     * on the device longer than the upload it was taken for.
     */
    fun clearCaptures(context: Context) {
        runCatching { File(context.cacheDir, CaptureDirectory).listFiles()?.forEach { it.delete() } }
    }

    private fun authority(context: Context) = "${context.packageName}.fileprovider"
}
