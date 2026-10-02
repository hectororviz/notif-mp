# Notif-MP

App Android nativa (Kotlin + Compose) que **notifica transferencias de Mercado Pago** en tiempo real, con OAuth, sonido/anuncio de monto (1er y 2do plano), modo oscuro, almacenamiento local, pantalla siempre encendida (opcional), exportación CSV+XLSX, icono propio y Premium sin publicidad (compra única Play Billing). 100% local salvo `api.mercadopago.com`, el micro-proxy OAuth, AdMob y Play Billing.

- Repo: `git@github.com:hectororviz/notif-mp.git`
- Carpeta: `~/notif-mp` (repo independiente, nada de `m-posw/` se toca)
- Infra: `https://notif.mposw.com.ar` detrás de `caddy-docker-proxy` (red `caddy_net`)

```
~/notif-mp/
  android/   # APK Nativo Kotlin (En vivo / Movimientos / Config + Onboarding)
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

## 2. UI (vertical, 3 solapas + Onboarding)

**Onboarding (primera apertura):** 4 slides `HorizontalPager` con ilustración vectorial (Vincular / Monitoreo+LED / Aviso de cobro / Movimientos+Exportar), `Saltar/Siguiente/Empezar`, flag `ONBOARD_DONE` en DataStore, reabrible desde Config → `Ver ayuda`.

**En vivo:** fila superior `Última transferencia` + botón LED ON/OFF (verde parpadeante = consulta activa, rojo fijo = pausado: sin requests ni avisos, `SERVICE_ON=false` + `PollService.stop()`); monto última en negrita formato es-AR (`$ 125.000,00`), debajo `dd/MM/yyyy HH:mm` pequeño + cadencia (`Cada 30s` / `Cada 2s · TURBO` / `Pausado`); tabla últimas 10 sin líneas (monto `$ ...` + fecha/hora); botón **Turbo 2s** compacto centrado al pie (se deshabilita activo). Al llegar pago nuevo: animación Lottie `assets/success.json` centrada + tono `res/raw/mp.mp3` + vibración corta + TTS. Banner AdMob fijo al pie.

**Movimientos:** 1 línea `Hoy: $ 125.000`; filtros `Desde/Hasta` con `DatePickerDialog` + Filtrar; tabla detalle (tap → modal monto `$ ... ARS`, fecha, pagador, email, payment_id, tipo, aprobada); barra fija al pie con **CSV / Excel** (SAF, columnas fecha,hora,monto,pagador,email,payment_id,tipo,estado).

**Configuración (4 cards):** 1·Vinculación (estado OAuth + Vincular + QR + code manual + Canjear / Desvincular); 2·Apariencia (modo oscuro + paleta 7 colores botones guardada en `BTN_COLOR`); 3·Avisos y servicio (Sonido, TTS es-AR, no apagar pantalla, intervalo 10–60s); 4·Premium (compra única `premium_no_ads`: `Quitar publicidad — Premium` con precio real + `Restaurar compra` + estado); `Guardar e iniciar` con Toast `Configuración guardada ✓`; botones `Ver logs` (dialog con OauthLog 200 filas + Copiar/Limpiar) y `Ver ayuda`.

**Sonido 2do plano:** `PollService.notifyTransfer()` emite `mp.mp3` (`USAGE_NOTIFICATION`, sale por auriculares si están conectados) + TTS es-AR en 1er y 2do plano/bloqueado según switches; `Live()` solo muestra Lottie + vibración (sin duplicar audio).

**Icono:** adaptativo vectorial (`drawable/ic_launcher_foreground.xml`: óvalo celeste `#00A7FB` borde azul `#0277BD` + campanita blanca, fondo `#FFFFFF`), `mipmap-anydpi-v26` + `android:icon/roundIcon` en Manifest.

## 3. Premium (Play Billing, compra única)

- Producto `premium_no_ads`, tipo **inapp no consumible** (crear y activar en Play Console; no es suscripción).
- `PremiumManager` centralizado (`billing-ktx:7.0.0`): conecta Billing, `queryPurchasesAsync(INAPP)` al iniciar, Premium activo solo si compra `PURCHASED`; `PurchasesUpdatedListener` + `acknowledgePurchase`; `launchBuy(activity)` con `ProductDetails`; `restore()` re-consulta; se restaura solo al reinstalar con la misma cuenta (sin `premium=true` local como verdad).
- `AdManager` centralizado: `MobileAds.initialize` y banner solo si `!isPremium`; `Root()` oculta `bottomBar` cuando Premium (sin lógica duplicada en pantallas).
- Testing: producto activo + license testers + track interno release (el `debug` usa `.debug` y Play lo ve como otra app).

## 4. Credenciales MP

App MP `Notif-MP` (crear nueva, no reusar m-POSw):

