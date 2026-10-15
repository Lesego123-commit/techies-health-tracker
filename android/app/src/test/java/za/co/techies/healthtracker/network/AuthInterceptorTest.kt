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

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import za.co.techies.healthtracker.core.auth.TokenProvider
import za.co.techies.healthtracker.core.network.AuthInterceptor

/**
 * Tests for the interceptor that authenticates every API call.
 *
 * This runs against MockWebServer rather than the real API, so it can assert on exactly what left
 * the device — which header was attached, and whether a request was replayed — without a network,
 * a Firebase project or a handset.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    /** A token provider the test drives directly, standing in for Firebase. */
    private class FakeTokenProvider(
        var token: String? = "token-one",
        var refreshedToken: String? = "token-two"
    ) : TokenProvider {
        var refreshCount = 0

        override suspend fun currentToken(forceRefresh: Boolean): String? =
            if (forceRefresh) {
                refreshCount++
                refreshedToken
            } else {
                token
            }

        override fun currentUid(): String? = "test-uid"
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientWith(provider: TokenProvider): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(provider)).build()

    private fun get(client: OkHttpClient, path: String = "/api/dependants") =
        client.newCall(
            Request.Builder().url(server.url(path)).build()
        ).execute()

    @Test
    fun `attaches the bearer token to an outgoing request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        get(clientWith(FakeTokenProvider())).use { }

        val recorded = server.takeRequest()

        assertEquals("Bearer token-one", recorded.getHeader("Authorization"))
    }

    @Test
    fun `sends no authorization header when nobody is signed in`() {
        server.enqueue(MockResponse().setResponseCode(401))

        get(clientWith(FakeTokenProvider(token = null))).use { }

        // An absent header is correct here. Sending "Bearer null" would be worse: the API would
        // reject it as a malformed token rather than as an anonymous request.
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `retries once with a refreshed token after a 401`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val provider = FakeTokenProvider()

        val response = get(clientWith(provider))
        response.use { assertEquals(200, it.code) }

        assertEquals("Bearer token-one", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-two", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, provider.refreshCount)
    }

    @Test
    fun `does not retry when the refreshed token is unchanged`() {
        server.enqueue(MockResponse().setResponseCode(401))

        // The same token coming back means the session is genuinely invalid, not merely stale.
        // Replaying it would produce a second 401 and a pointless extra round trip.
        val provider = FakeTokenProvider(token = "token-one", refreshedToken = "token-one")

        get(clientWith(provider)).use { assertEquals(401, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not retry when there is no session to refresh`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val provider = FakeTokenProvider(token = null)

        get(clientWith(provider)).use { assertEquals(401, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `leaves signed document download links unauthenticated`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("file-bytes"))

        // These URLs carry their own signature and expiry and are served anonymously by the API,
        // so attaching a bearer token to them achieves nothing.
        get(clientWith(FakeTokenProvider()), "/api/documents/download/abc123.jpg?expires=1&signature=x")
            .use { }

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `does not retry a successful request`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val provider = FakeTokenProvider()

        get(clientWith(provider)).use { assertEquals(200, it.code) }

        assertEquals(1, server.requestCount)
        assertEquals(0, provider.refreshCount)
    }

    @Test
    fun `passes a 403 straight through without refreshing`() {
        server.enqueue(MockResponse().setResponseCode(403))

        // A 403 means the record belongs to another caregiver. The token is perfectly valid, so
        // refreshing it would be wasted work and would mask a genuine authorisation failure.
        val provider = FakeTokenProvider()

        get(clientWith(provider)).use { assertEquals(403, it.code) }

        assertEquals(1, server.requestCount)
        assertEquals(0, provider.refreshCount)
    }

    @Test
    fun `token provider is consulted per request rather than cached`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val provider = FakeTokenProvider()
        val client = clientWith(provider)

        get(client).use { }

        // Firebase rotates the token roughly hourly. Reading it per request is what lets a
        // long-running session keep working without any refresh logic of our own.
        provider.token = "token-rotated"
        get(client).use { }

        assertEquals("Bearer token-one", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-rotated", server.takeRequest().getHeader("Authorization"))
    }
}
