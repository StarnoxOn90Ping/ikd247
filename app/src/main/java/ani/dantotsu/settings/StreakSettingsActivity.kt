package ani.dantotsu.settings

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityStreakSettingsBinding
import ani.dantotsu.home.streak.StreakManager
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Settings > Streak. Everything here is read by the Home header the next time Home is shown,
 * so there is nothing to restart.
 */
class StreakSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStreakSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityStreakSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.streakSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.streakSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // ---- look ---------------------------------------------------------------------------
        bindSwitch(binding.streakShow, PrefName.StreakShow)
        bindSwitch(binding.streakAnimated, PrefName.StreakFireAnimated)
        bindSwitch(binding.streakButtonFire, PrefName.StreakButtonFire)
        bindSwitch(binding.streakNumberFlames, PrefName.StreakNumberFlames)
        bindSwitch(binding.streakSparks, PrefName.StreakSparks)
        bindSwitch(binding.streakLines, PrefName.StreakLines)
        bindSwitch(binding.streakHaptics, PrefName.StreakHaptics)

        bindSlider(
            binding.streakIntensity, step = 10,
            initial = (PrefManager.getVal<Float>(PrefName.StreakIntensity) * 100f).roundToInt()
        ) { value ->
            PrefManager.setVal(PrefName.StreakIntensity, value / 100f)
            binding.streakIntensityLabel.text = getString(R.string.streak_intensity_label, value)
        }

        // ---- rules --------------------------------------------------------------------------
        bindSwitch(binding.streakGrace, PrefName.StreakGraceDay)

        bindSlider(
            binding.streakDayStart, step = 1,
            initial = PrefManager.getVal<Int>(PrefName.StreakDayStart)
        ) { value ->
            PrefManager.setVal(PrefName.StreakDayStart, value)
            binding.streakDayStartLabel.text = getString(R.string.streak_day_start_label, value)
        }

        bindSlider(
            binding.streakThreshold, step = 5,
            initial = PrefManager.getVal<Int>(PrefName.StreakThreshold)
        ) { value ->
            PrefManager.setVal(PrefName.StreakThreshold, value)
            binding.streakThresholdLabel.text = getString(R.string.streak_threshold_label, value)
        }

        binding.streakReset.setOnClickListener {
            customAlertDialog().apply {
                setTitle(getString(R.string.streak_reset_title))
                setMessage(getString(R.string.streak_reset_message))
                setPosButton(R.string.ok) {
                    StreakManager.reset()
                    toast(getString(R.string.streak_reset_done))
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        // ---- stages (just the text) -----------------------------------------------------------
        binding.streakStages.text = getString(R.string.streak_stages_text)

        // ---- debug --------------------------------------------------------------------------
        setupDebug()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun bindSwitch(view: CompoundButton, pref: PrefName) {
        view.isChecked = PrefManager.getVal(pref)
        view.setOnCheckedChangeListener { _, isChecked -> PrefManager.setVal(pref, isChecked) }
    }

    /**
     * A slider with whole steps. Sliders crash on a value that is not on a step, so the stored
     * value is snapped and clamped first. [onValue] is also called once for the starting value so
     * the label is right.
     */
    private fun bindSlider(slider: Slider, step: Int, initial: Int, onValue: (Int) -> Unit) {
        val from = slider.valueFrom.roundToInt()
        val to = slider.valueTo.roundToInt()
        val snapped = (((initial - from).toFloat() / step).roundToInt() * step + from).coerceIn(from, to)
        slider.value = snapped.toFloat()
        onValue(snapped)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) onValue(value.roundToInt())
        }
    }

    // ---- debug tools -------------------------------------------------------------------------

    private fun setupDebug() {
        val on = PrefManager.getVal<Boolean>(PrefName.StreakDebug)
        binding.streakDebug.isChecked = on
        binding.streakDebugGroup.isVisible = on

        binding.streakDebug.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // start from the real streak so there is a sensible number to edit
                // (snapshot() still shows the real one here: debug is not on yet)
                PrefManager.setVal(PrefName.StreakDebugCount, StreakManager.snapshot().count)
                PrefManager.setVal(PrefName.StreakDebugStatus, 0)
            }
            PrefManager.setVal(PrefName.StreakDebug, isChecked)
            binding.streakDebugGroup.isVisible = isChecked
            refreshDebugTexts()
        }

        // quick jumps: one button for the start of every stage
        val d = resources.displayMetrics.density
        binding.streakDebugQuick.removeAllViews()
        StreakManager.STAGE_STARTS.forEach { n ->
            val button = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = n.toString()
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f).apply {
                    marginStart = (3 * d).toInt()
                    marginEnd = (3 * d).toInt()
                }
                setOnClickListener {
                    PrefManager.setVal(PrefName.StreakDebugCount, n)
                    refreshDebugTexts()
                }
            }
            binding.streakDebugQuick.addView(button)
        }

        binding.streakDebugEdit.setOnClickListener {
            val pad = (24 * d).toInt()
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(PrefManager.getVal<Int>(PrefName.StreakDebugCount).toString())
                setSelection(text.length)
                selectAll()
            }
            val frame = FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            customAlertDialog().apply {
                setTitle(getString(R.string.streak_debug_edit_title))
                setCustomView(frame)
                setPosButton(R.string.ok) {
                    val n = input.text.toString().toIntOrNull()?.coerceIn(0, 9999) ?: 0
                    PrefManager.setVal(PrefName.StreakDebugCount, n)
                    refreshDebugTexts()
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        binding.streakDebugStatus.setOnClickListener {
            val names = resources.getStringArray(R.array.streak_debug_statuses)
            customAlertDialog().apply {
                setTitle(getString(R.string.streak_debug_status_title))
                singleChoiceItems(names, PrefManager.getVal<Int>(PrefName.StreakDebugStatus)) { index ->
                    PrefManager.setVal(PrefName.StreakDebugStatus, index)
                    refreshDebugTexts()
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        refreshDebugTexts()
    }

    private fun refreshDebugTexts() {
        val count = PrefManager.getVal<Int>(PrefName.StreakDebugCount)
        val names = resources.getStringArray(R.array.streak_debug_statuses)
        val status = names.getOrElse(PrefManager.getVal<Int>(PrefName.StreakDebugStatus)) { names[0] }
        binding.streakDebugEdit.text = getString(R.string.streak_debug_edit, count)
        binding.streakDebugStatus.text = getString(R.string.streak_debug_status, status)
    }
}
