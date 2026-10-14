// Tests of the pure parts of the feedback function. Run with either:
//   deno test backend/anonymous-feedback/lib.test.ts
//   node --experimental-strip-types --test backend/anonymous-feedback/lib.test.ts
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ALLOWED_ORIGINS,
  APK_URL,
  COUNT_EVENTS,
  countsAsDownload,
  Invalid,
  ipv6,
  isCount,
  isDownloadPath,
  networkOf,
  reference,
  secondsUntilUtcMidnight,
  utcDay,
  validate,
  validateCount,
} from "./lib.ts";

const TOKEN = "0123456789abcdef0123456789abcdef";

test("only Cloudflare's address is used: IPv4 as it is, IPv6 by its /64", () => {
  assert.equal(networkOf("203.0.113.7"), "4:203.0.113.7");
  assert.equal(networkOf("2001:db8:abcd:12:1:2:3:4"), "6:2001:db8:abcd:12::/64");
  assert.equal(networkOf("2001:DB8:ABCD:0012:ffff::1"), "6:2001:db8:abcd:12::/64", "one /64, any host");
  assert.equal(networkOf("2001:db8::1"), "6:2001:db8:0:0::/64");
  assert.equal(networkOf("::ffff:198.51.100.9"), "4:198.51.100.9", "an IPv4-mapped address is IPv4");
  assert.equal(networkOf("fe80::1%eth0"), "6:fe80:0:0:0::/64");
  assert.equal(networkOf(null), "unavailable");
  assert.equal(networkOf(""), "unavailable");
  assert.equal(networkOf("not an address"), "unavailable");
  assert.equal(networkOf("256.1.1.1"), "unavailable");
  assert.equal(networkOf("1::2::3"), "unavailable");
});

