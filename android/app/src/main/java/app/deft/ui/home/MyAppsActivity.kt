package app.deft.ui.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.deft.ui.dashboard.DashboardActivity

class MyAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(DashboardActivity.intent(this, DashboardActivity.TAB_WORKSPACES_KEY))
        finish()
    }
}
