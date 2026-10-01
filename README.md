# Notif-MP

App Android nativa (Kotlin + Compose) que **notifica transferencias de Mercado Pago** en tiempo real, con OAuth, sonido/anuncio de monto, modo oscuro, almacenamiento local, pantalla siempre encendida (opcional), exportación CSV+XLSX y banner AdMob al pie. 100% local salvo `api.mercadopago.com`, el micro-proxy OAuth y AdMob.

- Repo: `git@github.com:hectororviz/notif-mp.git`
- Carpeta: `~/notif-mp` (repo independiente, nada de `m-posw/` se toca)
- Infra: `https://notif.mposw.com.ar` detrás de `caddy-docker-proxy` (red `caddy_net`)

```
~/notif-mp/
  android/   # APK Nativo Kotlin (En vivo / Movimientos / Config / Diag temporal)
  proxy/     # micro-proxy OAuth stateless (único con MP_CLIENT_SECRET)
  docker-compose.yml
  .env / .env.example
  .github/workflows/build-apk.yml  # APK debug → release notif-latest
  docs/      # privacy-policy.md, ficha-play.md
```

## 1. Arquitectura

```
MP ──auth──▶ Custom Tab ──redirect──▶ proxy /oauth/callback ──deep link──▶ App
App ──POST /oauth/exchange {code, verifier}──▶ proxy (+secret) ──▶ api.mercadopago.com/oauth/token
App guarda access/refresh en EncryptedSharedPreferences (Keystore, nunca en log/APK)
App ──GET /v1/payments/search (Bearer)──▶ api.mercadopago.com ──▶ Room ──▶ Notif + TTS
```

- `APK nunca contiene MP_CLIENT_SECRET`. Solo `CLIENT_ID`, `REDIRECT_URI`, `PROXY_URL` (BuildConfig).
- Proxy stateless: `POST /oauth/exchange`, `POST /oauth/refresh`, `GET /oauth/callback` (HTML → deep link), `GET /.well-known/assetlinks.json`, `GET /health`. Sin DB, rate-limit 10/min/IP, no loguea tokens.
- Polling: `ForegroundService` dual — base configurable **10–60s** + **Turbo 2s** (botón en En vivo, auto-retorno al recibir transferencia o timeout 10min). Filtro `approved + cvu/money_transfer`, dedup por `paymentId` en Room, cursor persistido.
- Push MP: no usa webhooks (polling directo, 100% local).

## 2. UI (vertical, 3 solapas + Diag temporal)

**En vivo:** hero con último monto (grande), hora/pagador, cadencia actual (`Cada 30s` / `Cada 2s · TURBO`), últimas 5 centradas, botón **Turbo 2s** (se deshabilita activo, se rehabilita al recibir transferencia/timeout). Banner AdMob fijo al pie.

**Movimientos:** cards `Total hoy | Total ayer` (ARS, `America/Argentina`), filtros `Desde/Hasta` (aaaa-mm-dd) + Filtrar, botones **CSV / Excel** (SAF `ACTION_CREATE_DOCUMENT`, columnas fecha,hora,monto,pagador,email,payment_id,tipo,estado), tap fila → modal detalle (monto, fecha, pagador, email, payment_id, tipo, aprobada).

**Configuración:** estado OAuth + Vincular (Custom Tab) + QR de la misma `auth_url` + campo code manual + Canjear; Modo oscuro switch; Sonido on/off; Monto hablado TTS (es-AR) on/off; No apagar pantalla (`FLAG_KEEP_SCREEN_ON` solo En vivo); Intervalo base 10–60s; Guardar e iniciar.

**Diag (temporal, se quita al estabilizar):** log OAuth in-app (`callback/state/exchange/store`), botón Copiar/Limpiar, secretos ofuscados (`TG-…`).

## 3. Credenciales MP

App MP `Notif-MP` (crear nueva, no reusar m-POSw):

- `MP_CLIENT_ID=5631264729819538` (público, va en BuildConfig)
- `MP_CLIENT_SECRET` (solo en `~/notif-mp/.env` del VPS, **nunca** en chat/repo/APK — rotar si se expuso)
- `redirect_uri=https://notif.mposw.com.ar/oauth/callback` (idéntico en panel MP, BuildConfig y `ALLOWED_REDIRECT`)
- Panel MP: activar `Authorization Code + PKCE` (challenge S256 obligatorio), scopes `read write offline_access` (refresh 180 días, code 10min/único uso)

