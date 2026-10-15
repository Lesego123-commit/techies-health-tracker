// ----------------------------------------------------------------------------------------------
//  References
//   xUnit.net. 2024. Getting Started with xUnit.net. [Online]. Available at:
//    https://xunit.net/docs/getting-started/netfx/visual-studio [Accessed 25 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.7 (Minimum Requirement 7 —
//    Multi-Language Support).
// ----------------------------------------------------------------------------------------------

using FluentAssertions;
using TechiesHealthTracker.Api.Domain.Constants;
using TechiesHealthTracker.Api.Services.Implementations;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the notification wording.
///
/// Section 3.7 says the stored language preference exists so that dose reminders, refill alerts and
/// the rest are "sent in the correct language". Until this existed the preference was stored,
/// validated and shipped to the handset — and then ignored when the server composed the message, so
/// a caregiver who chose isiZulu got an isiZulu app and English push notifications.
///
/// What these pin down is not the translations themselves, which need a first-language reader, but
/// the things that are provably right or wrong: that each language produces something different,
/// that an unknown code falls back rather than throwing, that plurals are handled per language, and
/// that the data interpolated into a message is never translated.
/// </summary>
public class NotificationTextTests
{
    private const string Dependant = "Thabo";
    private const string Medication = "Amoxicillin";

    [Theory]
    [InlineData(SupportedLanguages.English)]
    [InlineData(SupportedLanguages.IsiZulu)]
    [InlineData(SupportedLanguages.Afrikaans)]
    public void EveryCategory_HasWordingInEverySupportedLanguage(string language)
    {
        NotificationText.DoseReminderTitle(language).Should().NotBeNullOrWhiteSpace();
        NotificationText.MissedDoseTitle(language).Should().NotBeNullOrWhiteSpace();
        NotificationText.AppointmentTitle(language).Should().NotBeNullOrWhiteSpace();
        NotificationText.RefillTitle(language).Should().NotBeNullOrWhiteSpace();
    }

    [Fact]
    public void TheThreeLanguages_ActuallyDiffer()
    {
        // Guards the copy-paste failure: a table like this is easy to extend by duplicating a row
        // and forgetting to translate it, which no compiler and no smoke test would ever catch.
        var titles = new[]
        {
            NotificationText.DoseReminderTitle(SupportedLanguages.English),
            NotificationText.DoseReminderTitle(SupportedLanguages.IsiZulu),
            NotificationText.DoseReminderTitle(SupportedLanguages.Afrikaans)
        };

        titles.Should().OnlyHaveUniqueItems();
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("xh")]
    [InlineData("not-a-language")]
    public void AnUnknownPreference_FallsBackToEnglishRatherThanThrowing(string? language)
    {
        // The preference travels from a client and between devices, so a build that predates a
        // language can still send its code. A background sweep that threw here would stop sending
        // *everyone* reminders, which is far worse than one caregiver reading English.
        NotificationText.DoseReminderTitle(language).Should().Be("Medication due");
        NotificationText.RefillTitle(language).Should().Be("Refill needed");
    }

    [Fact]
    public void ThePreference_IsMatchedRegardlessOfCasingOrSpacing()
    {
        NotificationText.DoseReminderTitle("ZU").Should()
            .Be(NotificationText.DoseReminderTitle(SupportedLanguages.IsiZulu));

        NotificationText.DoseReminderTitle(" af ").Should()
            .Be(NotificationText.DoseReminderTitle(SupportedLanguages.Afrikaans));
    }

    [Fact]
    public void NamesAndMedications_AreNeverTranslated()
    {
        // They are data. A dependant called Thabo is called Thabo in every language, and a
        // medication name that changed with the interface language would be dangerous rather than
        // merely wrong.
        foreach (var language in SupportedLanguages.All)
        {
            var body = NotificationText.DoseReminderBody(language, Dependant, Medication, "500 mg");

            body.Should().Contain(Dependant);
            body.Should().Contain(Medication);
            body.Should().Contain("500 mg");
        }
    }

    [Theory]
    [InlineData(SupportedLanguages.English)]
    [InlineData(SupportedLanguages.IsiZulu)]
    [InlineData(SupportedLanguages.Afrikaans)]
    public void MissedDoses_PluraliseSeparatelyInEachLanguage(string language)
    {
        var one = NotificationText.MissedDoseBody(language, Dependant, Medication, 1);
        var several = NotificationText.MissedDoseBody(language, Dependant, Medication, 3);

        // Not "dose(s)". isiZulu changes the noun itself — umthamo becomes imithamo — so the
        // bracketed-s habit could not have worked there even if it were acceptable in English.
        one.Should().NotBe(several);
        one.Should().NotContain("(s)");
        several.Should().NotContain("(s)");
        several.Should().Contain("3");
    }

    [Theory]
    [InlineData(SupportedLanguages.English)]
    [InlineData(SupportedLanguages.IsiZulu)]
    [InlineData(SupportedLanguages.Afrikaans)]
    public void Refill_SaysSomethingDifferentWhenStockHasAlreadyRunOut(string language)
    {
        var soon = NotificationText.RefillBody(language, Medication, 3);
        var gone = NotificationText.RefillBody(language, Medication, 0);

        // "will run out in about 0 days" would be both wrong and oddly calm about a medication
        // that is already unavailable.
        soon.Should().NotBe(gone);
        soon.Should().Contain("3");
        gone.Should().NotContain("0");
    }

    [Theory]
    [InlineData(null)]
    [InlineData(-2)]
    public void Refill_TreatsAMissingOrNegativeForecastAsRunOut(int? days)
    {
        // The forecast is null when nothing is being consumed and can go negative if a schedule
        // was edited after the fact. Neither should produce a countdown.
        NotificationText.RefillBody(SupportedLanguages.English, Medication, days)
            .Should().Be($"{Medication} has run out. Arrange a repeat prescription.");
    }

    [Fact]
    public void Refill_PluralisesASingleDay()
    {
        NotificationText.RefillBody(SupportedLanguages.English, Medication, 1)
            .Should().Contain("1 day.").And.NotContain("1 days");
    }

    [Fact]
    public void AppointmentTimes_AreFormattedInTheReadersOwnCulture()
    {
        var appointment = new DateTime(2026, 3, 15, 14, 30, 0, DateTimeKind.Utc);

        var english = NotificationText.AppointmentBody(
            SupportedLanguages.English, Dependant, "Dr Nkosi", appointment);

        var afrikaans = NotificationText.AppointmentBody(
            SupportedLanguages.Afrikaans, Dependant, "Dr Nkosi", appointment);

        english.Should().Contain("14:30").And.Contain("March");

        // March, deliberately: April is spelled the same in all three languages and would prove
        // nothing. A month name left in English is how a translated message gives itself away.
        afrikaans.Should().Contain("14:30");
        afrikaans.Should().NotContain("March");
    }

    [Fact]
    public void TheCulture_IsSouthAfricanForEverySupportedLanguage()
    {
        // The server runs invariant and in UTC. Without pinning these, every date in every
        // notification would be formatted the way a US-defaulted machine happens to prefer.
        NotificationText.CultureFor(SupportedLanguages.English).Name.Should().Be("en-ZA");
        NotificationText.CultureFor(SupportedLanguages.IsiZulu).Name.Should().Be("zu-ZA");
        NotificationText.CultureFor(SupportedLanguages.Afrikaans).Name.Should().Be("af-ZA");
        NotificationText.CultureFor("unknown").Name.Should().Be("en-ZA");
    }
}
