package nox.kiwami.settings

import nox.kiwami.databinding.ItemSettingsBinding
import nox.kiwami.databinding.ItemSettingsSwitchBinding

data class Settings(
    val type: Int,
    val name: String,
    val desc: String,
    val icon: Int,
    val onClick: ((ItemSettingsBinding) -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val switch: ((isChecked: Boolean, view: ItemSettingsSwitchBinding) -> Unit)? = null,
    val attach: ((ItemSettingsBinding) -> Unit)? = null,
    val attachToSwitch: ((ItemSettingsSwitchBinding) -> Unit)? = null,
    var isVisible: Boolean = true,
    val isActivity: Boolean = false,
    var isChecked: Boolean = false,
)
