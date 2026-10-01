import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';

const app = express();
const PORT = process.env.PROXY_PORT || 3000;
const CLIENT_ID = process.env.MP_CLIENT_ID || '';
const CLIENT_SECRET = process.env.MP_CLIENT_SECRET || '';
const ALLOWED_REDIRECT = process.env.ALLOWED_REDIRECT || 'https://notif.mposw.com.ar/oauth/callback';
const PROXY_KEY = process.env.PROXY_KEY || '';

app.use(helmet({ contentSecurityPolicy: false }));
app.use(express.json({ limit: '16kb' }));
app.use(rateLimit({ windowMs: 60_000, max: 10, standardHeaders: true, legacyHeaders: false }));

function checkKey(req, res, next) {
  if (!PROXY_KEY) return next();
  if (req.header('X-Proxy-Key') === PROXY_KEY) return next();
  return res.status(401).json({ error: 'unauthorized' });
}

app.get('/health', (_req, res) => res.json({ ok: true }));

app.get('/.well-known/assetlinks.json', (_req, res) => {
  const fingerprints = (process.env.ASSETLINKS_SHA256 || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  res.json([{
    relation: ['delegate_permission/common.handle_all_urls'],
    target: {
      namespace: 'android_app',
      package_name: process.env.ANDROID_PACKAGE || 'ar.com.notifmp',
      sha256_cert_fingerprints: fingerprints,
    },
  }]);
});

// Callback público: no loguea code/state, solo redirige al deep link de la app
app.get('/oauth/callback', (req, res) => {
  const code = String(req.query.code || '');
  const state = String(req.query.state || '');
  if (!code || !state) return res.status(400).send('Faltan code/state');
  const deep = `mpnotify://oauth/callback?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`;
  res.send(`<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Notif-MP</title><meta http-equiv="refresh" content="0;url=${deep}"></head><body style="font-family:sans-serif;text-align:center;padding:40px"><h2>Volviendo a Notif-MP…</h2><p><a href="${deep}">Abrir app</a></p></body></html>`);
});

async function mpToken(payload) {
  const r = await fetch('https://api.mercadopago.com/oauth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(payload),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok || !data.access_token) {
    const safe = { ...data };
    delete safe.access_token;
    delete safe.refresh_token;
    console.error('mp token fail', r.status, JSON.stringify(safe).slice(0, 300));
    return { status: r.status, error: 'mp_token_failed' };
  }
  return { status: 200, data };
}

app.post('/oauth/exchange', checkKey, async (req, res) => {
  const { code, code_verifier, redirect_uri } = req.body || {};
  if (!CLIENT_ID || !CLIENT_SECRET) return res.status(500).json({ error: 'proxy_misconfigured' });
  if (!code || !code_verifier || redirect_uri !== ALLOWED_REDIRECT) {
    return res.status(400).json({ error: 'bad_request' });
  }
  const out = await mpToken({
    grant_type: 'authorization_code', client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
    code, redirect_uri, code_verifier,
  });
  if (out.status !== 200) return res.status(502).json({ error: out.error });
  return res.json(out.data);
});

app.post('/oauth/refresh', checkKey, async (req, res) => {
  const { refresh_token } = req.body || {};
  if (!CLIENT_ID || !CLIENT_SECRET) return res.status(500).json({ error: 'proxy_misconfigured' });
  if (!refresh_token) return res.status(400).json({ error: 'bad_request' });
  const out = await mpToken({
    grant_type: 'refresh_token', client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
    refresh_token,
  });
  if (out.status !== 200) return res.status(502).json({ error: out.error });
  return res.json(out.data);
});

app.listen(PORT, () => console.log(`notif-mp-proxy on ${PORT}`));
