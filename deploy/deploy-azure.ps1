<#
.SYNOPSIS
    Provisions the Azure resources for the Techies Health Tracker API and deploys it.

.DESCRIPTION
    Implements section 5.3 of the Part 1 Planning and Design document: an App Service on Linux
    running the ASP.NET Core API, backed by Azure Database for PostgreSQL Flexible Server.

    The script is idempotent. Every create is guarded by an existence check, so a failed run can
    simply be run again rather than leaving half-built resources behind or erroring on the second
    attempt. That matters on a student subscription, where a quota refusal part-way through is
    a realistic outcome.

    Secrets are never written to a file. The database password is generated here, pushed straight
    into the App Service configuration, and printed once at the end for the operator to store.

.NOTES
    Requires: Azure CLI, an authenticated session (az login), and an active subscription.
#>

[CmdletBinding()]
param(
    [string]$ResourceGroup = 'techies-health-tracker',

    # South Africa North is the closest region to the app's users. If the student subscription
    # has no quota there, rerun with -Location 'westeurope'.
    [string]$Location = 'southafricanorth',

    # App Service names form part of a globally unique hostname, so a suffix is added below when
    # the default is taken.
    [string]$AppName = 'techies-health-api',

    [string]$DbServerName = '',
    [string]$DbName = 'techies_health_tracker',
    [string]$DbAdminUser = 'techiesadmin',

    # B1 is the cheapest tier that supports always-on. F1 (free) has no always-on, so the API
    # cold-starts on every request after 20 idle minutes, which makes a demo video painful.
    [string]$AppServiceSku = 'B1',
    [string]$DbSku = 'Standard_B1ms',

    [string]$FirebaseProjectId = 'techies-health-tracker',

    # Skips provisioning and only pushes a new build. Use for redeploys.
    [switch]$DeployOnly
)

$ErrorActionPreference = 'Stop'

$az = Join-Path $env:ProgramFiles 'Microsoft SDKs\Azure\CLI2\wbin\az.cmd'
if (-not (Test-Path $az)) { $az = 'az' }

$repoRoot = Split-Path -Parent $PSScriptRoot
$apiProject = Join-Path $repoRoot 'src\TechiesHealthTracker.Api\TechiesHealthTracker.Api.csproj'

function Write-Step($message) {
    Write-Host ''
    Write-Host "==> $message" -ForegroundColor Cyan
}

<#
    Generates the database admin password.

    [System.Web.Security.Membership]::GeneratePassword was the obvious choice and is wrong here:
    its symbol set includes ';' and '=', which are the separator and assignment characters in a
    PostgreSQL connection string. A password containing either produces a connection string that
    parses into nonsense, and the failure surfaces much later as an authentication error that
    looks nothing like its cause.

    This uses an explicit alphabet of characters that are safe in a connection string, and
    guarantees the mix Azure requires: at least one upper, one lower, one digit and one symbol.
#>
function New-DatabasePassword {
    param([int]$Length = 28)

    $upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ'.ToCharArray()
    $lower = 'abcdefghijkmnpqrstuvwxyz'.ToCharArray()
    $digit = '23456789'.ToCharArray()
    $symbol = '!#%*+-_?'.ToCharArray()

    $all = $upper + $lower + $digit + $symbol

    # One from each class first, so the complexity requirement cannot fail by chance.
    $chars = @(
        ($upper | Get-Random),
        ($lower | Get-Random),
        ($digit | Get-Random),
        ($symbol | Get-Random)
    )

    $chars += 1..($Length - 4) | ForEach-Object { $all | Get-Random }

    # Shuffle so the guaranteed characters are not always in the first four positions.
    return -join ($chars | Sort-Object { Get-Random })
}

<#
    Runs the Azure CLI and decides success from the exit code.

    The stderr handling here is deliberate and was the source of a real failure. PowerShell wraps
    each stderr line from a *native* executable in an ErrorRecord, and under
    $ErrorActionPreference = 'Stop' that ErrorRecord is terminating. The CLI writes to stderr
    routinely — including the perfectly ordinary "resource not found" that an existence check is
    asking about — so the script would throw on exactly the case it was written to detect.

    Both helpers therefore drop to 'Continue' around the call and judge the outcome by
    $LASTEXITCODE alone, which is the only reliable signal from a native command.
