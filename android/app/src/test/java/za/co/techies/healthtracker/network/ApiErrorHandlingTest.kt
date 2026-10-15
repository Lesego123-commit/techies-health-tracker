// ----------------------------------------------------------------------------------------------
//  References
//   JUnit Team. 2024. JUnit 4 Documentation. [Online]. Available at:
//    https://junit.org/junit4/ [Accessed 16 September 2026].
//   Square. 2024. MockWebServer. [Online]. Available at:
//    https://github.com/square/okhttp/tree/master/mockwebserver [Accessed 16 September 2026].
//   Google. 2024. Build Local Unit Tests. [Online]. Available at:
//    https://developer.android.com/training/testing/local-tests [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import za.co.techies.healthtracker.core.network.ApiErrorCodes
import za.co.techies.healthtracker.core.network.ApiException
import za.co.techies.healthtracker.core.network.NetworkFactory
import za.co.techies.healthtracker.data.remote.dto.CreateDependantRequest
import za.co.techies.healthtracker.data.repository.DependantRepository
import java.time.LocalDate

/**
 * Tests that every kind of failure reaches the caller as one [ApiException].
 *
 * This is the whole point of the repository layer: a screen should need one `onFailure` branch to
 * show any error, whether the cause was a validation failure, an authorisation refusal, a dropped
 * connection or an unparseable response body. These tests pin that down.
 */
class ApiErrorHandlingTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DependantRepository

    private object NoToken : za.co.techies.healthtracker.core.auth.TokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): String? = null
        override fun currentUid(): String? = null
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }

        val client: OkHttpClient = NetworkFactory.createOkHttpClient(NoToken, enableLogging = false)
        repository = DependantRepository(NetworkFactory.createApi(server.url("/").toString(), client))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun errorOf(result: Result<*>): ApiException {
        val throwable = result.exceptionOrNull()
        assertNotNull("Expected a failure", throwable)
        assertTrue("Expected an ApiException but got $throwable", throwable is ApiException)
        return throwable as ApiException
    }

    @Test
    fun `maps a business rule failure to its code and message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """
                {
                  "error": "invalid_request",
                  "message": "A date of birth cannot be in the future.",
                  "status": 400,
                  "traceId": "0HN7G2K9"
                }
                """.trimIndent()
            )
        )

        val error = errorOf(repository.list())

        assertEquals(ApiErrorCodes.INVALID_REQUEST, error.code)

        // The API writes these messages to be read by a user, so the screen can show it directly.
        assertEquals("A date of birth cannot be in the future.", error.message)
        assertEquals("0HN7G2K9", error.traceId)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `surfaces field level validation errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """
                {
                  "error": "validation_failed",
                  "message": "One or more fields are invalid.",
                  "status": 400,
                  "traceId": "0HN7G2KA",
                  "errors": {
                    "FirstName": ["The FirstName field is required."],
                    "Relationship": ["The Relationship field is required."]
                  }
                }
                """.trimIndent()
            )
        )

        val error = errorOf(
            repository.create(
                CreateDependantRequest(
                    firstName = "",
                    lastName = "Masike",
                    dateOfBirth = LocalDate.of(2014, 3, 12),
                    relationship = ""
                )
            )
        )

        // This map is what a form screen uses to highlight the offending inputs.
        assertEquals(2, error.fieldErrors.size)
        assertTrue(error.fieldErrors.containsKey("FirstName"))
    }

    @Test
    fun `flags a 401 as needing reauthentication`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = errorOf(repository.list())

        assertTrue(error.requiresReauthentication)
    }

    @Test
    fun `maps a 403 to forbidden without asking for reauthentication`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":"forbidden","message":"This dependant belongs to another caregiver.","status":403}"""
            )
        )

        val error = errorOf(repository.get("someone-elses-id"))

        assertEquals(ApiErrorCodes.FORBIDDEN, error.code)

        // The token is valid; the record simply is not theirs. Sending the user back to sign in
        // would be both useless and confusing.
        assertFalse(error.requiresReauthentication)
    }

    @Test
    fun `maps an upstream failure as retryable`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"error":"upstream_unavailable","message":"The pharmacy search service is unavailable.","status":502}"""
            )
        )

        val error = errorOf(repository.list())

        assertEquals(ApiErrorCodes.UPSTREAM_UNAVAILABLE, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `falls back to a status-derived message when the body is not an error envelope`() = runTest {
        // A reverse proxy or the App Service platform itself can return HTML on a bad day. The
        // client must not crash while handling an error; that turns a recoverable failure into
        // a report of "the app closed itself".
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html><body>Not Found</body></html>"))

        val error = errorOf(repository.get("missing"))

        assertEquals(ApiErrorCodes.NOT_FOUND, error.code)
        assertEquals(404, error.httpStatus)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `falls back cleanly on an empty error body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = errorOf(repository.list())

        assertEquals(ApiErrorCodes.SERVER_ERROR, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `reports a dropped connection as a network failure`() = runTest {
        server.shutdown()

        val error = errorOf(repository.list())

        assertEquals(ApiErrorCodes.NETWORK_UNAVAILABLE, error.code)
        assertTrue(error.isRetryable)

        // Worth checking the wording: this is the message a caregiver on a weak signal sees most
        // often, and it should tell them what to do rather than name an exception class.
        assertTrue(error.message.contains("connection", ignoreCase = true))
    }

    @Test
    fun `returns the parsed body on success`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {
                    "dependantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "firstName": "Thandi",
                    "lastName": "Masike",
                    "fullName": "Thandi Masike",
                    "dateOfBirth": "2014-03-12",
                    "age": 12,
                    "relationship": "child",
                    "activeMedicationCount": 2,
                    "createdAt": "2026-04-15T06:00:00Z",
                    "updatedAt": "2026-04-15T06:00:00Z",
                    "rowVersion": 1
                  }
                ]
                """.trimIndent()
            )
        )

        val result = repository.list()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("Thandi Masike", result.getOrThrow().first().fullName)
    }

    @Test
    fun `sends date query parameters as plain calendar dates`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val api = NetworkFactory.createApi(
            server.url("/").toString(),
            NetworkFactory.createOkHttpClient(NoToken, enableLogging = false)
        )

        za.co.techies.healthtracker.data.repository.DoseRepository(api)
            .history("dep-1", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15))

        val recorded = server.takeRequest()

        // The API parses these as DateOnly. Sending a full timestamp would be rejected.
        assertTrue(recorded.path!!.contains("from=2026-04-01"))
        assertTrue(recorded.path!!.contains("to=2026-04-15"))
    }
}
