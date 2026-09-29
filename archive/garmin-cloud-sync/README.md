# Garmin live cloud sync (archived 29/09/2026)

This folder keeps the live Garmin sync that ran from 27/09/2026 to 29/09/2026, so it can be brought back later. The app now reads Garmin data from Android Health Connect instead (see [html-to-app-android/README.md](../../html-to-app-android/README.md)).

## How it worked

```
Garmin watch app (Connect IQ) --HTTPS, watch token--> Cloudflare Worker --> D1 database "oldmanmuz"
Ol'Man Muz Android app       <--HTTPS, phone token-- Cloudflare Worker
```

- The watch app posted a live run update every ~10 seconds while recording, plus heart rate and watch temperature while open.
- The Worker stored one row per run and at most one health sample per five minutes.
- The page had **Activities** and **Health Stats** tabs that polled the Worker every 60 seconds.

## What is saved, and where

| Piece | Where it is now |
|---|---|
| Whole working version (page, Android app, backend) | Git tag `cloud-sync-v1` = commit `8dbdda0` on `main` |
| Cloudflare Worker, D1 schema, tests, deploy guide | [cloudflare-backend/](cloudflare-backend/) in this folder, unchanged |
| Page code (tabs, cloud settings dialog, polling) | Removed from `index.html`; still in `git show cloud-sync-v1:index.html` |
| Android `INTERNET` permission | Removed from the manifest; still in the tagged version |
| Garmin watch app source (`Garmin Exportable/source/GarminLiveDataView.mc`) | **Not in this repo.** It is only on the laptop that built it |
| Worker secrets (`WATCH_API_TOKEN`, `PHONE_API_TOKEN`) | Cloudflare, plus `%LOCALAPPDATA%\OlManMuz\cloud-secrets.json` on the laptop that deployed it |

To keep the watch app with everything else, copy the `Garmin Exportable` folder into `archive/garmin-cloud-sync/garmin-watch-app/`. First make sure `API_TOKEN` in `GarminLiveDataView.mc` says `REPLACE_WITH_WATCH_API_TOKEN` and not the real token. The repo is public.

The tag `cloud-sync-v1` exists only on the laptop that made it until you run `git push origin cloud-sync-v1`. Commit `8dbdda0` is already on GitHub either way.

### Page pieces to search for in the tagged `index.html`

| Search for | What it is |
|---|---|
| `cloudStorageKey`, `cloudConfig` | Saved Worker URL and phone token (`localStorage` key `peak-mild-effort.cloud.v1`) |
| `cloudRequest`, `refreshCloud`, `updateCloudPolling` | Fetching and 60-second polling |
| `renderActivities`, `renderHealth` | The two tab renderers (cloud versions) |
| `cloud-config-dialog`, `open-cloud-config` | Settings dialog and header button |
| `activities-panel`, `health-panel`, `.cloud-` | Tab markup and styles |

## Cloudflare resources still running

Removing the watch app stops new data arriving. What was deployed still exists in Cloudflare until you delete it:

- Worker `ol-man-muz-live-data` at `https://ol-man-muz-live-data.oldmanmuz-live.workers.dev`
- D1 database `oldmanmuz` (ID in [cloudflare-backend/wrangler.jsonc](cloudflare-backend/wrangler.jsonc)), holding the synced runs and heart-rate samples

Leaving them idle costs nothing on the Free plan. To delete them, run these from `archive/garmin-cloud-sync/cloudflare-backend` after `npm install` and `npx wrangler login`:

```powershell
npx wrangler delete ol-man-muz-live-data
npx wrangler d1 delete oldmanmuz
```

Deleting D1 permanently removes the synced Garmin data. Recreating it later only needs the steps in [cloudflare-backend/README.md](cloudflare-backend/README.md).

## Bringing it back

**Run the old version as it was:**

```powershell
git switch -c cloud-sync-restore cloud-sync-v1
```

**Add live sync alongside Health Connect:**

1. `git mv archive/garmin-cloud-sync/cloudflare-backend cloudflare-backend`, then deploy using its README. If you deleted D1, create a new database and put its ID in `wrangler.jsonc`.
2. Put `<uses-permission android:name="android.permission.INTERNET" />` back in `html-to-app-android/app/src/main/AndroidManifest.xml`.
3. Copy the page pieces listed above from `git show cloud-sync-v1:index.html` into the current `index.html`. Give the live tab a new name, because **Health** is now the Health Connect tab.
4. Put the watch token into the watch app locally, rebuild it and install it on the watch. Don't commit the token.
