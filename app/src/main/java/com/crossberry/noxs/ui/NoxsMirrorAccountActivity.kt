package com.crossberry.noxs.ui

import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private object NoxsMirrorSession {
    private const val PREFS = "noxs_mirror_session"
    private const val ALIAS = "noxs_mirror_access_v1"
    private const val BASE = "https://nxmirror.netlify.app/api"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun token(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString("data", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun request(context: Context, route: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL("$BASE/$route").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
                token(context)?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (status !in 200..299 || result.optBoolean("success", true).not()) {
                    throw IllegalStateException(result.optString("error").ifBlank { "Request failed (HTTP $status)" })
                }
                result
            } finally {
                connection.disconnect()
            }
        }
}

class NoxsMirrorAccountActivity : AppCompatActivity() {
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var accountInfo: TextView
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshSession()
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFF10141B.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "NXMirror Account"
            textSize = 25f
            setTextColor(0xFFF3F6FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Sign in to sync your Noxs packages, plugins and Git workspace."
            textSize = 14f
            setTextColor(0xFF9BA8B8.toInt())
            setPadding(0, dp(8), 0, dp(18))
        })
        accountInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8ED6B1.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF1A2525.toInt())
        }
        content.addView(accountInfo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        username = EditText(this).apply {
            hint = "Username"
            singleLine = true
            setTextColor(0xFFF3F6FA.toInt())
            setHintTextColor(0xFF8995A5.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF202833.toInt())
        }
        password = EditText(this).apply {
            hint = "Password"
            singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFF3F6FA.toInt())
            setHintTextColor(0xFF8995A5.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF202833.toInt())
        }
        content.addView(username, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        content.addView(password, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(14) })

        loginButton = button("Login")
        registerButton = button("Create account")
        logoutButton = button("Logout")
        content.addView(loginButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(registerButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(logoutButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(18) })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB9C4D2.toInt())
            setPadding(0, dp(8), 0, dp(14))
        }
        content.addView(status)

        content.addView(TextView(this).apply {
            text = "Cloud library"
            textSize = 18f
            setTextColor(0xFFF3F6FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        })
        val packagesButton = button("My cloud packages")
        val pluginsButton = button("My cloud plugins")
        content.addView(packagesButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
        content.addView(pluginsButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })

        loginButton.setOnClickListener { authenticate("login") }
        registerButton.setOnClickListener { authenticate("register") }
        logoutButton.setOnClickListener {
            NoxsMirrorSession.clear(this)
            status.text = "Logged out on this device."
            refreshSession()
        }
        packagesButton.setOnClickListener { loadCloud("cloud/packages") }
        pluginsButton.setOnClickListener { loadCloud("cloud/plugins") }
        root.addView(scroll)
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
    }

    private fun authenticate(route: String) {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            status.text = "Enter your username and password."
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = NoxsMirrorSession.request(
                    this@NoxsMirrorAccountActivity,
                    "auth/$route",
                    "POST",
                    JSONObject().put("username", user).put("password", pass)
                )
                val token = result.optString("token")
                if (token.isBlank()) throw IllegalStateException("The account API did not return a session token.")
                NoxsMirrorSession.save(this@NoxsMirrorAccountActivity, token)
                status.text = if (route == "register") "Account created and signed in." else "Login successful."
                refreshSession()
            } catch (error: Exception) {
                status.text = error.message ?: "Account request failed."
            } finally {
                setBusy(false)
            }
        }
    }

    private fun refreshSession() {
        val token = NoxsMirrorSession.token(this)
        logoutButton.visibility = if (token.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        loginButton.isEnabled = token.isNullOrBlank()
        registerButton.isEnabled = token.isNullOrBlank()
        if (token.isNullOrBlank()) {
            accountInfo.text = "Not signed in"
            return
        }
        lifecycleScope.launch {
            try {
                val result = NoxsMirrorSession.request(this@NoxsMirrorAccountActivity, "auth/me")
                val user = result.optJSONObject("user")
                accountInfo.text = "Signed in as  @${user?.optString("username") ?: "unknown"}"
            } catch (error: Exception) {
                NoxsMirrorSession.clear(this@NoxsMirrorAccountActivity)
                accountInfo.text = "Session expired — please login again."
                status.text = error.message ?: "Could not verify session."
                refreshSession()
            }
        }
    }

    private fun loadCloud(route: String) {
        if (NoxsMirrorSession.token(this).isNullOrBlank()) {
            status.text = "Login is required to access your cloud library."
            return
        }
        lifecycleScope.launch {
            try {
                val result = NoxsMirrorSession.request(this@NoxsMirrorAccountActivity, route)
                val items = result.optJSONArray("items")
                status.text = buildString {
                    append("Cloud items: ${items?.length() ?: 0}\n")
                    if (items != null) for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        append("• ${item.optString("name")}  ${item.optString("version")}\n")
                        append("  ${item.optString("downloadUrl")}\n")
                    }
                }
            } catch (error: Exception) {
                status.text = error.message ?: "Cloud library could not be loaded."
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        registerButton.isEnabled = !busy
        status.text = if (busy) "Connecting to NXMirror…" else status.text
    }
}
