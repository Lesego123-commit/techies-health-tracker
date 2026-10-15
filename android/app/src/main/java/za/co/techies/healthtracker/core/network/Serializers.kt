// ----------------------------------------------------------------------------------------------
//  References
//   JetBrains. 2024. Custom Serializers — kotlinx.serialization. [Online]. Available at:
//    https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md
//    [Accessed 16 September 2026].
//   Oracle. 2024. java.time Package Summary. [Online]. Available at:
//    https://docs.oracle.com/javase/8/docs/api/java/time/package-summary.html
//    [Accessed 16 September 2026].
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Serializers for the two date shapes the API uses.
 *
 * The backend distinguishes between them deliberately, and the distinction is worth preserving
 * here rather than flattening everything to a string:
 *
 *  - An **instant** (`loggedAt`, `nextDoseAt`, `appointmentDateTime`) is a moment in time, sent as
 *    UTC ISO-8601 such as `2026-04-15T18:00:00Z`. Convert to the device's zone for display.
 *  - A **date** (`dateOfBirth`, `startDate`, `estimatedRunOutDate`) has no time component and is
 *    sent as `2026-04-15`. Treating one of these as an instant would shift it across a day
 *    boundary for anyone east of UTC, which is everyone in South Africa.
 *
 * Schedule clock times (`times`) are a third shape: local wall-clock `HH:mm` strings that are not
 * a point in time at all. They stay as strings, which is why there is no serializer for them.
 *
 * These require API 26, which is why `minSdk` is 26.
 */
object InstantSerializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()

        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            // .NET omits the trailing Z when a DateTime has an unspecified kind. Every timestamp
            // this API returns is UTC, so an unqualified value is parsed as UTC rather than
            // rejected, which would fail the whole response over a formatting detail.
            try {
                Instant.parse("${raw}Z")
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("Could not parse '$raw' as an ISO-8601 instant", e)
            }
        }
    }
}

object LocalDateSerializer : KSerializer<LocalDate> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(DateTimeFormatter.ISO_LOCAL_DATE.format(value))
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()

        // .NET's DateOnly serialises as yyyy-MM-dd, but be tolerant of a full timestamp arriving
        // in a field that is logically a date and keep only the date part.
        return LocalDate.parse(raw.take(10))
    }
}
