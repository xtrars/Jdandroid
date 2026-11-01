package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.data.plainPassword
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Rapidgator API v2 for premium accounts: POST transport, error
 * classification and one session token per account. Every token-bound call
 * goes through [withToken], which logs in again at most once and only when a
 * cached session expired.
 */
internal class RapidgatorApi(private val base: String, private val client: OkHttpClient) {

    private val tokens = ConcurrentHashMap<Long, String>()

    /** Serializes logins per account: parallel downloads should share one session. */
    private val loginLocks = ConcurrentHashMap<Long, Any>()

    /** A login and the "user" object of its response; [user] is null when another thread had just logged in. */
    class Login(val token: String, val user: JSONObject?)

    /**
     * Expired session (401 outside the login): the only error for which a new
     * login makes sense. All other temporary errors (blocks, 5xx) are passed
     * on without re-login so that login counters and the parallel session
     * limit are not strained unnecessarily.
     */
    class TokenExpired(message: String) : HosterException(message, permanent = false)

    /** The login itself failed: an account problem, never a statement about a file. */
    class LoginFailed(message: String, permanent: Boolean) : HosterException(message, permanent)

    /**
     * Runs [block] with the cached token. If that session expired, the token
     * is dropped and [block] runs once more after a new login ([afterLogin]
     * instead, if given); without a cached token it logs in right away. A
     * fresh token that is rejected as well is passed on: logging in again
     * would only loop.
     */
    fun <T> withToken(account: Account, afterLogin: ((Login) -> T)? = null, block: (token: String) -> T): T {
        tokens[account.id]?.let { cached ->
            try {
                return block(cached)
            } catch (_: TokenExpired) {
                // Only this token: another thread may already hold a newer one
                tokens.remove(account.id, cached)
            }
        }
        val login = login(account)
        return if (afterLogin != null) afterLogin(login) else block(login.token)
    }

    /** The "user" object of user/info. */
    fun user(token: String): JSONObject =
        call("user/info", mapOf("token" to token)).optJSONObject("user") ?: JSONObject()

    /** Calls [path] and returns its "response" object; any status other than 200 is thrown as [failure]. */
    fun call(path: String, params: Map<String, String>, loginCall: Boolean = false): JSONObject {
        val json = callRaw(path, params)
        val status = json.optInt("status")
        if (status != 200) throw failure(status, json.optString("details"), loginCall)
        return json.optJSONObject("response") ?: JSONObject()
    }

    /**
     * Classification of an API error. [loginCall]: 401 on login means wrong
     * password/2FA, permanent, so neither engine nor account check run into a
     * login loop. On other calls 401 is an expired token ([TokenExpired]).
     */
    fun failure(status: Int, details: String, loginCall: Boolean): HosterException {
        val text = details.ifBlank { Texts.t("hoster_http_status", status) }
        if (status == 401 && !loginCall) return TokenExpired(Texts.t("hoster_rapidgator_api_error", text))
        // Rapidgator reports daily limit, IP block and parallel limit under 403
        // as well, all temporary. Permanent are only wrong credentials (401 on
        // login), missing file (404) and missing premium (402), unless the
        // text says otherwise.
        val transient = text.contains("traffic", true) ||
            text.contains("limit", true) ||
            text.contains("Denied by IP", true) ||
            text.contains("Session", true) ||
            status in 500..599
        val permanent = !transient && (status in listOf(402, 404) || (loginCall && status == 401))
        return HosterException(Texts.t("hoster_rapidgator_api_error", text), permanent = permanent)
    }

    /** Logs in under the account's lock, unless another thread just did. */
    private fun login(account: Account): Login = synchronized(loginLocks.getOrPut(account.id) { Any() }) {
        tokens[account.id]?.let { return Login(it, null) }
        try {
            val user = account.username ?: throw HosterException(Texts.t("hoster_no_username"), true)
            val pass = account.plainPassword ?: throw HosterException(Texts.t("hoster_no_password"), true)
            val resp = call("user/login", mapOf("login" to user, "password" to pass), loginCall = true)
            val token = resp.optString("token")
            if (token.isBlank()) throw HosterException(Texts.t("hoster_rapidgator_login_failed"), true)
            tokens[account.id] = token
            Login(token, resp.optJSONObject("user") ?: JSONObject())
        } catch (e: HosterException) {
            throw LoginFailed(e.message ?: Texts.t("hoster_rapidgator_login_failed"), e.permanent)
        }
    }

    /** Full JSON response including "status", without evaluation. */
    private fun callRaw(path: String, params: Map<String, String>): JSONObject {
        val text = post("$base/$path", params)
        return runCatching { JSONObject(text) }
            .getOrElse { throw HosterException(Texts.t("hoster_rapidgator_unexpected_response")) }
    }

    /**
     * POST with a form body: credentials and token do not belong in the URL
     * (proxy/server logs). Response read with a limit (Http.MAX_TEXT_BYTES).
     */
    private fun post(url: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        return client.newCall(request).execute().use { resp ->
            resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
    }
}
