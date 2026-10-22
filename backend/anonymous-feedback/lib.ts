// Pure parts of the accountless feedback function: what a request must hold (a part of a submission, or a count),
// and which network it came from. No I/O here.

/** The website's origins. A request with any other Origin is refused; the app sends none. */
export const ALLOWED_ORIGINS: ReadonlySet<string> = new Set([
  "https://offerfilter.org",
  "https://www.offerfilter.org",
]);

export const KINDS: ReadonlySet<string> = new Set(["feedback", "problem", "diagnostics"]);

export const CATEGORIES: ReadonlySet<string> = new Set([
  "general",
  "bug",
  "feature",
  "ux",
  "privacy",
  "update",
  "other",
]);

/** An app version as the app and the website send it ("0.6.3"): at most 32 characters, no spaces. */
const APP_VERSION = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$/;

export const LIMITS = {
  /** A request's body, in bytes. */
  bodyBytes: 150_000,
  /** What the user typed, in characters. */
  message: 4_000,
  /** One part's diagnostics, in characters. */
  diagnostics: 60_000,
  /** Parts of one submission (one report token). */
  parts: 4,
} as const;

/** One part of a submission, checked. */
export interface Submission {
  kind: string;
  category: string;
  message: string | null;
  diagnostics: string | null;
  diagnosticsConsented: boolean;
  appVersion: string | null;
  appVersionCode: number | null;
  source: "android" | "web";
  reportToken: string | null;
  partIndex: number;
  partCount: number;
}

/** A request the service will never store, whatever is retried: answered 400. */
export class Invalid extends Error {}

/** A short text field, trimmed; null when absent or blank. */
function trimmed(value: unknown, max: number): string | null {
  if (value == null) return null;
  if (typeof value !== "string") throw new Invalid("invalid text");
  const text = value.trim();
  if (!text) return null;
  if (text.length > max) throw new Invalid("text too long");
  return text;
}

/**
 * Diagnostics, exactly as sent: never trimmed (the app frames each part with a header and an end line, and the text
 * between them must reassemble byte for byte). Blank diagnostics are not diagnostics: null.
 */
function untrimmed(value: unknown, max: number): string | null {
  if (value == null) return null;
  if (typeof value !== "string") throw new Invalid("invalid text");
  if (!value.trim()) return null;
  if (value.length > max) throw new Invalid("text too long");
  return value;
}

function integer(value: unknown, min: number, max: number): number | null {
  if (value == null) return null;
  if (typeof value !== "number" || !Number.isInteger(value)) throw new Invalid("invalid integer");
  if (value < min || value > max) throw new Invalid("invalid integer");
  return value;
}

/** Checks one request's fields. @throws Invalid for a request that could never be stored */
export function validate(body: Record<string, unknown>, origin: string | null): Submission {
  const kind = trimmed(body.kind, 24) ?? "feedback";
  if (!KINDS.has(kind)) throw new Invalid("invalid kind");

  const source = origin ? "web" : "android";
  if (source === "web" && kind !== "feedback") throw new Invalid("web feedback only");

  const category = trimmed(body.category, 64) ?? "general";
  if (!CATEGORIES.has(category)) throw new Invalid("invalid category");

  const message = trimmed(body.message, LIMITS.message);
  const diagnostics = untrimmed(body.diagnostics, LIMITS.diagnostics);
  const diagnosticsConsented = body.diagnosticsConsented === true;
  if (diagnostics && !diagnosticsConsented) throw new Invalid("diagnostics consent required");
  if (kind === "feedback" && !message) throw new Invalid("feedback message required");
  if (kind === "diagnostics" && !diagnostics) throw new Invalid("diagnostics required");
  if (!message && !diagnostics) throw new Invalid("empty");

  const appVersion = trimmed(body.appVersion, 32);
  if (appVersion && !APP_VERSION.test(appVersion)) {
    throw new Invalid("invalid app version");
  }
  const appVersionCode = integer(body.appVersionCode, 1, 10_000_000);

  const reportToken = trimmed(body.reportToken, 32);
  if (reportToken && !/^[a-f0-9]{32}$/.test(reportToken)) throw new Invalid("invalid report token");
  const partIndex = integer(body.partIndex ?? 0, 0, LIMITS.parts - 1) ?? 0;
  const partCount = integer(body.partCount ?? 1, 1, LIMITS.parts) ?? 1;
  if (partIndex >= partCount) throw new Invalid("invalid part");
  // Parts are tied together only by their token.
  if (!reportToken && partCount !== 1) throw new Invalid("parts need a report token");

  return {
    kind,
    category,
    message,
    diagnostics,
    diagnosticsConsented,
    appVersion,
    appVersionCode,
    source,
    reportToken,
    partIndex,
    partCount,
  };
}

