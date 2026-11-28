package app.deft.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.deft.agent.OpenRouterSignIn

/**
 * The no-key state of the agent: "Sign in with OpenRouter" first, pasting an existing key second.
 * [fieldModifier] goes on the key field (the panel attaches its focus requester there).
 */
@Composable
fun AgentSignIn(
    keyDraft: String,
    onKeyDraft: (String) -> Unit,
    onSaveKey: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by OpenRouterSignIn.state.collectAsState()
    val waiting = state == OpenRouterSignIn.State.Waiting
    var paste by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Sign in with OpenRouter to use the agent. The free model works without paying.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { OpenRouterSignIn.start(context) }, enabled = !waiting) {
                Text(if (waiting) "Waiting for OpenRouter…" else "Sign in with OpenRouter")
            }
            if (waiting) TextButton(onClick = { OpenRouterSignIn.cancel() }) { Text("Cancel") }
        }
        (state as? OpenRouterSignIn.State.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!paste) {
            TextButton(onClick = { paste = true }) { Text("Use an existing key") }
        } else {
            Text(
                "Paste an OpenRouter API key. It is stored encrypted on this device, not in config files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = keyDraft,
                onValueChange = onKeyDraft,
                modifier = fieldModifier.fillMaxWidth(),
                label = { Text("OpenRouter API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (keyDraft.isNotBlank()) onSaveKey() }),
            )
            Button(onClick = onSaveKey, enabled = keyDraft.isNotBlank()) { Text("Save key") }
        }
    }
}