## 4. Deploy proxy (VPS)

```bash
cd ~/notif-mp
cp .env.example .env   # completar MP_CLIENT_SECRET y ASSETLINKS_SHA256
docker compose up -d --build
curl https://notif.mposw.com.ar/health                        # {"ok":true}
curl https://notif.mposw.com.ar/.well-known/assetlinks.json  # paquetes + fingerprints
```

Compose: 1 servicio `notif-mp-proxy` en red externa `caddy_net` con labels `caddy: notif.mposw.com.ar` (patrón `~/oauth-mposw`). DNS `A notif.mposw.com.ar → VPS` ya resuelve. Tras release con keystore propio, cargar su SHA256 en `ASSETLINKS_SHA256` (coma-separados) y `ANDROID_PACKAGES` si cambia el package.

## 5. App (Android Studio / CI)

- `minSdk 26, target/compile 34, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7` (pinneado en CI; Gradle 10 rompe AGP 8.5).
- `AndroidManifest`: `INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE[_SPECIAL_USE], RECEIVE_BOOT_COMPLETED, AD_ID`; App Link `https://notif.mposw.com.ar/oauth/callback` + scheme `mpnotify://oauth/callback`; FGS `specialUse`; AdMob `APPLICATION_ID ca-app-pub-9763480712544528~9980738235`, banner `ca-app-pub-9763480712544528/7201783224`.
- CI: `push main (android/**)` → JDK17 + SDK34 → `gradle :app:assembleDebug` → `notif-mp.apk` → release rolling `notif-latest` (`softprops/action-gh-release@v2`). Debug = `ar.com.notifmp.debug` (App Links no autoverifican hasta keystore release).
- Issues CI ya resueltos: `sdkmanager` vía `ANDROID_HOME/cmdline-tools` en PATH; `.gradle.kts` (era DSL Kotlin en `.gradle`); Moshi `KotlinJsonAdapterFactory`; imports `LocalLifecycleOwner` (compose-ui).

## 6. Probar

1. Instalar `notif-mp.apk` de `Releases → notif-latest`.
2. Config → Vincular → OK en MP → **tocar "Abrir app"** en la página celeste (App Link auto solo con release) → Toast "¡Vinculado! ✓". Fallback: pegar `code=TG-…` en campo manual + Canjear. Diag muestra cada etapa.
3. Transferir a la cuenta → ~base (o 2s en Turbo) suena + TTS + hero/últimas 5.
4. Movimientos: totales, filtro, modal, CSV/XLSX.
5. Fondo: bloquear 5min, matar app, reiniciar celu → reanuda si estaba activo.

## 7. Troubleshooting

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `sdkmanager: command not found` | no estaba en PATH | ya fixeado: `ANDROID_HOME/cmdline-tools/latest/bin` al PATH |
| `compileSdkVersion is not specified` | cascada de fallo config (Gradle 10 / script no compiló) | Gradle 8.7 pinneado + `.gradle.kts` |
| `Unable to create converter for TokenResp` | Moshi sin adaptador Kotlin | fixeado con `KotlinJsonAdapterFactory` |
| MP OK pero app no vincula | no se tocó "Abrir app" / state / code reuse | Diag: `callback/state/exchange`; manual fallback; proxy `docker logs` (si no hay POST, no salió del celu) |
| `mp_token_failed` en proxy | code usado/expirado, redirect mismatch, PKCE | re-vincular (code único 10min), igualar redirect exacto, revisar método PKCE en panel |
| Sin avisos en fondo | Doze/OEM, optimización batería | pedir ignorar optimización, FGS persistente, probar en 2 marcas |

## 8. Pendientes

- Ajustes finos GUI (tipografía/espaciados En vivo).
- Keystore release + AAB firmado Play + SHA256 a `assetlinks.json` (App Link automático).
- Quitar solapa **Diag** al estabilizar (dejar card último error en Config).
- Rotar `MP_CLIENT_SECRET` (se pegó en chat).
