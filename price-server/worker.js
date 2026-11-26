// WindSMP-Preis-Server (Cloudflare Worker + D1, Binding-Name "DB").
// Clients schicken gesehene /ah- und /orders-Preise (POST /report),
// alle Clients holen die Durchschnittspreise (GET /prices).
// Tabelle wird beim ersten Aufruf automatisch angelegt.

const DAYS = 7;            // Preise der letzten 7 Tage zählen
const KEEP_DAYS = 31;      // ältere Einträge werden gelöscht
const MAX_PER_REPORT = 300;
const CACHE_S = 120;       // /prices wird 2 min gecacht

let ready = false;
async function init(db) {
  if (ready) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS obs (
      h TEXT PRIMARY KEY, item TEXT NOT NULL, name TEXT, src TEXT NOT NULL,
      price REAL NOT NULL, ts INTEGER NOT NULL)`),
    db.prepare(`CREATE INDEX IF NOT EXISTS obs_ts ON obs(ts)`),
  ]);
  ready = true;
}

const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json", "access-control-allow-origin": "*", ...extra },
});

function median(a) {
  a.sort((x, y) => x - y);
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    await init(env.DB);

    if (req.method === "POST" && url.pathname === "/report") {
      let body;
      try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
      const list = Array.isArray(body?.obs) ? body.obs.slice(0, MAX_PER_REPORT) : [];
      const now = Date.now(), hour = Math.floor(now / 3600_000);
      const stmts = [];
      for (const o of list) {
        const item = String(o.item ?? "").slice(0, 200);
        const src = o.src === "order" ? "order" : "ah";
        const price = Number(o.price);
        if (!item || !(price > 0) || price > 1e15) continue;
        // gleiches Angebot (Item, Quelle, Preis, Seller/Anzahl-Fingerprint) zählt pro Stunde nur einmal
        const h = `${item}|${src}|${price}|${String(o.fp ?? "").slice(0, 80)}|${hour}`;
        stmts.push(env.DB.prepare("INSERT OR IGNORE INTO obs (h,item,name,src,price,ts) VALUES (?,?,?,?,?,?)")
          .bind(h, item, String(o.name ?? "").slice(0, 80), src, price, now));
      }
      if (stmts.length) await env.DB.batch(stmts);
      if (Math.random() < 0.02) ctx.waitUntil(env.DB.prepare("DELETE FROM obs WHERE ts < ?").bind(now - KEEP_DAYS * 86400_000).run());
      return json({ ok: true, saved: stmts.length });
    }

    if (req.method === "GET" && url.pathname === "/prices") {
      const cache = caches.default, key = new Request(url.origin + "/prices");
      const hit = await cache.match(key);
      if (hit) return hit;
      const since = Date.now() - DAYS * 86400_000;
      const { results } = await env.DB.prepare("SELECT item, name, src, price, ts FROM obs WHERE ts > ?").bind(since).all();
      const by = {};
      for (const r of results) {
        const e = by[r.item] ??= { name: r.name, ah: [], order: [], t: 0 };
        e[r.src].push(r.price);
        if (r.ts > e.t) { e.t = r.ts; if (r.name) e.name = r.name; }
      }
      const items = {};
      for (const [k, e] of Object.entries(by)) {
        items[k] = {
          name: e.name,
          ah: e.ah.length ? median(e.ah) : 0, ahN: e.ah.length,
          o: e.order.length ? median(e.order) : 0, oN: e.order.length,
          t: e.t,
        };
      }
      const res = json({ updated: Date.now(), days: DAYS, items }, 200, { "cache-control": `public, max-age=${CACHE_S}` });
      ctx.waitUntil(cache.put(key, res.clone()));
      return res;
    }

    if (url.pathname === "/") return json({ name: "WindSMP-Preis-Server", ok: true });
    return json({ error: "not found" }, 404);
  },
};
