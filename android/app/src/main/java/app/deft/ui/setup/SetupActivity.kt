package app.deft.ui.setup

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.deft.ui.theme.AppDeftTheme

/**
 * First-run device setup. Personal substitute for the public M8 wizard:
 * default-home hint, then the permission note. `scripts/debloat.sh` still
 * covers the one-time ADB cleanup.
 */
class SetupActivity : ComponentActivity() {
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppDeftTheme {
                var step by rememberSaveable { mutableIntStateOf(0) }
                var level by rememberSaveable { mutableIntStateOf(1) }
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Set up Deft", style = MaterialTheme.typography.headlineSmall)
                    when (step) {
                        0 -> {
                            Text("Level 1 only changes the home app. Levels 2 and 3 are optional and do not run yet.")
                            Button(onClick = { level = 1; step = 1 }) { Text("Level 1 · Launcher only") }
                            Button(onClick = { level = 2; step = 1 }) { Text("Level 2 · Privacy-hardened") }
                            Button(onClick = { level = 3; step = 1 }) { Text("Level 3 · No Google") }
                        }
                        1 -> {
                            Text(
                                if (level == 1) {
                                    "Make Deft the home app. This does not need wireless debugging."
                                } else {
                                    "Level $level stays behind this choice. Package changes use an allow-listed client and a package deny-list. Nothing is removed in this step."
                                },
                            )
                            Button(onClick = { requestRole(RoleManager.ROLE_HOME) }) { Text("Set as home app") }
                            if (level == 1) {
                                Button(onClick = { requestRole(RoleManager.ROLE_SMS) }) { Text("SMS app") }
                                Button(onClick = { requestRole(RoleManager.ROLE_DIALER) }) { Text("Phone app") }
                                // PARITY G11: no Browser button; a web app becomes the browser from its dashboard card.
                            }
                            Button(onClick = { step = 2 }) { Text("Continue") }
                        }
                        else -> {
                            Text("SMS, phone, contacts, and files ask the first time an app needs them. You can change grants later in the dashboard.")
                            Button(onClick = {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
                                finish()
                            }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    private fun requestRole(role: String) {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(role)) {
            if (role == RoleManager.ROLE_HOME) {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            return
        }
        if (roles.isRoleHeld(role)) return
        roleRequest.launch(roles.createRequestRoleIntent(role))
    }

    companion object {
        const val PREFS = "setup"
        const val DONE = "done"

        fun needed(activity: ComponentActivity): Boolean =
            !activity.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(DONE, false)
    }
}
