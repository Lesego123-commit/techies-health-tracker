// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 25 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.9 (Additional Feature 2 —
//    Document Management / Capturing).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.techies.healthtracker.core.files.UploadRules
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.documents.DocumentsViewModel
import java.util.Locale

/**
 * Tests for what the client decides about an upload before anything leaves the phone.
 *
 * The API enforces all of this too, and deliberately so — the client's copy is a courtesy, not the
 * control. But the courtesy is the whole point: a caregiver on mobile data should be told that an
 * eleven-megabyte photo is too big *before* it is sent, not after. These tests are what keep the
 * two copies of the rules saying the same thing.
 */
class UploadRulesTest {

    @Test
    fun `accepts exactly the types the API accepts`() {
        // Mirrors DocumentService.AllowedContentTypes. If the server ever widens or narrows this,
        // the two lists disagreeing is a bug the caregiver meets as a confusing refusal.
        assertTrue(UploadRules.isAcceptableType("image/jpeg"))
        assertTrue(UploadRules.isAcceptableType("image/png"))
        assertTrue(UploadRules.isAcceptableType("image/heic"))
        assertTrue(UploadRules.isAcceptableType("image/webp"))
        assertTrue(UploadRules.isAcceptableType("application/pdf"))
    }

    @Test
    fun `refuses types the API would reject`() {
        assertFalse(UploadRules.isAcceptableType("image/gif"))
        assertFalse(UploadRules.isAcceptableType("text/plain"))
        assertFalse(UploadRules.isAcceptableType("application/msword"))
        assertFalse(UploadRules.isAcceptableType(null))
        assertFalse(UploadRules.isAcceptableType(""))
    }

    @Test
    fun `ignores the parameters a provider may append to the type`() {
        // A ContentResolver can report "image/jpeg; charset=utf-8" or similar. Comparing the whole
        // string would reject a perfectly ordinary photo.
        assertTrue(UploadRules.isAcceptableType("image/jpeg; charset=binary"))
        assertTrue(UploadRules.isAcceptableType("IMAGE/JPEG"))
        assertTrue(UploadRules.isAcceptableType("  application/pdf  "))
    }

    @Test
    fun `refuses an empty file as well as an oversized one`() {
        // Zero bytes is its own failure: a provider that cannot read the file reports size 0, and
        // uploading nothing would create a document that can never be opened.
        assertFalse(UploadRules.isAcceptableSize(0))
        assertFalse(UploadRules.isAcceptableSize(-1))

        assertTrue(UploadRules.isAcceptableSize(1))
        assertTrue(UploadRules.isAcceptableSize(UploadRules.MaximumFileSizeBytes))
        assertFalse(UploadRules.isAcceptableSize(UploadRules.MaximumFileSizeBytes + 1))
    }

    @Test
    fun `the limit is the ten megabytes the API advertises`() {
        assertEquals(10L * 1024 * 1024, UploadRules.MaximumFileSizeBytes)
    }

    @Test
    fun `offers exactly the categories the API stores`() {
        // DomainValues.DocumentTypes on the server side.
        assertEquals(
            listOf("prescription", "lab_result", "medical_report", "other"),
            UploadRules.DocumentTypes
        )
    }

    @Test
    fun `the picker is filtered to the accepted types`() {
        // Filtering the picker is what stops an unsupported file being chosen at all, which is a
        // better experience than picking one and being told no.
        assertEquals(UploadRules.AllowedContentTypes.size, UploadRules.PickerMimeTypes.size)
        UploadRules.PickerMimeTypes.forEach { assertTrue(UploadRules.isAcceptableType(it)) }
    }

    @Test
    fun `the title limit matches the API's own`() {
        // StringLength(150) on UploadDocumentRequest.Title. Letting someone type 200 characters
        // and then refusing the upload would waste the file transfer as well as their time.
        assertEquals(150, DocumentsViewModel.TitleLimit)
    }
}

/**
 * Tests for the file-size wording.
 *
 * Binary units on purpose: a phone's own file manager uses them, and a document that reads "2.1 MB"
 * there should not read "2.2 MB" here.
 */
class FileSizeFormattingTest {

    @Test
    fun `reads in bytes below a kilobyte`() {
        assertEquals("512 B", Formatting.fileSize(512, Locale.UK))
    }

    @Test
    fun `reads in whole kilobytes below a megabyte`() {
        // No decimal place: nobody needs to know a prescription photo is 240.7 kB rather than 240.
        assertEquals("240 kB", Formatting.fileSize(240 * 1024L, Locale.UK))
        assertEquals("1 kB", Formatting.fileSize(1024, Locale.UK))
    }

    @Test
    fun `reads in megabytes with one decimal place above that`() {
        assertEquals("2.5 MB", Formatting.fileSize((2.5 * 1024 * 1024).toLong(), Locale.UK))
        assertEquals("10.0 MB", Formatting.fileSize(UploadRules.MaximumFileSizeBytes, Locale.UK))
    }

    @Test
    fun `switches units exactly on the boundaries`() {
        assertEquals("1023 B", Formatting.fileSize(1023, Locale.UK))
        assertEquals("1 kB", Formatting.fileSize(1024, Locale.UK))
        assertEquals("1023 kB", Formatting.fileSize(1024 * 1024 - 1, Locale.UK))
        assertEquals("1.0 MB", Formatting.fileSize(1024 * 1024, Locale.UK))
    }

    @Test
    fun `uses the separator the locale writes`() {
        // Afrikaans writes the decimal comma, and the size sits next to an already-translated date.
        assertEquals("2,5 MB", Formatting.fileSize((2.5 * 1024 * 1024).toLong(), Locale.forLanguageTag("af")))
    }

    @Test
    fun `handles a size the provider could not report`() {
        // A ContentResolver is allowed to return no size column at all, which surfaces as 0.
        assertEquals("0 B", Formatting.fileSize(0, Locale.UK))
    }
}
