
const ALLOWED_ORIGINS = new Set([
  "https://offerfilter.org",
  "https://www.offerfilter.org",
  "https://dillxn.github.io",
]);

const CATEGORIES = new Set([
  "general",
  "bug",
  "feature",
  "ux",
  "privacy",
  "update",
  "other",
]);

const encoder = new TextEncoder();

function cors(origin: string | null): Record<string, string> {
  if (origin && ALLOWED_ORIGINS.has(origin)) {
    return {
      "Access-Control-Allow-Origin": origin,
      "Vary": "Origin",
      "Access-Control-Allow-Headers": "content-type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
    };
  }
  return {};
}

function json(origin: string | null, status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...cors(origin),
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function text(value: unknown, max: number): string | null {
  if (value == null) return null;
  if (typeof value !== "string") throw new Error("invalid text");
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (trimmed.length > max) throw new Error("text too long");
  return trimmed;
}

function intField(value: unknown, min: number, max: number): number | null {
  if (value == null) return null;
  if (!Number.isInteger(value)) throw new Error("invalid integer");
  const n = Number(value);
  if (n < min || n > max) throw new Error("invalid integer");
  return n;
}

function forwardedIp(req: Request): string {
  const cf = req.headers.get("cf-connecting-ip")?.trim();
  if (cf) return cf;
  const forwarded = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (forwarded) return forwarded;
  const real = req.headers.get("x-real-ip")?.trim();
  if (real) return real;
  return "unavailable";
}

async function rateBucket(ip: string, secret: string): Promise<string> {
  const day = new Date().toISOString().slice(0, 10);
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    encoder.encode(`offer-filter-feedback\0${day}\0${ip}`),
  );
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
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
  if (Number.isFinite(declaredLength) && declaredLength > 150_000) {
    return json(origin, 413, { ok: false, error: "too_large" });
  }

  const raw = await req.text();
  if (encoder.encode(raw).length > 150_000) {
    return json(origin, 413, { ok: false, error: "too_large" });
  }

  let body: Record<string, unknown>;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error("invalid json");
    body = parsed as Record<string, unknown>;
  } catch {
    return json(origin, 400, { ok: false, error: "invalid_json" });
  }

  try {
    const kind = text(body.kind, 24) ?? "feedback";
    if (!["feedback", "problem", "diagnostics"].includes(kind)) throw new Error("invalid kind");

    const source = origin ? "web" : "android";
    if (source === "web" && kind !== "feedback") throw new Error("web feedback only");

    const category = text(body.category, 64) ?? "general";
    if (!CATEGORIES.has(category)) throw new Error("invalid category");

    const message = text(body.message, 4000);
    const diagnostics = text(body.diagnostics, 60000);
    const diagnosticsConsented = body.diagnosticsConsented === true;

    if (diagnostics && !diagnosticsConsented) throw new Error("diagnostics consent required");
    if (kind === "feedback" && !message) throw new Error("feedback message required");
    if (kind === "diagnostics" && !diagnostics) throw new Error("diagnostics required");
    if (!message && !diagnostics) throw new Error("empty");

    const appVersion = text(body.appVersion, 32);
    if (appVersion && !/^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$/.test(appVersion)) {
      throw new Error("invalid app version");
    }
    const appVersionCode = intField(body.appVersionCode, 1, 10_000_000);

    const reportToken = text(body.reportToken, 32);
    if (reportToken && !/^[a-f0-9]{32}$/.test(reportToken)) throw new Error("invalid report token");
    const partIndex = intField(body.partIndex ?? 0, 0, 31) ?? 0;
    const partCount = intField(body.partCount ?? 1, 1, 32) ?? 1;
    if (partIndex >= partCount) throw new Error("invalid part");

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceRole) {
      return json(origin, 503, { ok: false, error: "temporarily_unavailable" });
    }

    const bucket = await rateBucket(forwardedIp(req), serviceRole);

    const rpc = await fetch(`${supabaseUrl}/rest/v1/rpc/submit_offer_filter_feedback`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": serviceRole,
        "Authorization": `Bearer ${serviceRole}`,
        "Prefer": "return=representation",
      },
      body: JSON.stringify({
        p_rate_bucket: bucket,
        p_kind: kind,
        p_category: category,
        p_message: message,
        p_diagnostics: diagnostics,
        p_app_version: appVersion,
        p_app_version_code: appVersionCode,
        p_source: source,
        p_diagnostics_consented: diagnosticsConsented,
        p_report_token: reportToken,
        p_part_index: partIndex,
        p_part_count: partCount,
      }),
    });

    if (!rpc.ok) {
      if (rpc.status === 429) return json(origin, 429, { ok: false, error: "rate_limited" });
      const err = await rpc.text();
      if (err.includes("rate limit exceeded")) {
        return json(origin, 429, { ok: false, error: "rate_limited" });
      }
      console.error("feedback storage failed", rpc.status);
      return json(origin, 502, { ok: false, error: "temporarily_unavailable" });
    }

    const stored = await rpc.json();
    const id = typeof stored === "string" ? stored : String(stored ?? "");
    return json(origin, 201, { ok: true, reference: id.replace(/"/g, "").slice(0, 8) });
  } catch {
    return json(origin, 400, { ok: false, error: "invalid_feedback" });
  }
});
