package app.fediferry.mastodon

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.fediferry.MainActivity
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Receives `fediferry://oauth?code=…&state=…` from the Custom Tab, finishes the
 * token exchange and drops the user back into the app.
 */
class OAuthRedirectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data

        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state")
        val error = data?.getQueryParameter("error")

        if (code == null || state == null) {
            ServiceLocator.auth(this).cancel()
            toast(error?.let { "Sign-in failed: $it" } ?: "Sign-in was cancelled")
            openApp()
            return
        }

        lifecycleScope.launch {
            val result = ServiceLocator.auth(this@OAuthRedirectActivity)
                .completeAuthorization(code, state)
            toast(
                result.fold(
                    onSuccess = { "Connected as @${it.acct}" },
                    onFailure = { "Sign-in failed: ${it.message}" },
                ),
            )
            openApp()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
