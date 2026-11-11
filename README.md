# Listing Radar (Android)

Tracks tokens in Binance's pipeline (Futures/Alpha but not Spot), enriched with
CoinGecko and DEXScreener data. Notifies you of new pipeline tokens and new
Binance listing announcements.

## Build on GitHub (no Android Studio needed)
1. Push this folder to a new GitHub repo.
2. Open the repo's **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes, download **ListingRadar-debug-apk** from the run's Artifacts, unzip it, and install `app-debug.apk` on your phone (allow "install unknown apps").

## Notes
- Not financial advice. Most Alpha/Futures tokens never reach Spot.
- Some endpoints are unofficial and may change. Binance APIs may be blocked in some regions (e.g. the US).
- Android runs background checks roughly every 15+ minutes, and may delay them to save battery.
