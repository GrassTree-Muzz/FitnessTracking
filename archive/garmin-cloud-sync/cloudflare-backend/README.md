# Garmin live data backend

This Cloudflare Worker accepts Garmin watch updates, stores activities and health samples in D1, and serves them to the Android app. Exercise logs and runs entered manually in the app remain in local storage.

Garmin health readings are coalesced into at most one database sample per five-minute interval. Activity updates replace the current row for a run instead of creating a new row every ten seconds. Health samples remain in D1 until deleted from Cloudflare; disconnecting the Android app only removes that device's saved URL and read token.

## Deploy

You need Node.js/npm and a Cloudflare account. From this directory:

```powershell
npm install
npx wrangler login
```

The existing `oldmanmuz` database ID is already set in `wrangler.jsonc`. Apply the schema and create two different secrets:

```powershell
npm run d1:migrate
```

Register the `workers.dev` subdomain `oldmanmuz-live` in the Cloudflare dashboard when prompted by Wrangler. Then deploy:

```powershell
npm run deploy
```

Two distinct random 64-character tokens have been generated and uploaded for this setup. They are stored outside the repository at `%LOCALAPPDATA%\OlManMuz\cloud-secrets.json`; keep that file private because it is needed to configure both devices. Never share either value in chat. Keep the phone token out of source control. The watch app must contain its write-only token, so insert it locally in the Garmin source and do not commit the token-bearing edit. A token can be extracted from a watch app binary; use a dedicated token, avoid distributing that build publicly, and rotate the Worker secret if it leaks.

Save the deployed `workers.dev` base URL (without a path). The Worker exposes:

- `POST /api/events`: Garmin write-only endpoint; requires `WATCH_API_TOKEN`.
- `GET /api/activities` and `GET /api/health`: Android read endpoints; require `PHONE_API_TOKEN`.

Only `https://appassets.androidplatform.net` is allowed as a browser origin by default. If you also use the hosted web page, add its exact HTTPS origin to `ALLOWED_ORIGINS` in `wrangler.jsonc` and redeploy. The Android app itself does not need an origin change.

## Connect the devices

In the Android app, open the cloud-settings button, confirm the prefilled Worker base URL, enter `PHONE_API_TOKEN`, then save. The phone token is stored in that app's local storage; do not reuse the watch token.

`Garmin Exportable/source/GarminLiveDataView.mc` already targets the deployed Worker. Replace `REPLACE_WITH_WATCH_API_TOKEN` in `API_TOKEN` locally with the `WATCH_API_TOKEN` secret, then rebuild and install the Garmin app. Do not commit that secret-bearing edit. The watch sends a live activity while recording and periodic heart-rate/temperature readings while the app is open.

## Limits and checks

The Cloudflare Free plan currently includes 100,000 Worker requests/day and D1 allowances of 5 GB total storage, 5 million rows read/day, and 100,000 rows written/day. Free-plan overages fail instead of automatically upgrading the account. Review current quotas in Cloudflare before relying on them.

Run the local Worker tests with `npm test`. After configuring Wrangler, `npm run dev` starts a local Worker and D1 development database. Monitor usage and delete D1 data from the Cloudflare dashboard when it is no longer needed.