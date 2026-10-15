// ----------------------------------------------------------------------------------------------
//  References
//   xUnit.net. 2024. Getting Started with xUnit.net. [Online]. Available at:
//    https://xunit.net/docs/getting-started/netfx/visual-studio [Accessed 25 September 2026].
//   Fluent Assertions. 2024. Documentation. [Online]. Available at:
//    https://fluentassertions.com/introduction [Accessed 25 September 2026].
//   Microsoft. 2024. Grant Limited Access to Data with Shared Access Signatures. [Online].
//    Available at: https://learn.microsoft.com/azure/storage/common/storage-sas-overview
//    [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, sections 3.9 and 6.7.
// ----------------------------------------------------------------------------------------------

using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using TechiesHealthTracker.Api.Services.External;
using Xunit;

namespace TechiesHealthTracker.Api.Tests.Services;

/// <summary>
/// Tests for the Azure Blob storage configuration decisions.
///
/// Uploading and signing need a real storage account, so those are not exercised here — a test
/// that reached the network would fail in CI for reasons that have nothing to do with the code.
/// What *is* worth pinning down is the behaviour around the edges, because each of these has a
/// failure mode that is silent rather than loud: a service that starts without a connection string
/// and only fails on the first upload, or a container that is created with public access and makes
/// every signed URL decorative.
/// </summary>
public class BlobStorageOptionsTests
{
    [Fact]
    public void IsConfigured_IsFalseUntilAConnectionStringIsSupplied()
    {
        // This is what Program.cs branches on. Getting it wrong in the "false" direction means the
        // API silently keeps writing documents to the App Service filesystem, which is wiped on
        // every deployment — a data-loss bug that looks like nothing at all until a restart.
        new BlobStorageOptions().IsConfigured.Should().BeFalse();
        new BlobStorageOptions { ConnectionString = "   " }.IsConfigured.Should().BeFalse();
        new BlobStorageOptions { ConnectionString = "UseDevelopmentStorage=true" }
            .IsConfigured.Should().BeTrue();
    }

    [Fact]
    public void ContainerName_DefaultsToDocuments()
    {
        // The deployment guide provisions a container by this name, so the default and the
        // documentation have to agree or a fresh deployment writes into the wrong place.
        new BlobStorageOptions().ContainerName.Should().Be("documents");
    }

    [Fact]
    public void Constructing_WithoutAConnectionString_FailsImmediatelyAndSaysWhy()
    {
        var options = Options.Create(new BlobStorageOptions());

        var act = () => new AzureBlobFileStorageService(
            options, NullLogger<AzureBlobFileStorageService>.Instance);

        // Fails at construction rather than on the first upload. A service that starts happily and
        // then throws the first time a caregiver photographs a prescription is far harder to
        // diagnose than one that refuses to start at all.
        act.Should().Throw<InvalidOperationException>()
            .WithMessage("*BlobStorage:ConnectionString*");
    }
}
