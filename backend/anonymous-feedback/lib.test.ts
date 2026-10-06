// Tests of the pure parts of the feedback function. Run with either:
//   deno test backend/anonymous-feedback/lib.test.ts
//   node --experimental-strip-types --test backend/anonymous-feedback/lib.test.ts
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ALLOWED_ORIGINS,
  Invalid,
  ipv6,
  networkOf,
  reference,
  secondsUntilUtcMidnight,
  utcDay,
  validate,
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
});