/**
 * What a count is: "daily", an install of the app checking in once a UTC day, or "download", the app downloaded
 * through the counted download link (or, from pages loaded before it, a press of the install page's Download button
 * sent as a POST). A count is not feedback: it carries no words, and it is kept as a daily total (its token and its
 * network's day-keyed value only briefly, so it counts once).
 */
export const COUNT_EVENTS: ReadonlySet<string> = new Set(["daily", "download"]);

/** The only keys each count may hold. Any other (a message, a device, a locale) is refused, never dropped. */
const COUNT_KEYS: Readonly<Record<string, ReadonlySet<string>>> = {
  daily: new Set(["kind", "event", "countToken", "first", "appVersion", "appVersionCode"]),
  download: new Set(["kind", "event", "countToken"]),
};

/** A count's token: 32 lowercase hex digits, random. */
const COUNT_TOKEN = /^[a-f0-9]{32}$/;

/** One count, checked. */
export interface Count {
  event: "daily" | "download";
  /**
   * Random, so that a count retried (its answer was lost) is counted once. The app makes one for each UTC day and sends
   * it again only to retry that day's count; the website may send one or none.
   */
  countToken: string | null;
  /** True while the service has never accepted a count from this install. Always false for a download. */
  first: boolean;
  /** The app's version name; null for a download. */
  appVersion: string | null;
  /** The app's version code: checked, never stored. Null for a download. */
  appVersionCode: number | null;
}

/** Whether a request's body is a count (kind "count") rather than a part of a submission. */
export function isCount(body: Record<string, unknown>): boolean {
  return body.kind === "count";
}

/** A count's key as sent: undefined when absent. A key that is present must hold a value: null is refused. */
function given(body: Record<string, unknown>, key: string): unknown {
  const value = body[key];
  if (value === null) throw new Invalid("null field");
  return value;
}

/** A count's text key: null when absent; present, exactly a text the pattern matches (nothing is trimmed). */
function countText(body: Record<string, unknown>, key: string, pattern: RegExp): string | null {
  const value = given(body, key);
  if (value === undefined) return null;
  if (typeof value !== "string" || !pattern.test(value)) throw new Invalid(`invalid ${key}`);
  return value;
}

/**
 * Checks a count. Stricter than a part of a submission: nothing is trimmed, a key that is present must hold a value
 * of its kind, and a key the event does not name is refused. The app's daily count comes with no Origin; the Download
 * button's only from the website's own origins.
 * @throws Invalid for a count that could never be counted
 */
export function validateCount(body: Record<string, unknown>, origin: string | null): Count {
  if (body.kind !== "count") throw new Invalid("not a count");
  if (typeof body.event !== "string" || !COUNT_EVENTS.has(body.event)) throw new Invalid("invalid event");
  const event = body.event as Count["event"];
  const keys = COUNT_KEYS[event];
  for (const key of Object.keys(body)) {
    if (!keys.has(key)) throw new Invalid("unexpected field");
  }
  const fromWebsite = origin ? ALLOWED_ORIGINS.has(origin) : false;
  if (event === "daily" ? Boolean(origin) : !fromWebsite) throw new Invalid("count from the wrong place");

  const countToken = countText(body, "countToken", COUNT_TOKEN);
  if (event === "download") {
    return { event, countToken, first: false, appVersion: null, appVersionCode: null };
  }

  // The app's count is retried only with its own token: without one, a lost answer would have it counted twice.
  if (countToken === null) throw new Invalid("a daily count needs a token");
  const first = given(body, "first") ?? false;
  if (typeof first !== "boolean") throw new Invalid("invalid first");
  const appVersion = countText(body, "appVersion", APP_VERSION);
  const appVersionCode = integer(given(body, "appVersionCode"), 1, 10_000_000);
  return { event, countToken, first, appVersion, appVersionCode };
}

/**
 * Where the counted download link sends every fetch, counted or not: the signed APK on the update server, the file
 * the app's own updates come from.
 */
export const APK_URL = "https://dash-offer-filter-build.onrender.com/OfferFilter.apk";

/** Whether a request is for the counted download link (this function's address and then /download). */
export function isDownloadPath(pathname: string): boolean {
  return /\/download\/?$/.test(pathname);
}

/**
 * What fetches a link for a preview, an index or a script rather than for a person: such a fetch of the download link
 * is sent on to the file and never counted. Only a fetch's own User-Agent is read for it, and it is never kept.
 */
