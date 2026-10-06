// The accountless feedback service of Offer Filter (a Supabase Edge Function named offer-filter-feedback).
// Deploy steps, limits and what it stores: README.md. Never deployed from CI.
//
// One JSON POST per part. The app sends no Origin header; the website's form sends its own Origin and may send only
// feedback. Submissions of several parts share a random report token (32 hex digits), and each part is stored once
// (a retry of a stored part is answered with the same reference). Only a submission's first part counts toward the
// per-network rate limit; a global daily cap protects storage. No IP address, user agent or account is stored: the
// network (an IPv4 address or an IPv6 /64, from Cloudflare's cf-connecting-ip only) is turned into a keyed hash with
// a random salt that changes every UTC day, used only to count recent submissions, and kept at most an hour.

import {
  ALLOWED_ORIGINS,
  Invalid,
  LIMITS,
  networkOf,
  reference,
  secondsUntilUtcMidnight,
  type Submission,
  utcDay,
  validate,
} from "./lib.ts";

const encoder = new TextEncoder();
/** After an upstream failure, the app tries again after this long (it backs off further by itself). */
const UPSTREAM_RETRY_SECONDS = 60;
/** The rate limit counts ten minutes. */
const RATE_RETRY_SECONDS = 600;

function cors(origin: string | null): Record<string, string> {
  if (origin && ALLOWED_ORIGINS.has(origin)) {
    return {
      "Access-Control-Allow-Origin": origin,
      "Vary": "Origin",
      "Access-Control-Allow-Headers": "content-type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Max-Age": "86400",
    };
  }
  return {};
}

function json(
  origin: string | null,
  status: number,
  body: Record<string, unknown>,
  retryAfterSeconds?: number,
): Response {
  const headers: Record<string, string> = {
    ...cors(origin),
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  };
  if (retryAfterSeconds !== undefined) headers["Retry-After"] = String(retryAfterSeconds);
  return new Response(JSON.stringify(body), { status, headers });
}

/** Storage could not be reached or failed: the request may be retried as it is. */
class Upstream extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`upstream ${status}`);
    this.status = status;
  }
}

async function rpc(name: string, args: Record<string, unknown>): Promise<unknown> {
  const url = Deno.env.get("SUPABASE_URL");
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceRole) throw new Upstream(0);
  let response: Response;
  try {
    response = await fetch(`${url}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": serviceRole,
        "Authorization": `Bearer ${serviceRole}`,
      },
      body: JSON.stringify(args),
    });
  } catch {
    throw new Upstream(0);
  }
  if (!response.ok) {
    // Never the body: it could echo what was submitted.
    await response.body?.cancel();
    throw new Upstream(response.status);
  }
  return await response.json();
}

/** Today's random salt (made by the database on the day's first request, deleted once the day is over). */
let saltCache: { day: string; salt: string } | null = null;

async function dailySalt(day: string): Promise<string> {
  if (saltCache && saltCache.day === day) return saltCache.salt;
  const salt = await rpc("offer_filter_feedback_daily_salt", {});
  if (typeof salt !== "string" || !/^[0-9a-f]{64}$/.test(salt)) throw new Upstream(502);
  saltCache = { day, salt };
  return salt;
}

/** The network's keyed hash for today: counts recent submissions, and cannot be turned back into an address. */
async function rateBucket(network: string, now: Date): Promise<string> {
  const day = utcDay(now);
  const salt = await dailySalt(day);
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(salt),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    encoder.encode(`offer-filter-feedback\0${day}\0${network}`),
  );
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

async function store(submission: Submission, bucket: string): Promise<{ outcome: string; id: unknown }> {
  const result = await rpc("submit_offer_filter_feedback_v2", {
    p_rate_bucket: bucket,
    p_kind: submission.kind,
    p_category: submission.category,
    p_message: submission.message,
    p_diagnostics: submission.diagnostics,
    p_app_version: submission.appVersion,
    p_app_version_code: submission.appVersionCode,
    p_source: submission.source,
    p_diagnostics_consented: submission.diagnosticsConsented,
    p_report_token: submission.reportToken,
    p_part_index: submission.partIndex,
    p_part_count: submission.partCount,
  });
  const row = Array.isArray(result) ? result[0] : result;
  if (!row || typeof row !== "object") throw new Upstream(502);
  const { outcome, reference: id } = row as Record<string, unknown>;
  if (typeof outcome !== "string") throw new Upstream(502);
  return { outcome, id };
}

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("origin");

  if (req.method === "OPTIONS") {
    if (!origin || !ALLOWED_ORIGINS.has(origin)) {
      return new Response(null, { status: 403, headers: { "Cache-Control": "no-store" } });
    }
    return new Response(null, { status: 204, headers: { ...cors(origin), "Cache-Control": "no-store" } });
  }

  if (req.method !== "POST") return json(origin, 405, { ok: false, error: "method_not_allowed" });
  if (origin && !ALLOWED_ORIGINS.has(origin)) return json(origin, 403, { ok: false, error: "origin_not_allowed" });

  const contentType = req.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    return json(origin, 415, { ok: false, error: "json_required" });
  }

  const declaredLength = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > LIMITS.bodyBytes) {
    return json(origin, 413, { ok: false, error: "too_large" });
  }
  const raw = await req.text();
  if (encoder.encode(raw).length > LIMITS.bodyBytes) {
    return json(origin, 413, { ok: false, error: "too_large" });
  }

  let submission: Submission;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Invalid("invalid json");
    submission = validate(parsed as Record<string, unknown>, origin);
  } catch {
    return json(origin, 400, { ok: false, error: "invalid_feedback" });
  }

  try {
    const now = new Date();
    const bucket = await rateBucket(networkOf(req.headers.get("cf-connecting-ip")), now);
    const { outcome, id } = await store(submission, bucket);
    switch (outcome) {
      case "stored":
      case "duplicate":
        // One reference per submission: its first part's.
        return json(origin, 201, { ok: true, reference: reference(id) });
      case "rate_limited":
        return json(origin, 429, { ok: false, error: "rate_limited" }, RATE_RETRY_SECONDS);
      case "daily_cap":
        return json(origin, 503, { ok: false, error: "temporarily_unavailable" }, secondsUntilUtcMidnight(now));
      case "too_large":
        return json(origin, 413, { ok: false, error: "too_large" });
      case "invalid":
      case "part_mismatch":
      case "part_out_of_order":
        return json(origin, 400, { ok: false, error: outcome });
      default:
        throw new Upstream(502);
    }
  } catch (failure) {
    console.error("feedback storage failed", failure instanceof Upstream ? failure.status : "unexpected");
    return json(origin, 503, { ok: false, error: "temporarily_unavailable" }, UPSTREAM_RETRY_SECONDS);
  }
});
