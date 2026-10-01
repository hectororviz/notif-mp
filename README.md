# Notif-MP

App Android nativa (Kotlin) que notifica transferencias de Mercado Pago.

- `android/`: APK (En vivo / Movimientos / Configuración + banner AdMob al pie)
- `proxy/`: micro-proxy OAuth stateless (único que conoce `MP_CLIENT_SECRET`)
- Infra: `https://notif.mposw.com.ar` detrás de `caddy-docker-proxy` (`caddy_net`)

## Deploy proxy (VPS)

```bash
cp .env.example .env  # completar MP_CLIENT_SECRET y ASSETLINKS_SHA256
docker compose up -d --build
curl https://notif.mposw.com.ar/health
```

## App

Abrir `android/` en Android Studio. `BuildConfig` lleva solo `CLIENT_ID`, `REDIRECT_URI` y `PROXY_URL` (sin secret).

APK debug automático: Release `notif-latest` en GitHub Actions.
