# The download count (for offer-filter-site and the update server's page)

The owner asked to "keep track of if anyone besides me downloads the app" and chose both ways offered: the app's
anonymous daily count (DailyCount, in the app) and a count of downloads from offerfilter.org's install page. Both go
to the accountless feedback service in this folder and are kept as daily totals, with what lets each count once kept
only briefly (README.md, "A count" and "What is stored, and for how long").

## From 0.6.15: a counted link, not a beacon

0.6.3 counted a press of the install page's Download button with a beacon (`navigator.sendBeacon`). A long press
("Download link"), "Save link as", a blocked or failed beacon, scripts off, and the APK's address copied and shared
all downloaded uncounted. On 14 October 2026 the owner, told of those blind spots, said "let's fix em".

So the Download links now point at the function's counted download link:

```
https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback/download
```

Every GET or HEAD there is answered `302` to the APK on the update server,
`https://dash-offer-filter-build.onrender.com/OfferFilter.apk` (`lib.ts`'s `APK_URL`), with `Cache-Control: no-store`
and `Referrer-Policy: no-referrer`. A GET from a browser (its User-Agent begins `Mozilla/` and names no robot or link
previewer; no `Sec-Purpose` or `Purpose` naming a prefetch or prerender) is counted first as a `download`, exactly as
the beacon's count was: once per network per UTC day, under the same day-keyed one-way network value, within the same
global daily cap. The redirect never waits on storage (the count finishes after the answer, `EdgeRuntime.waitUntil`),
and a count that fails is only logged. The User-Agent and the purpose headers are read only to decide whether to
count, and are never stored.

What a counted link catches that the beacon missed: a long press or "Save link as" on the button, a browser without
`sendBeacon` or with scripts off, a blocked beacon, and the link copied and shared (a shared copy is the counted link
too). What it still misses: someone who fetches the APK's own address directly (it is in `release.json`, the
update feed and the redirect's `Location`), and the app's own update downloads, which go straight to the update
server and are not downloads of a new copy.

## The links

- **offerfilter.org's install page**: `id="download-apk"`'s `href` in `install/index.html` is the counted link, and
  `install/install.js` no longer replaces it with `release.json`'s `apkUrl` (it still labels the button from
  `release.json`) and sends no beacon. The link works with scripts off, as before.
- **The update server's page** (`tools/mirror_repo_feed.py` writes `public/index.html` on Render): its Download link is
  the counted link too. It loses the `download="OfferFilter-<version>.apk"` file name, which a browser ignores for a
  link to another site; the file arrives as `OfferFilter.apk`, as from the install page.

Nothing else on the site, and nothing in the app, sends a download count. The function still takes the beacon's POST
(`{"kind":"count","event":"download"}` as `text/plain;charset=UTF-8` from the site's two origins), so an install page
loaded before the change counts once, as it did; a page with the new HTML and an old cached install.js puts the
direct link back and sends the beacon, still once.

## Order

Deploy the function that serves `/download` first; until then the counted link answers 405 and nothing downloads.
Then publish the site and the Render page with the links and the regenerated Privacy page (from this repository's
PRIVACY.md, whose Download bullet describes the counted link), together.

## Checking it

`curl -sI https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback/download` answers `302` with
`location: https://dash-offer-filter-build.onrender.com/OfferFilter.apk` and counts nothing (curl is not a browser).
In a phone's browser, Download starts the APK at once; a download from a network not yet counted that UTC day (a
phone on mobile data, say) raises that day's `downloads` in `offer_filter_counts_daily` by exactly one, and a second
one that day, from that network, adds nothing.
