package app.deft.ui.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Debug only: shows [AgentMyAppPreview] for a my-app slug; [shown] hides it like the agent panel does. */
class PreviewHostTestActivity : ComponentActivity() {
    var shown by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slug = requireNotNull(intent.getStringExtra(EXTRA_SLUG))
        setContent {
            if (shown) AgentMyAppPreview(slug = slug, modifier = Modifier.fillMaxSize())
        }
    }

    companion object {
        const val EXTRA_SLUG = "slug"
    }
}