const ROBOT = new RegExp(
  "bot|crawl|spider|slurp|preview|externalhit|facebookcatalog|meta-external|embedly|iframely|vkshare|headless|" +
    "lighthouse|google-inspectiontool|googleother",
  "i",
);

/**
 * Whether a fetch of the download link counts as a download: a GET from a browser (its User-Agent begins
 * "Mozilla/", as every browser's does), not a robot's, and not a fetch made ahead of a tap (a prefetch or prerender,
 * named by Sec-Purpose or Purpose). Everything else is sent on to the file all the same.
 */
export function countsAsDownload(method: string, userAgent: string | null, purpose: string | null): boolean {
  if (method !== "GET") return false;
  if (!userAgent || !userAgent.startsWith("Mozilla/") || ROBOT.test(userAgent)) return false;
  return !(purpose && /prefetch|prerender/i.test(purpose));
}

function ipv4(text: string): number[] | null {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(text);
  if (!match) return null;
  const bytes = match.slice(1).map((part) => Number(part));
  return bytes.every((byte) => byte <= 255) ? bytes : null;
}

/** An IPv6 address as its eight 16-bit groups, or null when it is not one. */
export function ipv6(text: string): number[] | null {
  let address = text.trim().toLowerCase();
  const zone = address.indexOf("%");
  if (zone >= 0) address = address.slice(0, zone);
  if (address.startsWith("[") && address.endsWith("]")) address = address.slice(1, -1);
  if (!address.includes(":") || !/^[0-9a-f:.]+$/.test(address)) return null;

  // An IPv4 address in the last 32 bits ("::ffff:192.0.2.1").
  let tail: number[] = [];
  if (address.includes(".")) {
    const lastColon = address.lastIndexOf(":");
    const v4 = ipv4(address.slice(lastColon + 1));
    if (!v4) return null;
    tail = [(v4[0] << 8) | v4[1], (v4[2] << 8) | v4[3]];
    address = address.slice(0, lastColon + 1);
    if (!address.endsWith("::")) address = address.slice(0, -1);
  }

  const halves = address.split("::");
  if (halves.length > 2) return null;
  const groups = (part: string): number[] | null => {
    if (part === "") return [];
    const out: number[] = [];
    for (const group of part.split(":")) {
      if (!/^[0-9a-f]{1,4}$/.test(group)) return null;
      out.push(parseInt(group, 16));
    }
    return out;
  };
  const head = groups(halves[0]);
  const rest = halves.length === 2 ? groups(halves[1]) : [];
  if (!head || !rest) return null;
  const known = head.length + rest.length + tail.length;
  if (halves.length === 2) {
    // "::" stands for at least one group of zeros.
    if (known > 7) return null;
    return [...head, ...new Array<number>(8 - known).fill(0), ...rest, ...tail];
  }
  return known === 8 ? [...head, ...tail] : null;
}

/**
 * The network a request came from, for rate limiting only: an IPv4 address, or an IPv6 address's /64 (one home or
 * phone network usually holds a whole /64, so a single address would be too easy to change). Only Cloudflare's
 * cf-connecting-ip is trusted; with none, every such request shares one network, "unavailable".
 */
export function networkOf(connectingIp: string | null | undefined): string {
  const text = connectingIp?.trim() ?? "";
  if (!text) return "unavailable";
  const v4 = ipv4(text);
  if (v4) return `4:${v4.join(".")}`;
  const v6 = ipv6(text);
  if (!v6) return "unavailable";
  // An IPv4-mapped address is that IPv4 address.
  if (v6.slice(0, 5).every((group) => group === 0) && v6[5] === 0xffff) {
    return `4:${v6[6] >> 8}.${v6[6] & 0xff}.${v6[7] >> 8}.${v6[7] & 0xff}`;
  }
  return `6:${v6.slice(0, 4).map((group) => group.toString(16)).join(":")}::/64`;
}

/**
 * Seconds until the next UTC midnight (when the daily caps and a network's day of counts start again), at least a
 * minute and at most atMost: an hour unless asked otherwise, so that a submission refused by the cap is tried again
 * within the hour (a count refused for its day waits the whole time).
 */
export function secondsUntilUtcMidnight(now: Date, atMost: number = 3_600): number {
  const midnight = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  const seconds = Math.ceil((midnight - now.getTime()) / 1000);
  return Math.min(atMost, Math.max(60, seconds));
}

/** "2026-10-06": the UTC day, which names the day's salt. */
export function utcDay(now: Date): string {
  return now.toISOString().slice(0, 10);
}

/** The short reference the app and the website show: the first eight hex digits of the submission's first row. */
export function reference(id: unknown): string {
  return String(id ?? "").replace(/[^0-9a-f]/gi, "").slice(0, 8).toLowerCase();
}
