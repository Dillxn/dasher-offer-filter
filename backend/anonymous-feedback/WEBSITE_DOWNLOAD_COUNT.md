# The install page's Download count (for offer-filter-site)

A spec for the website repository (offer-filter-site), which its own session syncs and deploys. Nothing in this
repository deploys the website.

The owner asked to "keep track of if anyone besides me downloads the app" and chose both ways offered: the app's
anonymous daily count (DailyCount, in the app) and this one, a count of presses of the Download button on
offerfilter.org's install page. Both go to the accountless feedback service in this folder and are kept as daily
totals, with what lets each count once kept only briefly (README.md, "A count" and "What is stored, and for how long").

## The change

The install page's Download link is the element with `id="download-apk"`; install.js sets its `href` from
`assets/release.json`. Add one click handler to it, in install.js, that sends one beacon and does nothing else:

```js
// The Download button's anonymous count: one beacon a page view, never in the download's way (the app repository's
// backend/anonymous-feedback/WEBSITE_DOWNLOAD_COUNT.md).
(function () {
  var link = document.getElementById("download-apk");
  if (!link || !navigator.sendBeacon) return;
  var counted = false;
  link.addEventListener("click", function () {
    if (counted) return;
    counted = true;
    try {
      navigator.sendBeacon(FEEDBACK_ENDPOINT, new Blob([JSON.stringify({ kind: "count", event: "download" })],
          { type: "text/plain;charset=UTF-8" }));
    } catch (ignored) {
      // A count that cannot go is no reason to stand in the download's way.
    }
  });
})();
```

`FEEDBACK_ENDPOINT` is the address page.js already posts the feedback form to, which is the app's own
(`Feedback.ENDPOINT`): `https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback`. Use page.js's
constant where install.js can see it; otherwise define it once in install.js with a comment naming page.js's, so the
two cannot drift apart unnoticed.

Rules:

- **Never block or delay the download.** No `preventDefault`, no `stopPropagation`, no `await`, no timer before the
  link is followed, no fallback request with `fetch` (a `fetch` only ever replaces the beacon, under the
  referrer-policy rule below). A browser without `sendBeacon` just downloads, uncounted.
- **The link keeps working with scripts off**, as it does today: its `href` in the page's HTML stays a working direct
  link to the APK, which install.js only updates from `assets/release.json`.
- **At most one beacon a page view** (`counted`). The return value of `sendBeacon` is ignored and nothing is retried.
  Errors are swallowed. A middle-click, a long press or "Save link as" fires no `click`; it goes uncounted, which is
  fine.
- **Exactly `{"kind":"count","event":"download"}`.** The protocol allows an optional `countToken`; the site sends
  none. No `appVersion`, `appVersionCode` or `first` (the function refuses them for a download), and nothing about
  the visitor: no page address, referrer, language, screen, time zone, cookie, `localStorage` value or identifier.
  The browser itself adds the `Origin` (`https://offerfilter.org` or `https://www.offerfilter.org`, the only ones
  the function counts a download from) and its ordinary request information, as long as the page's referrer policy
  lets it (the next rule).
- **The install page's referrer policy must keep the Origin on a cross-origin request.** A beacon of a
  CORS-safelisted type goes as a `no-cors` request, and for a `no-cors` POST the browser sends `Origin: null`
  when the page's policy is `no-referrer`, or `same-origin` (the endpoint is another origin). The function refuses
  any Origin but the site's two with 403 before it reads the body, so under either policy no Download is ever
  counted, silently. The default, `strict-origin-when-cross-origin`, keeps it, and so do `origin`,
  `origin-when-cross-origin` and `strict-origin`. Check the page's `Referrer-Policy` header and any
  `<meta name="referrer">` (the rule above, "no referrer", is about the body: the site sends none in it, and needs
  no policy for that). If the site must keep `no-referrer` or `same-origin`, send the count with
  `fetch(FEEDBACK_ENDPOINT, { method: "POST", mode: "cors", keepalive: true, headers: { "Content-Type":
  "text/plain;charset=UTF-8" }, body: JSON.stringify({ kind: "count", event: "download" }) })` instead of
  `sendBeacon` (the snippet's `navigator.sendBeacon` check goes with it), never both, never awaited, with its
  promise's rejection caught and ignored: still a simple request with no preflight, and a `cors` request always
  carries the page's real Origin (the function answers the site's two origins with CORS headers). Every other rule
  here holds for it.
- **`text/plain;charset=UTF-8`, through the Blob's type.** It is a CORS-safelisted type, so the beacon goes as a
  simple request with no preflight. The function takes `text/plain` only from those two origins and only for a
  count. Do not give the Blob `application/json`: browsers refuse or preflight a beacon of that type.
- **Content Security Policy**: if the site sets `connect-src`, it must allow the endpoint's origin (beacons fall under
  it). The feedback form already posts there, so it should already be allowed; check.
- Only the install page's Download button counts. Nothing else on the site, and nothing in the app, sends a
  download count.

## What the service answers

A beacon's answer cannot be read, and the page does not need it. For the record: 201 `{"ok":true}` when the press
is counted or is a duplicate (this network's download was already counted that UTC day: each network counts once a
day); 400 for an invalid count; 503 while storage is unavailable or the day's cap of 20,000 counts is reached.

**Until the function that counts is deployed**, the function deployed now answers 415 to the beacon (it takes only
`application/json`) and stores nothing. That is harmless: the site can ship this before or after the function, and
presses are counted from the day the counts migration and the new function are deployed (README.md, "Deploying").

## The privacy text the site must carry

The site's Privacy page is generated from the app's PRIVACY.md, so regenerating it brings the new text: the bullets
"**Send an anonymous daily count**", "The install page's **Download** button" and "How counts are kept" under "What
leaves the phone, and when", the daily count in "Your choices", and the header "for Offer Filter 0.6.3". Deploy the
beacon and the regenerated Privacy page together, never the beacon with a Privacy page that does not describe it.
The Download bullet reads:

> The install page's **Download** button. When you tap Download on offerfilter.org's install page, your browser
> sends the same feedback service one anonymous count saying only that Download was tapped. The page sets no cookie
> and stores nothing in your browser for it, and the download goes ahead whether or not the count arrives. Like any
> web request it carries your browser's ordinary request information (such as your IP address, the browser and the
> page's address), which Cloudflare and Supabase receive as described above.

So the beacon must stay as that text says: no cookie or browser storage, and the download never waits for it.

## Checking it

In a browser's developer tools, on the install page: one press of Download shows one beacon (a "ping" request) to
the endpoint with the body above, `Content-Type: text/plain;charset=UTF-8` and the request header
`Origin: https://offerfilter.org` (or `https://www.offerfilter.org`), never `Origin: null`, and the download starts
at once; a second press on the same page view sends none; with JavaScript off, Download still downloads. Against the
production function after the new one is deployed, the beacon's answer in the network panel is 201 (not 403, which
means the Origin did not arrive, nor 415, which means the old function is still deployed), and a press from a network
not yet counted that UTC day (a phone on mobile data, say) raises that day's downloads in `offer_filter_counts_daily`
by exactly one; pressing again that day, from that network, adds nothing.