test("IPv6 text is read in all its forms", () => {
  assert.deepEqual(ipv6("::"), [0, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(ipv6("1::"), [1, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(ipv6("::1"), [0, 0, 0, 0, 0, 0, 0, 1]);
  assert.deepEqual(ipv6("[2001:db8::7]"), [0x2001, 0xdb8, 0, 0, 0, 0, 0, 7]);
  assert.deepEqual(ipv6("::ffff:1.2.3.4"), [0, 0, 0, 0, 0, 0xffff, 0x0102, 0x0304]);
  assert.deepEqual(ipv6("1:2:3:4:5:6:7:8"), [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.equal(ipv6("1:2:3:4:5:6:7:8:9"), null);
  assert.equal(ipv6("1:2:3:4:5:6:7::8"), null, ":: stands for at least one group");
  assert.equal(ipv6("12345::"), null);
  assert.equal(ipv6("1.2.3.4"), null);
});

test("a part from the app is taken as sent, its diagnostics never trimmed", () => {
  const diagnostics = `[Offer Filter diagnostics 01234567 part 1/2]\n\n  indented first line\n\n[end part 1/2]`;
  const part = validate({
    kind: "diagnostics",
    category: "general",
    diagnostics: `  ${diagnostics}\n`,
    diagnosticsConsented: true,
    appVersion: "0.5.0",
    appVersionCode: 80,
    reportToken: TOKEN,
    partIndex: 0,
    partCount: 2,
  }, null);
  assert.equal(part.source, "android");
  assert.equal(part.diagnostics, `  ${diagnostics}\n`);
  assert.equal(part.partCount, 2);
  assert.equal(part.message, null);
});

test("what could never be stored is refused", () => {
  const ok = { kind: "feedback", category: "bug", message: "It declined a $12 offer." };
  assert.equal(validate(ok, null).message, "It declined a $12 offer.");
  const refused: Record<string, unknown>[] = [
    { ...ok, kind: "praise" },
    { ...ok, category: "sales" },
    { ...ok, message: "   " },
    { ...ok, message: "x".repeat(4_001) },
    { ...ok, diagnostics: "log" },
    { ...ok, diagnostics: "d".repeat(60_001), diagnosticsConsented: true },
    { kind: "diagnostics", diagnostics: " \n\t ", diagnosticsConsented: true },
    { ...ok, reportToken: TOKEN.toUpperCase() },
    { ...ok, reportToken: TOKEN, partIndex: 4, partCount: 5 },
    { ...ok, reportToken: TOKEN, partIndex: 2, partCount: 2 },
    { ...ok, partIndex: 0, partCount: 2 },
    { ...ok, partIndex: "0" },
    { ...ok, appVersion: "0.5.0 <script>" },
    { ...ok, appVersionCode: 0 },
  ];
  for (const body of refused) {
    assert.throws(() => validate(body, null), Invalid, JSON.stringify(body).slice(0, 120));
  }
  assert.equal(validate({ ...ok, diagnostics: "   " }, null).diagnostics, null, "blank diagnostics are none");
});

test("the website sends feedback only, from its own origins", () => {
  assert.ok(ALLOWED_ORIGINS.has("https://offerfilter.org"));
  assert.ok(!ALLOWED_ORIGINS.has("https://dillxn.github.io"));
  assert.equal(validate({ kind: "feedback", message: "Hello" }, "https://offerfilter.org").source, "web");
  assert.throws(() => validate({ kind: "problem", message: "x" }, "https://offerfilter.org"), Invalid);
});

test("references, days and the daily cap's retry", () => {
  assert.equal(reference("1a2b3c4d-5e6f-4a1b-8c2d-0123456789ab"), "1a2b3c4d");
  assert.equal(reference(null), "");
  assert.equal(utcDay(new Date("2026-10-06T23:59:59Z")), "2026-10-06");
  assert.equal(secondsUntilUtcMidnight(new Date("2026-10-06T23:30:00Z")), 1_800);
  assert.equal(secondsUntilUtcMidnight(new Date("2026-10-06T10:00:00Z")), 3_600, "at most an hour");
  assert.equal(secondsUntilUtcMidnight(new Date("2026-10-06T23:59:30Z")), 60, "at least a minute");
  assert.equal(secondsUntilUtcMidnight(new Date("2026-10-06T10:00:00Z"), 86_400), 50_400, "a count's day: all of it");
  assert.equal(secondsUntilUtcMidnight(new Date("2026-10-06T23:59:30Z"), 86_400), 60);
});

const WEBSITE = "https://offerfilter.org";
const DAILY = {
  kind: "count",
  event: "daily",
  countToken: TOKEN,
  first: true,
  appVersion: "0.6.3",
  appVersionCode: 95,
};

test("the app's daily count and the Download button's are taken as sent", () => {
  assert.deepEqual(COUNT_EVENTS, new Set(["daily", "download"]));
  assert.deepEqual(validateCount(DAILY, null), {
    event: "daily",
    countToken: TOKEN,
    first: true,
    appVersion: "0.6.3",
    appVersionCode: 95,
  });
  assert.deepEqual(
    validateCount({ kind: "count", event: "daily", countToken: TOKEN }, null),
    { event: "daily", countToken: TOKEN, first: false, appVersion: null, appVersionCode: null },
    "first is false unless sent; the version is optional",
  );
  assert.equal(validateCount({ ...DAILY, first: false }, null).first, false);
  assert.equal(validateCount({ ...DAILY, appVersion: "v".repeat(32) }, null).appVersion, "v".repeat(32));
  assert.equal(validateCount({ ...DAILY, appVersionCode: 1 }, null).appVersionCode, 1);
  assert.equal(validateCount({ ...DAILY, appVersionCode: 10_000_000 }, null).appVersionCode, 10_000_000);

  const download = { event: "download", countToken: null, first: false, appVersion: null, appVersionCode: null };
  assert.deepEqual(validateCount({ kind: "count", event: "download" }, WEBSITE), download);
  assert.deepEqual(validateCount({ kind: "count", event: "download" }, "https://www.offerfilter.org"), download);
  assert.deepEqual(
    validateCount({ kind: "count", event: "download", countToken: TOKEN }, WEBSITE),
    { ...download, countToken: TOKEN },
  );
});

test("a count holds its event's keys and nothing else", () => {
  const notForAnyCount: Record<string, unknown> = {
    message: "hello",
    diagnostics: "log",
    category: "general",
    reportToken: TOKEN,
    partIndex: 0,
    partCount: 1,
    diagnosticsConsented: true,
    device: "Pixel 9",
    androidVersion: 36,
    maker: "Google",
    locale: "en-US",
    timeZone: "America/New_York",
    installId: TOKEN,
  };
  for (const [key, value] of Object.entries(notForAnyCount)) {
    assert.throws(() => validateCount({ ...DAILY, [key]: value }, null), Invalid, `daily with ${key}`);
    const download = { kind: "count", event: "download", [key]: value };
    assert.throws(() => validateCount(download, WEBSITE), Invalid, `download with ${key}`);
  }
  // JSON can name a key like an object's own machinery: it is a key like any other.
  const proto = JSON.parse(`{"kind":"count","event":"daily","countToken":"${TOKEN}","__proto__":{}}`);
  assert.throws(() => validateCount(proto, null), Invalid);
  // The Download button sends no app version and no first flag.
  for (const [key, value] of Object.entries({ appVersion: "0.6.3", appVersionCode: 95, first: false })) {
    assert.throws(
      () => validateCount({ kind: "count", event: "download", [key]: value }, WEBSITE),
      Invalid,
      `download with ${key}`,
    );
  }
});

test("each count comes only from its own place", () => {
  assert.throws(() => validateCount(DAILY, WEBSITE), Invalid, "the app's count from the website");
  assert.throws(() => validateCount(DAILY, "https://www.offerfilter.org"), Invalid);
  assert.throws(() => validateCount({ kind: "count", event: "download" }, null), Invalid, "a download with no Origin");
  assert.throws(() => validateCount({ kind: "count", event: "download" }, "https://dillxn.github.io"), Invalid);
  assert.throws(() => validateCount({ kind: "count", event: "download" }, "http://offerfilter.org"), Invalid);
});

test("a count's event, token, first flag and version are checked strictly", () => {
  const refused: [string, Record<string, unknown>, string | null][] = [
    ["no event", { kind: "count", countToken: TOKEN }, null],
    ["another event", { ...DAILY, event: "install" }, null],
    ["an event in capitals", { ...DAILY, event: "Daily" }, null],
    ["an event that is not text", { ...DAILY, event: 1 }, null],
    ["a kind in capitals", { ...DAILY, kind: "Count" }, null],
    ["a daily count without its token", { kind: "count", event: "daily", first: true }, null],
    ["a token in capitals", { ...DAILY, countToken: TOKEN.toUpperCase() }, null],
    ["a short token", { ...DAILY, countToken: TOKEN.slice(1) }, null],
    ["a long token", { ...DAILY, countToken: `${TOKEN}0` }, null],
    ["a token with spaces", { ...DAILY, countToken: ` ${TOKEN}` }, null],
    ["a token that is not text", { ...DAILY, countToken: 12345 }, null],
    ["a null token", { ...DAILY, countToken: null }, null],
    ["a download's bad token", { kind: "count", event: "download", countToken: "xyz" }, WEBSITE],
    ["a download's null token", { kind: "count", event: "download", countToken: null }, WEBSITE],
    ["first as text", { ...DAILY, first: "true" }, null],
    ["first as a number", { ...DAILY, first: 1 }, null],
    ["first as null", { ...DAILY, first: null }, null],
    ["a version with a space", { ...DAILY, appVersion: "0.6.3 beta" }, null],
    ["a version with spaces around", { ...DAILY, appVersion: " 0.6.3" }, null],
    ["an empty version", { ...DAILY, appVersion: "" }, null],
    ["a version too long", { ...DAILY, appVersion: "v".repeat(33) }, null],
    ["a version that is not text", { ...DAILY, appVersion: 63 }, null],
    ["a null version", { ...DAILY, appVersion: null }, null],
    ["a version code of 0", { ...DAILY, appVersionCode: 0 }, null],
    ["a version code too large", { ...DAILY, appVersionCode: 10_000_001 }, null],
    ["a fractional version code", { ...DAILY, appVersionCode: 1.5 }, null],
    ["a version code as text", { ...DAILY, appVersionCode: "95" }, null],
    ["a null version code", { ...DAILY, appVersionCode: null }, null],
  ];
  for (const [what, body, origin] of refused) {
    assert.throws(() => validateCount(body, origin), Invalid, what);
  }
});

test("a count is not feedback, and feedback is not a count", () => {
  assert.ok(isCount(DAILY));
  assert.ok(isCount({ kind: "count", event: "download" }));
  assert.ok(!isCount({ kind: "feedback", message: "Hello" }));
  assert.ok(!isCount({ message: "Hello" }), "a body with no kind is feedback, as before");
  assert.ok(!isCount({ kind: " count" }), "only exactly \"count\"");
  assert.throws(() => validate(DAILY, null), Invalid, "count is no feedback kind");
  assert.throws(() => validate({ kind: "count", message: "Hello" }, null), Invalid);
  assert.throws(() => validate({ kind: "count", event: "download" }, WEBSITE), Invalid);
  assert.throws(() => validateCount({ kind: "feedback", message: "Hello" }, null), Invalid);
});

test("the counted download link: its path, and where it sends every fetch", () => {
  assert.ok(isDownloadPath("/offer-filter-feedback/download"));
  assert.ok(isDownloadPath("/functions/v1/offer-filter-feedback/download/"));
  assert.ok(!isDownloadPath("/offer-filter-feedback"));
  assert.ok(!isDownloadPath("/offer-filter-feedback/downloads"));
  assert.ok(!isDownloadPath("/offer-filter-feedback/download/x"));
  assert.equal(APK_URL, "https://dash-offer-filter-build.onrender.com/OfferFilter.apk", "the app's update file");
});

test("a browser's GET of the download link counts; robots, previews, prefetches and HEAD do not", () => {
  const chrome = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 " +
    "Mobile Safari/537.36";
  const samsung = "Mozilla/5.0 (Linux; Android 16; SM-S938U) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36";
  const facebookApp = "Mozilla/5.0 (Linux; Android 15; SM-A156U Build/AP3A; wv) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Version/4.0 Chrome/129.0.0.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/485.0.0.0;]";
  for (const person of [chrome, samsung, facebookApp]) assert.ok(countsAsDownload("GET", person, null), person);

  assert.ok(!countsAsDownload("HEAD", chrome, null), "a HEAD fetches no file");
  assert.ok(!countsAsDownload("POST", chrome, null));
  assert.ok(!countsAsDownload("GET", chrome, "prefetch;prerender"), "Sec-Purpose: a prerender");
  assert.ok(!countsAsDownload("GET", chrome, "prefetch"), "Purpose: a prefetch");
  assert.ok(!countsAsDownload("GET", null, null), "no User-Agent");
  for (const robot of [
    "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
    "Mozilla/5.0 (compatible; meta-externalagent/1.1 (+https://developers.facebook.com/docs/sharing/webmasters/" +
    "crawler))",
    "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
    "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_1) AppleWebKit/601.2.4 (KHTML, like Gecko) Version/9.0.1 " +
    "Safari/601.2.4 facebookexternalhit/1.1 Facebot Twitterbot/1.0",
    "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
    "TelegramBot (like TwitterBot)",
    "WhatsApp/2.24.1.6 A",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/129.0.0.0 Safari/537.36",
    "curl/8.5.0",
    "python-requests/2.32.3",
  ]) assert.ok(!countsAsDownload("GET", robot, null), robot);
});
