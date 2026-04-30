package com.classsche.mobile

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.classsche.mobile.databinding.ActivityNotificationSettingsBinding

class NotificationSettingsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityNotificationSettingsBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupToolbar()
    setupPickers()
    setupManagementActions()
    restoreValues()
    renderExactAlarmState()
    renderBatteryOptimizationState()
  }

  override fun onResume() {
    super.onResume()
    renderExactAlarmState()
    renderBatteryOptimizationState()
  }

  private fun setupToolbar() {
    baseToolbarPaddingLeft = binding.toolbar.paddingLeft
    baseToolbarPaddingTop = binding.toolbar.paddingTop
    baseToolbarPaddingRight = binding.toolbar.paddingRight
    baseToolbarPaddingBottom = binding.toolbar.paddingBottom
    binding.toolbar.title = getString(R.string.notification_settings_page_title)
    binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
    binding.toolbar.setNavigationOnClickListener { finish() }
    ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { _, insets ->
      lastStatusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      applyToolbarLayout()
      insets
    }
    applyToolbarLayout()
    updateToolbarNavigationButtonLayout()
  }

  private fun setupPickers() {
    setupPicker(binding.notifyHourPicker, 0, 23)
    setupPicker(binding.notifyMinutePicker, 0, 59)
    val listener = NumberPicker.OnValueChangeListener { _, _, _ ->
      persistLeadTime()
    }
    binding.notifyHourPicker.setOnValueChangedListener(listener)
    binding.notifyMinutePicker.setOnValueChangedListener(listener)
    binding.openExactAlarmButton.setOnClickListener {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
      }
    }
  }

  private fun restoreValues() {
    binding.notifyHourPicker.value = CourseNotificationService.getLeadHours(this)
    binding.notifyMinutePicker.value = CourseNotificationService.getLeadMinutePart(this)
  }

  private fun setupManagementActions() {
    binding.openStartupManagerButton.setOnClickListener {
      openStartupManagementSettings()
    }
    binding.openBatteryOptimizationButton.setOnClickListener {
      openBatteryOptimizationSettings()
    }
  }

  private fun persistLeadTime() {
    CourseNotificationService.saveLeadTime(this, binding.notifyHourPicker.value, binding.notifyMinutePicker.value)
    CourseNotificationScheduler.sync(this)
    binding.currentSummary.text = getString(
      R.string.notification_setting_summary_format,
      binding.notifyHourPicker.value,
      binding.notifyMinutePicker.value
    )
  }

  private fun renderExactAlarmState() {
    binding.currentSummary.text = getString(
      R.string.notification_setting_summary_format,
      CourseNotificationService.getLeadHours(this),
      CourseNotificationService.getLeadMinutePart(this)
    )
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val allowed = CourseNotificationScheduler.canScheduleExactAlarms(this)
    binding.exactAlarmHint.text = when {
      !supported -> getString(R.string.notification_exact_alarm_supported_by_default)
      allowed -> getString(R.string.notification_exact_alarm_enabled)
      else -> getString(R.string.notification_exact_alarm_missing)
    }
    binding.openExactAlarmButton.visibility = if (supported && !allowed) android.view.View.VISIBLE else android.view.View.GONE
  }

  private fun renderBatteryOptimizationState() {
    val powerManager = getSystemService(PowerManager::class.java)
    val ignoringOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      powerManager.isIgnoringBatteryOptimizations(packageName)
    } else {
      true
    }
    binding.batteryOptimizationHint.text = if (ignoringOptimization) {
      getString(R.string.notification_battery_allowed)
    } else {
      getString(R.string.notification_battery_missing)
    }
  }

  private fun openStartupManagementSettings() {
    val intents = listOf(
      Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
      Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")).putExtra("package_name", packageName),
      Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")),
      Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
      Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.AppControlActivity")),
      Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
      Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
      Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
      Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
      Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")),
      Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
      Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
      Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
      Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")),
      Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
    )

    if (!launchFirstSupportedIntent(intents)) {
      openAppDetailsSettings()
      Toast.makeText(this, getString(R.string.notification_settings_fallback_to_app_details), Toast.LENGTH_SHORT).show()
    }
  }

  private fun openBatteryOptimizationSettings() {
    val intents = buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
      }
    }
    if (!launchFirstSupportedIntent(intents)) {
      openAppDetailsSettings()
    }
  }

  private fun openAppDetailsSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
  }

  private fun launchFirstSupportedIntent(intents: List<Intent>): Boolean {
    intents.forEach { intent ->
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (intent.resolveActivity(packageManager) != null) {
        runCatching {
          startActivity(intent)
        }.onSuccess {
          return true
        }
      }
    }
    return false
  }

  private fun setupPicker(picker: NumberPicker, min: Int, max: Int) {
    picker.minValue = min
    picker.maxValue = max
    picker.wrapSelectorWheel = true
    picker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
  }

  private fun applyToolbarLayout() {
    binding.toolbar.minimumHeight = 0
    binding.toolbar.setPadding(
      baseToolbarPaddingLeft,
      baseToolbarPaddingTop + lastStatusBarInsetTop,
      baseToolbarPaddingRight,
      baseToolbarPaddingBottom
    )
  }

  private fun updateToolbarNavigationButtonLayout() {
    binding.toolbar.post {
      val targetSize = dpToPx(44)
      val horizontalMargin = dpToPx(8)
      for (index in 0 until binding.toolbar.childCount) {
        val child = binding.toolbar.getChildAt(index)
        if (child is ImageButton) {
          val params = child.layoutParams
          params.height = targetSize
          params.width = targetSize
          if (params is android.view.ViewGroup.MarginLayoutParams) {
            params.marginStart = horizontalMargin
            params.marginEnd = horizontalMargin
          }
          child.layoutParams = params
          child.minimumHeight = targetSize
          child.minimumWidth = targetSize
          child.setPadding(0, 0, 0, 0)
          child.scaleType = android.widget.ImageView.ScaleType.CENTER
        }
      }
    }
  }

  private fun dpToPx(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).toInt()
}
