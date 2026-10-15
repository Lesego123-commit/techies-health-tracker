# Deployment Guide — Azure App Service

How to get the Techies Health Tracker API onto the hosting platform named in section 5.3 of the
Part 1 design document. Nothing here has been run yet: the Azure resources are provisioned when
the group is ready to deploy, using the GitHub Education credits.

---

## 0. Signing in to the Azure CLI

```bash
az login
```

**Run this from your own terminal, not through a script or a background process.** It needs to
launch a browser on your desktop and listen for the redirect; started without a desktop session it
hangs silently with no window ever appearing.

**Do not use `az login --use-device-code` on the ADvTECH tenant.** Device-code authentication is
blocked by a Conditional Access policy, and the refusal is easy to misread — it reports
*"Your sign-in was successful but does not meet the criteria to access this resource"*, which
sounds like an account problem when it is purely the auth flow being restricted. The ordinary
browser flow above is permitted, which is also why the Azure portal works normally in Chrome.

Confirm the session before deploying:

```bash
az account show
```

## 1. What gets provisioned

| Resource | Tier | Purpose |
| --- | --- | --- |
| Azure App Service | Linux, B1 | Runs the API |
| Azure Database for PostgreSQL — Flexible Server | Burstable B1ms | The database |
| Azure Blob Storage | Standard LRS | Uploaded documents |

Put all three in the same region — `South Africa North` — so that database round trips stay on
the same network and the app is close to its users.

---

## 2. Create the resources

**The scripted route is `deploy/deploy-azure.ps1`**, which does everything in this section and the
next two, and is idempotent so a failed run can simply be repeated. The commands below are what it
runs, for reference and for anyone deploying by hand.

First register the resource providers. A fresh subscription has neither, and the failure when they
are missing does not say so clearly:

```bash
az provider register --namespace Microsoft.Web
az provider register --namespace Microsoft.DBforPostgreSQL
```

```bash
az group create --name techies-health-tracker --location southafricanorth

az postgres flexible-server create \
  --resource-group techies-health-tracker \
  --name techies-db \
  --location southafricanorth \
  --tier Burstable --sku-name Standard_B1ms \
  --version 16 \
  --storage-size 32 \
  --admin-user techiesadmin \
  --public-access 0.0.0.0 \
  --yes
```

The application database is a separate command. Azure CLI 2.90 rejects `--database-name` on server
create, accepting it only alongside `--node-count` for elastic clusters. Note that the `db`
subcommand spells the same concept `--name`, not `--database-name`:

```bash
az postgres flexible-server db create \
  --resource-group techies-health-tracker \
  --server-name techies-db \
  --name techies_health_tracker
```

```bash
az appservice plan create \
  --resource-group techies-health-tracker \
  --name techies-plan --is-linux --sku B1

az webapp create \
  --resource-group techies-health-tracker \
  --plan techies-plan \
  --name techies-health-api \
  --runtime "DOTNETCORE:8.0"
```

Allow the App Service to reach the database:

```bash
az postgres flexible-server firewall-rule create \
  --resource-group techies-health-tracker \
  --name techies-db \
  --rule-name allow-azure-services \
  --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0
```

That rule permits Azure-internal traffic only, not the public internet.

---

## 3. Configure the app

Every secret is an App Service application setting. **None of these belongs in `appsettings.json`**
— the committed file holds empty placeholders precisely so that a real value can never be
committed by accident.

```bash
az webapp config appsettings set \
  --resource-group techies-health-tracker \
  --name techies-health-api \
  --settings \
    ConnectionStrings__Default="Host=techies-db.postgres.database.azure.com;Database=techies_health_tracker;Username=techiesadmin;Password=<password>;SSL Mode=Require;Trust Server Certificate=true" \
    Firebase__ProjectId="<firebase-project-id>" \
    Firebase__UseDevelopmentTokens="false" \
    GeoApify__ApiKey="<geoapify-key>" \
    BlobStorage__ConnectionString="<storage-account-connection-string>" \
    BlobStorage__ContainerName="documents" \
    FileStorage__SigningKey="<random-64-character-string>" \
    FileStorage__PublicBaseUrl="https://techies-health-api.azurewebsites.net" \
    Swagger__Enabled="true" \
    ASPNETCORE_ENVIRONMENT="Production"
```

Note the double underscore: that is how .NET maps a flat environment variable onto a nested
configuration section.

### Document storage

The storage account is created separately, because the connection string above comes out of it:

