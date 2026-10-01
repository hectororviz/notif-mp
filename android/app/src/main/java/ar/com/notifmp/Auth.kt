package ar.com.notifmp

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

object Pkce {
  fun verifier(): String {
    val b = ByteArray(64); SecureRandom().nextBytes(b)
    return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }
  fun challenge(v: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(v.toByteArray())
    return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }
}

fun authUrl(challenge: String, state: String): String {
  val q = Uri.encode(BuildConfig.REDIRECT_URI)
  return "https://auth.mercadopago.com/authorization?client_id=${BuildConfig.MP_CLIENT_ID}" +
    "&response_type=code&platform_id=mp&state=$state&redirect_uri=$q" +
    "&code_challenge=$challenge&code_challenge_method=S256&scope=${Uri.encode("read write offline_access")}"
}

fun openAuth(ctx: Context, url: String) {
  CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url))
}

data class TokenResp(val access_token: String?, val refresh_token: String?, val expires_in: Long?, val user_id: Long?)
interface ProxyApi {
  @POST("oauth/exchange") suspend fun exchange(@Body b: Map<String, String>): TokenResp
  @POST("oauth/refresh") suspend fun refresh(@Body b: Map<String, String>): TokenResp
}
fun proxy(): ProxyApi {
  val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }
  val http = OkHttpClient.Builder().addInterceptor(log).build()
  return Retrofit.Builder().baseUrl(BuildConfig.PROXY_URL + "/")
    .client(http).addConverterFactory(MoshiConverterFactory.create()).build().create(ProxyApi::class.java)
}
