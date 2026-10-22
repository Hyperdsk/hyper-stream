// Signs a TV in with the 8-digit code a signed-in phone or Mac showed (see hs_create_tv_code).
// Deploy with "Verify JWT" off: the TV isn't signed in yet and calls it with the publishable key.
// Returns a one-time sign-in token for that account, which the TV exchanges for its own session.
// Each address gets a few wrong codes per 15 minutes (and everyone together a few hundred a minute),
// so a live code can't be found by trying them all.
import { createClient } from "jsr:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

// The runtime provides the server key; newer projects also list them as SUPABASE_SECRET_KEYS
function serverKey(): string | undefined {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    return Object.values(keys)[0] as string | undefined;
  } catch {
    return undefined;
  }
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST." }, 405);
  const body = await req.json().catch(() => ({}));
  const code = String(body?.code ?? "").replace(/\D/g, "");
  if (code.length !== 8) return json({ error: "Enter the 8-digit code." }, 400);

  const key = serverKey();
  if (!key) return json({ error: "The function has no server key." }, 500);
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, key, { auth: { persistSession: false } });

  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
  const since = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
  const { count: mine } = await admin.from("hs_tv_attempts").select("*", { count: "exact", head: true }).eq("ip", ip).gt("at", since(15));
  const { count: everyone } = await admin.from("hs_tv_attempts").select("*", { count: "exact", head: true }).gt("at", since(1));
  if ((mine ?? 0) >= 10 || (everyone ?? 0) >= 300) {
    return json({ error: "Too many wrong codes. Wait 15 minutes, then show a new code." }, 429);
  }
  // Used once: taken out of the table as it's read
  const { data: row } = await admin
    .from("hs_tv_codes")
    .delete()
    .eq("code", code)
    .gt("expires_at", new Date().toISOString())
    .select("user_id")
    .maybeSingle();
  if (!row) {
    await admin.from("hs_tv_attempts").insert({ ip });
    // Old attempts aren't needed once they're out of every window
    await admin.from("hs_tv_attempts").delete().lt("at", since(60 * 24));
    return json({ error: "That code is wrong or has expired. Show a new one." }, 400);
  }

  const { data: user, error: userError } = await admin.auth.admin.getUserById(row.user_id);
  const email = user?.user?.email;
  if (userError || !email) return json({ error: "Couldn't find that account." }, 400);

  // A sign-in link's token, without sending any email
  const { data: link, error: linkError } = await admin.auth.admin.generateLink({ type: "magiclink", email });
  if (linkError || !link?.properties?.hashed_token) return json({ error: linkError?.message ?? "Couldn't sign in." }, 500);
  // Not the email: the TV reads it from its own session, and a wrong caller shouldn't learn it
  return json({ token_hash: link.properties.hashed_token });
});