- `MP_CLIENT_ID=5631264729819538` (público, va en BuildConfig)
- `MP_CLIENT_SECRET` (solo en `~/notif-mp/.env` del VPS, **nunca** en chat/repo/APK — rotar si se expuso)
- `redirect_uri=https://notif.mposw.com.ar/oauth/callback` (idéntico en panel MP, BuildConfig y `ALLOWED_REDIRECT`)
- Panel MP: activar `Authorization Code + PKCE` (challenge S256 obligatorio), scopes `read write offline_access` (refresh 180 días, code 10min/único uso)

## 5. Deploy proxy (VPS)

```bash
cd ~/notif-mp
cp .env.example .env   # completar MP_CLIENT_SECRET y ASSETLINKS_SHA256
docker compose up -d --build
curl https://notif.mposw.com.ar/health                        # {"ok":true}
curl https://notif.mposw.com.ar/.well-known/assetlinks.json  # paquetes + fingerprints
```

Compose: 1 servicio `notif-mp-proxy` en red externa `caddy_net` con labels `caddy: notif.mposw.com.ar` (patrón `~/oauth-mposw`). DNS `A notif.mposw.com.ar → VPS` ya resuelve. Tras release con keystore propio, cargar su SHA256 en `ASSETLINKS_SHA256` (coma-separados) y `ANDROID_PACKAGES` si cambia el package.

## 6. App (Android Studio / CI)

- `minSdk 26, target/compile 34, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7` (pinneado en CI; Gradle 10 rompe AGP 8.5). Deps UI: `lottie-compose 6.4.0`, `foundation` (pager onboarding), `material-icons-extended`.
- `AndroidManifest`: `INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE[_SPECIAL_USE], RECEIVE_BOOT_COMPLETED, VIBRATE, AD_ID`; App Link `https://notif.mposw.com.ar/oauth/callback` + scheme `mpnotify://oauth/callback`; FGS `specialUse`; AdMob `APPLICATION_ID ca-app-pub-9763480712544528~9980738235`, banner `ca-app-pub-9763480712544528/7201783224`.
- Recursos: `res/raw/mp.mp3` (tono aviso), `assets/success.json` (Lottie cobro). Formato moneda es-AR en `Format.kt` (`fmtARS` → `$ 125.000,00`, `fmtFechaHora` → `dd/MM/yyyy HH:mm`).
- CI: `push main (android/**)` → JDK17 + SDK34 → `gradle :app:assembleDebug` → `notif-mp.apk` → release rolling `notif-latest` (`softprops/action-gh-release@v2`). Debug = `ar.com.notifmp.debug` (App Links no autoverifican hasta keystore release).
- Issues CI ya resueltos: `sdkmanager` vía `ANDROID_HOME/cmdline-tools` en PATH; `.gradle.kts` (era DSL Kotlin en `.gradle`); Moshi `KotlinJsonAdapterFactory`; imports `LocalLifecycleOwner` (compose-ui).

## 7. Probar

1. Instalar `notif-mp.apk` de `Releases → notif-latest`.
2. Onboarding 4 slides → Empezar. Config → Vincular → OK en MP → **tocar "Abrir app"** en la página celeste (App Link auto solo con release) → Toast "¡Vinculado! ✓". Fallback: pegar `code=TG-…` en campo manual + Canjear. `Ver logs` muestra cada etapa.
3. Transferir a la cuenta → ~base (o 2s en Turbo) animación central + tono mp.mp3 + TTS + primera en Últimas 10. LED: verde parpadea consultando, rojo pausa todo.
4. Movimientos: `Hoy: $ ...`, filtro calendario, modal, CSV/XLSX (botones fijos).
5. Fondo: bloquear 5min, matar app, reiniciar celu → reanuda si estaba activo (`SERVICE_ON`).

## 8. Troubleshooting

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `sdkmanager: command not found` | no estaba en PATH | ya fixeado: `ANDROID_HOME/cmdline-tools/latest/bin` al PATH |
| `compileSdkVersion is not specified` | cascada de fallo config (Gradle 10 / script no compiló) | Gradle 8.7 pinneado + `.gradle.kts` |
| `Unable to create converter for TokenResp` | Moshi sin adaptador Kotlin | fixeado con `KotlinJsonAdapterFactory` |
| MP OK pero app no vincula | no se tocó "Abrir app" / state / code reuse | Diag: `callback/state/exchange`; manual fallback; proxy `docker logs` (si no hay POST, no salió del celu) |
| `mp_token_failed` en proxy | code usado/expirado, redirect mismatch, PKCE | re-vincular (code único 10min), igualar redirect exacto, revisar método PKCE en panel |
| Sin avisos en fondo | Doze/OEM, optimización batería | pedir ignorar optimización, FGS persistente, probar en 2 marcas |

## 9. Pendientes

- Crear y activar producto `premium_no_ads` (inapp no consumible) en Play Console + license testers.
- Keystore release + AAB firmado Play + SHA256 a `assetlinks.json` (App Link automático).
- Rotar `MP_CLIENT_SECRET` (se pegó en chat).