```bash
# Microsoft.Storage is not registered on a fresh subscription, and every storage command
# fails with SubscriptionNotFound until it is. Registration takes about a minute.
az provider register --namespace Microsoft.Storage

az storage account create \
  --name techieshealthdocs \
  --resource-group techies-health-tracker \
  --location southafricanorth \
  --sku Standard_LRS \
  --kind StorageV2 \
  --min-tls-version TLS1_2 \
  --https-only true \
  --allow-blob-public-access false

az storage container create \
  --name documents \
  --public-access off \
  --connection-string "$(az storage account show-connection-string \
      --name techieshealthdocs --resource-group techies-health-tracker \
      --query connectionString -o tsv)"
```

`--allow-blob-public-access false` matters: it is set at the account level, so even a container
created with public access by mistake stays private. Every download goes through a read-only
Shared Access Signature scoped to one blob and valid for fifteen minutes.

A note on the connection string: it contains the account key, and the key is what signs those
signatures. Pass it through a settings file rather than inline — it contains semicolons, which
most shells split on, and a half-assigned connection string produces a confusing failure at
the first upload rather than at deployment:

```bash
az webapp config appsettings set \
  --resource-group techies-health-tracker \
  --name techies-health-api \
  --settings @blob-settings.json
```

To confirm which implementation a deployment actually resolved, call the local download route
with any signature. It returns **404** when Blob Storage is registered, because a Shared Access
Signature points straight at the blob endpoint and the route is bypassed; it returns **403**
when the local filesystem implementation is still in place and the signature fails to verify:

```bash
curl -i "https://techies-health-api.azurewebsites.net/api/documents/download/probe.jpg?expires=1&signature=x"
```

**`Firebase__UseDevelopmentTokens` must be `false`.** The API refuses the development-token path
in Production regardless of this setting (`Authentication/AuthenticationSetup.cs`), but it should
be explicit here as well.

`Swagger__Enabled` is set to `true` so the deployed API can be demonstrated through its OpenAPI
page in the video. Turn it off for a real production service.

---

## 4. Deploy

### From the CI artefact

`backend-ci.yml` publishes an `api-publish` artefact on every push to `main`. Download it and:

```bash
az webapp deploy \
  --resource-group techies-health-tracker \
  --name techies-health-api \
  --src-path ./publish.zip --type zip
```

### Continuously, from GitHub

Add the publish profile as a repository secret named `AZURE_WEBAPP_PUBLISH_PROFILE`
(App Service → *Get publish profile*), then append a deploy job to the workflow:

```yaml
  deploy:
    name: Deploy to Azure App Service
    runs-on: ubuntu-latest
    needs: publish
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: api-publish
          path: ./publish
      - uses: azure/webapps-deploy@v3
        with:
          app-name: techies-health-api
          publish-profile: ${{ secrets.AZURE_WEBAPP_PUBLISH_PROFILE }}
          package: ./publish
```

The job depends on `publish`, which depends on `build-and-test`, so a failing test can never
reach the deployed environment.

---

## 5. Migrations

`Program.cs` applies any pending EF Core migration at startup and **fails hard if it cannot**.
That is deliberate: an API running against a stale schema corrupts data rather than simply
erroring, so refusing to start is the safer outcome.

To apply them by hand instead, set `Database__ApplyMigrationsOnStartup=false` and run:

```bash
dotnet ef database update --project src/TechiesHealthTracker.Api
```

---

## 6. Verify the deployment

```bash
curl https://techies-health-api.azurewebsites.net/api/health
```

A healthy instance returns `200` with `"database": "reachable"`. A `503` with
`"database": "unreachable"` means the API is up but cannot reach PostgreSQL — check the
connection string and the firewall rule from section 2.

Then confirm:

- `https://techies-health-api.azurewebsites.net/swagger` lists all 22 routes;
- a call without a token returns `401`;
- a call with a valid Firebase token returns the caller's profile.

---

## 7. Firebase

1. In the Firebase console, enable **Google** as a sign-in provider.
2. Add the Android app's package name and SHA-1 fingerprint.
3. Set `Firebase__ProjectId` to the project id — the token issuer and audience are derived from it,
   so nothing else is needed for authentication.
4. **POE only:** download the service-account JSON for Cloud Messaging and supply it through
   `Firebase__ServiceAccountPath`. Commit it nowhere; `.gitignore` already excludes the usual
   filenames as a second line of defence.

---

## 8. Logs

Serilog writes to the console, which App Service captures:

```bash
az webapp log tail --resource-group techies-health-tracker --name techies-health-api
```

Every request is logged with its method, path, status and duration. Denied access attempts are
logged at warning level with the caller and the record they asked for, so an attempt to reach
another caregiver's data is visible rather than disappearing into a plain `403`.
