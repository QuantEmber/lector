package app.lector

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.lector.ui.LectorTheme
import app.lector.ui.ReaderScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only ingest the launch intent on a FRESH start. On a configuration
        // change (rotation) the ViewModel — and its reading position — survive,
        // so re-processing getIntent() here would wrongly reset to sentence 0.
        if (savedInstanceState == null) {
            handleIncomingText(intent)
        }
        setContent {
            LectorTheme {
                ReaderScreen(viewModel)
            }
        }
    }

    // launchMode=singleTask: shares arrive here while the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() current, per the Activity contract
        handleIncomingText(intent)
    }

    /**
     * Accept text from the share sheet (SEND) or the selection toolbar
     * (PROCESS_TEXT). EXTRA_TEXT may be a styled CharSequence, so read it with
     * getCharSequenceExtra — getStringExtra returns null for styled text.
     */
    private fun handleIncomingText(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) {
            viewModel.setPastedText(text)
        }
    }
}
