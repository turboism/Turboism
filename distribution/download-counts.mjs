/** Optional request analytics; independent from release verification and binary integrity. */
export const DEDUP_WINDOW_SECONDS = 1800;
const WINDOW_MS = DEDUP_WINDOW_SECONDS * 1000;
const validCount = n => Number.isSafeInteger(n) && n >= 0;
const iso = ms => new Date(ms).toISOString();
const sum = values => {
  if (values.some(n => !validCount(n))) return null;
  const total = values.reduce((a, b) => a + b, 0);
  return validCount(total) ? total : null;
};

export function eligibleStart(request, response, asset) {
  if (request.method !== 'GET' || !/\.(exe|jar|zip)$/.test(asset.name)) return false;
  // This is a public analytics opt-out, NOT authorization or a protection bypass.
  if (request.headers.get('X-Turboism-Download-Purpose') === 'verification') return false;
  if (/prefetch|prerender/i.test([request.headers.get('Purpose'), request.headers.get('Sec-Purpose')].join(' '))) return false;
  if (response.status === 200) return true;
  return response.status === 206 && /^bytes 0-\d+\/\d+$/.test(response.headers.get('Content-Range') ?? '');
}

/** All mutations after HMAC are synchronous SQLite transactions, including dedup. */
export class DownloadCounter {
  constructor(storage, now = Date.now) { this.storage = storage; this.sql = storage.sql; this.now = now; this.meta = null; this.nextPrune = 0; }
  initialize() {
    this.sql.exec('CREATE TABLE IF NOT EXISTS download_counter_meta (id INTEGER PRIMARY KEY, since TEXT NOT NULL, salt TEXT NOT NULL)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS download_totals (asset_key TEXT PRIMARY KEY, count INTEGER NOT NULL CHECK(count>=0), updated_at TEXT NOT NULL)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS download_seen (fingerprint TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)');
    this.sql.exec('CREATE INDEX IF NOT EXISTS download_seen_expiry ON download_seen(expires_at)');
    this.sql.exec('INSERT OR IGNORE INTO download_counter_meta(id,since,salt) VALUES(1,?,?)', iso(this.now()), crypto.randomUUID()+crypto.randomUUID());
    this.meta = this.sql.exec('SELECT since,salt FROM download_counter_meta WHERE id=1').toArray()[0];
    if (!this.meta?.salt || !Number.isFinite(Date.parse(this.meta.since))) throw new Error('Invalid analytics metadata');
  }
  prune() {
    const now = this.now();
    if (now < this.nextPrune) return;
    this.sql.exec('DELETE FROM download_seen WHERE expires_at<=?', now);
    this.nextPrune = now + 60000;
  }
  async record(request, response, asset) {
    if (!eligibleStart(request, response, asset)) return false;
    if (!this.meta) throw new Error('Analytics not initialized');
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(this.meta.salt), {name:'HMAC',hash:'SHA-256'}, false, ['sign']);
    // Raw IP/UA exist only in this invocation; storage contains a salted, asset-scoped digest.
    // Same-network/same-UA downloads may be grouped: this is not a unique-user metric.
    const input = JSON.stringify([asset.key, request.headers.get('CF-Connecting-IP') ?? 'unknown', (request.headers.get('User-Agent') ?? '').slice(0,512)]);
    const hash = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(input));
    const fingerprint = Array.from(new Uint8Array(hash), b=>b.toString(16).padStart(2,'0')).join('');
    const now = this.now(); this.prune();
    return this.storage.transactionSync(() => {
      const old = this.sql.exec('SELECT expires_at FROM download_seen WHERE fingerprint=?', fingerprint).toArray()[0];
      this.sql.exec('INSERT OR REPLACE INTO download_seen(fingerprint,expires_at) VALUES(?,?)', fingerprint, now+WINDOW_MS);
      if (old && old.expires_at > now) return false;
      const total = this.sql.exec('SELECT count FROM download_totals WHERE asset_key=?', asset.key).toArray()[0]?.count ?? 0;
      if (!validCount(total) || total >= Number.MAX_SAFE_INTEGER) throw new Error('Counter capacity exceeded');
      this.sql.exec('INSERT INTO download_totals(asset_key,count,updated_at) VALUES(?,1,?) ON CONFLICT(asset_key) DO UPDATE SET count=count+1,updated_at=excluded.updated_at', asset.key, iso(now));
      return true;
    });
  }
  read(assets) {
    if (!this.meta) throw new Error('Analytics not initialized');
    this.prune();
    const keys = [...new Set(assets.map(a=>a.key))];
    const rows = keys.map(key=>this.sql.exec('SELECT count,updated_at FROM download_totals WHERE asset_key=?',key).toArray()[0]);
    const count = sum(rows.map(r=>r?.count ?? 0));
    if (count === null) throw new Error('Invalid stored counter');
    return {count, assets:keys.map((key,i)=>({key,count:rows[i]?.count??0})), since:this.meta.since, dedupWindowSeconds:DEDUP_WINDOW_SECONDS,
      updatedAt:rows.reduce((latest,r)=>r?.updated_at>latest?r.updated_at:latest,this.meta.since)};
  }
}

export function countsDocument(version, record, official) {
  if (!record?.active || record.release.tag !== 'v'+version || record.release.assets.length !== 4) throw new Error('Unknown release');
  const githubAsOf = Number.isFinite(Date.parse(record.countsAsOf)) ? record.countsAsOf : null;
  const github = githubAsOf ? sum(record.release.assets.map(a=>a.downloadCount)) : null;
  const officialCount = official && validCount(official.count) ? official.count : null;
  const complete = officialCount !== null && github !== null;
  const total = complete ? sum([officialCount,github]) : null;
  return {schemaVersion:1, status:complete && total !== null ? 'ready':'partial', version, tag:record.release.tag,
    unit:'download_requests', total,
    assets:record.release.assets.map(a=>{
      const stored=official?.assets?.find(row=>row.key===a.key)?.count;
      const direct=validCount(stored)?stored:null;
      const upstream=githubAsOf&&validCount(a.downloadCount)?a.downloadCount:null;
      return {key:a.key,name:a.name,sha256:a.sha256,official:direct,github:upstream,total:sum([direct,upstream])};
    }),
    official:{count:officialCount,since:official?.since ?? null,dedupWindowSeconds:DEDUP_WINDOW_SECONDS},
    github:{count:github,asOf:githubAsOf},
    asOf:[official?.updatedAt,githubAsOf].filter(Boolean).sort().at(-1) ?? new Date().toISOString()};
}
