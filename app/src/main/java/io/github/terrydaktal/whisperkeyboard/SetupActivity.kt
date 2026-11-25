package io.github.terrydaktal.whisperkeyboard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SetupActivity : Activity() {
    private data class ReturnKeyboardOption(val imeId: String?, val label: String) {
        override fun toString(): String = label
    }

    private lateinit var microphoneButton: Button
    private var notificationButton: Button? = null
    private lateinit var statusView: TextView
    private lateinit var grantCommandView: TextView
    private lateinit var returnKeyboardSpinner: Spinner
    private lateinit var backendHeading: TextView
    private lateinit var backendGroup: RadioGroup
    private lateinit var clearAddressButton: Button
    private var ramblerRadioId = 0
    private var whisperRadioId = 0
    private var returnOptions: List<ReturnKeyboardOption> = emptyList()
    private var updatingReturnSpinner = false

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

        statusView = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(8))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        content.addView(statusView)

        grantCommandView = TextView(this).apply {
            setText(R.string.grant_command)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(grantCommandView)

        microphoneButton = addButton(content, R.string.allow_microphone, ::requestMicrophone)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationButton = addButton(content, R.string.allow_notification) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            }
        }
        addButton(content, R.string.enable_keyboard) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        addButton(content, R.string.accessibility_settings) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(content, R.string.choose_keyboard) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        content.addView(TextView(this).apply {
            setText(R.string.return_keyboard_heading)
            textSize = 18f
            setPadding(0, dp(12), 0, dp(4))
        })
        returnKeyboardSpinner = Spinner(this).apply {
            minimumHeight = dp(48)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (updatingReturnSpinner) return
                    val chosen = returnOptions.getOrNull(position) ?: return
                    KeyboardLayoutStore.setPreferredReturnIme(this@SetupActivity, chosen.imeId)
                    updateBridgeStatus()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        content.addView(returnKeyboardSpinner)

        backendHeading = TextView(this).apply {
            setText(R.string.backend_heading)
            textSize = 18f
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(backendHeading)

        ramblerRadioId = View.generateViewId()
        whisperRadioId = View.generateViewId()
        backendGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@SetupActivity).apply {
                id = ramblerRadioId
                setText(R.string.backend_rambler_setup)
                minHeight = dp(48)
            })
            addView(RadioButton(this@SetupActivity).apply {
                id = whisperRadioId
                setText(R.string.backend_whisper_setup)
                minHeight = dp(48)
            })
        }
        content.addView(backendGroup)

        val localeField = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setHint(R.string.rambler_locale_hint)
            setText(DictationSettings.locale(this@SetupActivity))
        }
        content.addView(localeField)
        addButton(content, R.string.save_locale) {
            val saved = DictationSettings.setLocale(this, localeField.text.toString())
            localeField.setText(saved)
            Toast.makeText(this, getString(R.string.locale_saved, saved), Toast.LENGTH_SHORT).show()
        }

        val address = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setHint(R.string.server_address)
            setText(WhisperEndpoint.savedAddress(this@SetupActivity))
        }
        content.addView(address)

        val urlActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        urlActions.addView(
            Button(this).apply {
                setText(R.string.save_address)
                setOnClickListener {
                    val raw = address.text.toString().trim()
                    if (raw.isEmpty()) {
                        WhisperEndpoint.clear(this@SetupActivity)
                        DictationSettings.setBackend(this@SetupActivity, DictationBackend.RAMBLER)
                        address.error = null
                        updateBackendControls()
                        Toast.makeText(this@SetupActivity, R.string.address_cleared, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val endpoint = WhisperEndpoint.parse(raw)
                    if (endpoint == null) {
                        address.error = getString(R.string.address_invalid)
                    } else {
                        WhisperEndpoint.save(this@SetupActivity, endpoint)
                        address.error = null
                        address.setText(endpoint.toExternalForm())
                        updateBackendControls()
                        Toast.makeText(this@SetupActivity, R.string.address_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        clearAddressButton = Button(this).apply {
            setText(R.string.clear_address)
            setOnClickListener {
                WhisperEndpoint.clear(this@SetupActivity)
                DictationSettings.setBackend(this@SetupActivity, DictationBackend.RAMBLER)
                address.setText("")
                address.error = null
                updateBackendControls()
                Toast.makeText(this@SetupActivity, R.string.address_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        urlActions.addView(
            clearAddressButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        content.addView(urlActions)

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
        updateReturnKeyboardSpinner()
        updateBridgeStatus()
        updateBackendControls()
    }

    override fun onPause() {
        backendGroup.setOnCheckedChangeListener(null)
        super.onPause()
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
        updateBridgeStatus()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.microphone_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateReturnKeyboardSpinner() {
        val keyboards = KeyboardLayoutStore.availableReturnKeyboards(this)
        val options = buildList {
            add(ReturnKeyboardOption(null, getString(R.string.return_keyboard_auto)))
            for (ime in keyboards) {
                add(ReturnKeyboardOption(ime.id, KeyboardLayoutStore.keyboardLabel(this@SetupActivity, ime)))
            }
        }
        returnOptions = options
        val savedId = KeyboardLayoutStore.preferredReturnIme(this)
        val selectedIndex = options.indexOfFirst { it.imeId == savedId }.coerceAtLeast(0)

        updatingReturnSpinner = true
        try {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            returnKeyboardSpinner.adapter = adapter
            returnKeyboardSpinner.setSelection(selectedIndex, false)
        } finally {
            updatingReturnSpinner = false
        }
    }

    private fun updateBridgeStatus() {
        val accessibilityEnabled = getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwiftKeyBridgeService::class.java.name }
        val enabled = KeyboardSwitch.enabled(this)
        val voiceKeyboardEnabled = enabled.any { it.packageName == packageName }
        val returnLabel = KeyboardLayoutStore.returnKeyboardLabel(this)
        val canWrite = KeyboardSwitch.canWrite(this)
        val status = listOf(
            getString(if (hasMicrophonePermission()) R.string.microphone_allowed else R.string.voice_keyboard_microphone_needed),
            getString(if (voiceKeyboardEnabled) R.string.voice_keyboard_ready else R.string.enable_voice_keyboard),
            if (returnLabel != null) {
                getString(R.string.return_keyboard_status, returnLabel)
            } else {
                getString(R.string.return_keyboard_none)
            },
            getString(if (accessibilityEnabled) R.string.accessibility_ready else R.string.enable_accessibility),
            getString(if (canWrite) R.string.permission_ready else R.string.grant_permission),
        )
        statusView.text = status.joinToString("\n")
        grantCommandView.visibility = if (canWrite) View.GONE else View.VISIBLE
    }

    private fun updateBackendControls() {
        val whisperConfigured = WhisperEndpoint.isConfigured(this)
        val visibility = if (whisperConfigured) View.VISIBLE else View.GONE
        backendHeading.visibility = visibility
        backendGroup.visibility = visibility
        clearAddressButton.visibility = visibility

        backendGroup.setOnCheckedChangeListener(null)
        if (!whisperConfigured) return
        backendGroup.check(
            when (DictationSettings.backend(this)) {
                DictationBackend.RAMBLER -> ramblerRadioId
                DictationBackend.WHISPER -> whisperRadioId
            },
        )
        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                ramblerRadioId -> DictationBackend.RAMBLER
                whisperRadioId -> DictationBackend.WHISPER
                else -> return@setOnCheckedChangeListener
            }
            DictationSettings.setBackend(this, selected)
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