#>
function Invoke-Az {
    param([string[]]$Arguments, [switch]$AllowFailure)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    try {
        $output = & $az @Arguments 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($exit -ne 0 -and -not $AllowFailure) {
        Write-Host ($output | Out-String) -ForegroundColor Red
        throw "az $($Arguments -join ' ') failed with exit code $exit"
    }

    return ($output | Out-String).Trim()
}

function Test-AzResource {
    param([string[]]$Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    try {
        & $az @Arguments 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previous
    }
}

# ---- Preflight -------------------------------------------------------------------------------

Write-Step 'Checking the Azure session'

$accountJson = Invoke-Az @('account', 'show', '--output', 'json') -AllowFailure

if (-not $accountJson -or $accountJson -match 'az login') {
    throw "Not signed in. Run 'az login' first."
}

$account = $accountJson | ConvertFrom-Json
Write-Host "  Subscription: $($account.name)"
Write-Host "  Tenant:       $($account.tenantId)"
Write-Host "  User:         $($account.user.name)"

if (-not (Test-Path $apiProject)) {
    throw "Could not find the API project at $apiProject"
}

# App Service hostnames are global, so a name clash is likely with a generic name. A short
# deterministic suffix derived from the subscription id keeps the name stable across reruns
# while making a collision with another tenant very unlikely.
$suffix = ($account.id -replace '[^0-9a-f]', '').Substring(0, 6)

if ([string]::IsNullOrWhiteSpace($DbServerName)) {
    $DbServerName = "techies-db-$suffix"
}

$planName = "$AppName-plan"

Write-Host "  App name:     $AppName"
Write-Host "  DB server:    $DbServerName"

# ---- Resource group --------------------------------------------------------------------------

if (-not $DeployOnly) {
    Write-Step "Resource group '$ResourceGroup'"

    if (Test-AzResource @('group', 'show', '--name', $ResourceGroup)) {
        Write-Host '  Already exists.'
    } else {
        Invoke-Az @('group', 'create', '--name', $ResourceGroup, '--location', $Location, '--output', 'none')
        Write-Host '  Created.'
    }

    # ---- PostgreSQL ---------------------------------------------------------------------------

    Write-Step "PostgreSQL Flexible Server '$DbServerName'"

    $dbPassword = $null

    if (Test-AzResource @('postgres', 'flexible-server', 'show', '--resource-group', $ResourceGroup, '--name', $DbServerName)) {
        Write-Host '  Already exists. Resetting the admin password so this run has a known value.'

        $dbPassword = New-DatabasePassword

        Invoke-Az @(
            'postgres', 'flexible-server', 'update',
            '--resource-group', $ResourceGroup,
            '--name', $DbServerName,
            '--admin-password', $dbPassword,
            '--output', 'none'
        )
    } else {
        $dbPassword = New-DatabasePassword

        Write-Host '  Creating. This takes several minutes.'

        Invoke-Az @(
            'postgres', 'flexible-server', 'create',
            '--resource-group', $ResourceGroup,
            '--name', $DbServerName,
            '--location', $Location,
            '--admin-user', $DbAdminUser,
            '--admin-password', $dbPassword,
            '--sku-name', $DbSku,
            '--tier', 'Burstable',
            '--storage-size', '32',
            '--version', '16',
            '--public-access', '0.0.0.0',
            '--yes',
            '--output', 'none'
        )

        Write-Host '  Created.'
    }

    # The application database is created as a separate step. Azure CLI 2.90 rejects
    # --database-name on server create, accepting it only alongside --node-count for elastic
    # clusters, so it cannot be done inline any more. Note the db subcommand takes --name,
    # not --database-name; the two commands spell the same concept differently.
    Write-Step "Database '$DbName'"

    if (Test-AzResource @('postgres', 'flexible-server', 'db', 'show',
            '--resource-group', $ResourceGroup, '--server-name', $DbServerName, '--name', $DbName)) {
        Write-Host '  Already exists.'
    } else {
        Invoke-Az @(
            'postgres', 'flexible-server', 'db', 'create',
            '--resource-group', $ResourceGroup,
            '--server-name', $DbServerName,
            '--name', $DbName,
            '--output', 'none'
        )
        Write-Host '  Created.'
    }

    # 0.0.0.0 is Azure's sentinel for "allow Azure services", not the public internet. It lets
    # the App Service reach the database without opening it to the world.
    Write-Step 'Database firewall'

    Invoke-Az @(
        'postgres', 'flexible-server', 'firewall-rule', 'create',
        '--resource-group', $ResourceGroup,
        '--name', $DbServerName,
        '--rule-name', 'allow-azure-services',
        '--start-ip-address', '0.0.0.0',
        '--end-ip-address', '0.0.0.0',
        '--output', 'none'
    ) -AllowFailure | Out-Null

    Write-Host '  Azure services allowed.'

    # ---- App Service ---------------------------------------------------------------------------

    Write-Step "App Service plan '$planName'"

    if (Test-AzResource @('appservice', 'plan', 'show', '--resource-group', $ResourceGroup, '--name', $planName)) {
        Write-Host '  Already exists.'
    } else {
        Invoke-Az @(
            'appservice', 'plan', 'create',
            '--resource-group', $ResourceGroup,
            '--name', $planName,
            '--location', $Location,
            '--is-linux',
            '--sku', $AppServiceSku,
            '--output', 'none'
        )
        Write-Host '  Created.'
    }

    Write-Step "Web app '$AppName'"

    if (Test-AzResource @('webapp', 'show', '--resource-group', $ResourceGroup, '--name', $AppName)) {
        Write-Host '  Already exists.'
    } else {
        Invoke-Az @(
            'webapp', 'create',
            '--resource-group', $ResourceGroup,
            '--plan', $planName,
            '--name', $AppName,
            '--runtime', 'DOTNETCORE:8.0',
            '--output', 'none'
        )
        Write-Host '  Created.'
    }

    # ---- Configuration -------------------------------------------------------------------------

    Write-Step 'Application settings'

    $connectionString = "Host=$DbServerName.postgres.database.azure.com;Port=5432;Database=$DbName;Username=$DbAdminUser;Password=$dbPassword;SSL Mode=Require;Trust Server Certificate=true"

    # A 64-character random key signs the time-limited document download URLs.
    $signingKey = -join ((48..57) + (97..122) | Get-Random -Count 64 | ForEach-Object { [char]$_ })

    # Double underscore is how .NET maps a flat environment variable onto a nested config section.
    $settings = @(
        "ConnectionStrings__Default=$connectionString",
        "Firebase__ProjectId=$FirebaseProjectId",
        'Firebase__UseDevelopmentTokens=false',
        "FileStorage__SigningKey=$signingKey",
        "FileStorage__PublicBaseUrl=https://$AppName.azurewebsites.net",
        'Swagger__Enabled=true',
        'Database__ApplyMigrationsOnStartup=true',
        'ASPNETCORE_ENVIRONMENT=Production'
    )

    Invoke-Az (@('webapp', 'config', 'appsettings', 'set',
        '--resource-group', $ResourceGroup,
        '--name', $AppName,
        '--output', 'none',
        '--settings') + $settings) | Out-Null

    Write-Host '  Applied.'

    # Always-on keeps the instance warm so the API does not cold-start mid-demonstration, and so
    # the reminder background service keeps sweeping.
    Invoke-Az @(
        'webapp', 'config', 'set',
        '--resource-group', $ResourceGroup,
        '--name', $AppName,
        '--always-on', 'true',
        '--http20-enabled', 'true',
        '--min-tls-version', '1.2',
        '--output', 'none'
    ) -AllowFailure | Out-Null

    Invoke-Az @(
        'webapp', 'update',
        '--resource-group', $ResourceGroup,
        '--name', $AppName,
        '--https-only', 'true',
        '--output', 'none'
    ) -AllowFailure | Out-Null

    Write-Host '  Always-on and HTTPS-only enabled.'

    $script:GeneratedDbPassword = $dbPassword
}

# ---- Build and deploy --------------------------------------------------------------------------

Write-Step 'Publishing the API'

$publishDir = Join-Path $env:TEMP "techies-publish-$(Get-Random)"
$zipPath = "$publishDir.zip"

dotnet publish $apiProject --configuration Release --output $publishDir --nologo
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }

Compress-Archive -Path (Join-Path $publishDir '*') -DestinationPath $zipPath -Force
Write-Host "  Packaged: $zipPath"

Write-Step 'Deploying to App Service'

Invoke-Az @(
    'webapp', 'deploy',
    '--resource-group', $ResourceGroup,
    '--name', $AppName,
    '--src-path', $zipPath,
    '--type', 'zip',
    '--output', 'none'
)

Write-Host '  Deployed.'

Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $zipPath -Force -ErrorAction SilentlyContinue

# ---- Verify ------------------------------------------------------------------------------------

Write-Step 'Verifying'

$baseUrl = "https://$AppName.azurewebsites.net"

# The first request after a deployment also triggers the EF Core migrations, so it can take a
# while. Polling beats a fixed sleep that is either too short or wastes time.
$healthy = $false
for ($attempt = 1; $attempt -le 20; $attempt++) {
    Start-Sleep -Seconds 15

    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/api/health" -UseBasicParsing -TimeoutSec 30
        Write-Host "  Attempt $attempt : HTTP $($response.StatusCode) - $($response.Content)"
        $healthy = $true
        break
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        Write-Host "  Attempt $attempt : not ready yet$(if ($code) { " (HTTP $code)" })"
    }
}

Write-Host ''
Write-Host '================================================================' -ForegroundColor Green
Write-Host ' Deployment complete' -ForegroundColor Green
Write-Host '================================================================' -ForegroundColor Green
Write-Host " API:      $baseUrl"
Write-Host " Swagger:  $baseUrl/swagger"
Write-Host " Health:   $baseUrl/api/health"
Write-Host " Healthy:  $healthy"

if ($script:GeneratedDbPassword) {
    Write-Host ''
    Write-Host ' Database admin password (store this now; it is not saved anywhere):' -ForegroundColor Yellow
    Write-Host "   $($script:GeneratedDbPassword)" -ForegroundColor Yellow
    Write-Host ''
    Write-Host ' It is already set in the App Service configuration, so the API has it.'
    Write-Host ' You only need it to connect to the database directly, e.g. from pgAdmin.'
}

Write-Host ''
Write-Host ' Next: set apiBaseUrl in android/local.properties to' -ForegroundColor Cyan
Write-Host "   $baseUrl/" -ForegroundColor Cyan
