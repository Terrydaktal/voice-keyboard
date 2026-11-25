package io.github.terrydaktal.whisperkeyboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SetupActivity : Activity() {
    private lateinit var microphoneButton: Button
    private var notificationButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            isFocusableInTouchMode = true
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val padding = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                            WindowInsets.Type.ime(),
                    )
                    view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                    insets
                }
            }
        }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            setText(R.string.app_name)
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            setText(R.string.setup_summary)
            setPadding(0, dp(8), 0, dp(12))
        })

        microphoneButton = addButton(content, R.string.allow_microphone, ::requestMicrophone)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationButton = addButton(content, R.string.allow_notification) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            }
        }
        addButton(content, R.string.enable_keyboard) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        addButton(content, R.string.choose_keyboard) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        val address = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setHint(R.string.server_address)
            setText(WhisperEndpoint.savedAddress(this@SetupActivity))
        }
        content.addView(address)
        addButton(content, R.string.save_address) {
            val endpoint = WhisperEndpoint.parse(address.text.toString())
            if (endpoint == null) {
                address.error = getString(R.string.address_invalid)
            } else {
                WhisperEndpoint.save(this, endpoint)
                address.error = null
                address.setText(endpoint.toExternalForm())
                Toast.makeText(this, R.string.address_saved, Toast.LENGTH_SHORT).show()
            }
        }

        content.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setHint(R.string.test_field)
        })
        content.requestFocus()
        if (intent.getBooleanExtra(REQUEST_MICROPHONE, false) && savedInstanceState == null) {
            requestMicrophone()
        }
    }

    override fun onResume() {
        super.onResume()
        updateMicrophoneButton()
        updateNotificationButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST) {
            updateNotificationButton()
            return
        }
        if (requestCode != MICROPHONE_REQUEST) return
        updateMicrophoneButton()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.microphone_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMicrophone() {
        if (!hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
        }
    }

    private fun updateMicrophoneButton() {
        val allowed = hasMicrophonePermission()
        microphoneButton.setText(if (allowed) R.string.microphone_allowed else R.string.allow_microphone)
        microphoneButton.isEnabled = !allowed
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateNotificationButton() {
        if (Build.VERSION.SDK_INT < 33) return
        val allowed = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        notificationButton?.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun addButton(content: LinearLayout, label: Int, action: () -> Unit): Button =
        Button(this).apply {
            setText(label)
            setOnClickListener { action() }
            content.addView(this)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        internal const val REQUEST_MICROPHONE = "request_microphone"
        private const val MICROPHONE_REQUEST = 1
        private const val NOTIFICATION_REQUEST = 2
    }
}
