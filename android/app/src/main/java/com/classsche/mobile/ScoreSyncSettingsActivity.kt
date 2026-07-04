package com.classsche.mobile

import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.classsche.mobile.databinding.ActivityScoreSyncSettingsBinding

class ScoreSyncSettingsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityScoreSyncSettingsBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityScoreSyncSettingsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupToolbar()
    setupActions()
    renderSettings()
  }

  override fun onResume() {
    super.onResume()
    renderSettings()
  }

  private fun setupToolbar() {
    baseToolbarPaddingLeft = binding.toolbar.paddingLeft
    baseToolbarPaddingTop = binding.toolbar.paddingTop
    baseToolbarPaddingRight = binding.toolbar.paddingRight
    baseToolbarPaddingBottom = binding.toolbar.paddingBottom
    binding.toolbar.title = getString(R.string.score_sync_settings_title)
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

  private fun setupActions() {
    binding.scoreSyncModeRow.setOnClickListener {
      showScoreSyncModeDialog()
    }
    binding.scoreSyncIntervalRow.setOnClickListener {
      showScoreSyncIntervalDialog()
    }
  }

  private fun renderSettings() {
    val settings = ScoreSyncSettings.read(this)
    binding.scoreSyncModeSummary.text = buildString {
      append("当前：").append(formatModeLabel(settings.mode))
      if (settings.mode == ScoreSyncSettings.Mode.WIFI_ONLY) {
        append("（")
        append(if (ScoreSyncSettings.isOnWifi(this@ScoreSyncSettingsActivity)) "当前已连接 Wi-Fi" else "当前非 Wi-Fi")
        append("）")
      }
    }
    binding.scoreSyncIntervalSummary.text = "当前：每 ${formatIntervalLabel(settings.intervalHours)} 自动检查一次"
    binding.scoreSyncIntervalRow.alpha = if (settings.mode == ScoreSyncSettings.Mode.DISABLED) 0.45f else 1f
  }

  private fun showScoreSyncModeDialog() {
    val settings = ScoreSyncSettings.read(this)
    val options = arrayOf("是", "否", "仅 Wi-Fi")
    val modes = arrayOf(
      ScoreSyncSettings.Mode.ENABLED,
      ScoreSyncSettings.Mode.DISABLED,
      ScoreSyncSettings.Mode.WIFI_ONLY
    )
    val checkedIndex = modes.indexOf(settings.mode).coerceAtLeast(0)
    AlertDialog.Builder(this)
      .setTitle("后台成绩拉取")
      .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
        val selectedMode = modes.getOrElse(which) { ScoreSyncSettings.Mode.ENABLED }
        if (selectedMode != settings.mode) {
          ScoreSyncSettings.saveMode(this, selectedMode)
          renderSettings()
          HeadlessScoreSyncScheduler.onSettingsChanged(this)
          AppDebugLog.append(
            this,
            "HEADLESS_SCORE_SCHEDULE",
            "INFO",
            "用户修改后台成绩拉取模式为 ${selectedMode.storageValue}"
          )
        }
        dialog.dismiss()
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun showScoreSyncIntervalDialog() {
    val settings = ScoreSyncSettings.read(this)
    val hoursOptions = ScoreSyncSettings.intervalOptionsHours()
    val labels = hoursOptions.map(::formatIntervalLabel).toTypedArray()
    val checkedIndex = hoursOptions.indexOf(settings.intervalHours).let { if (it >= 0) it else 1 }
    AlertDialog.Builder(this)
      .setTitle("后台拉取频率")
      .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
        val selectedHours = hoursOptions.getOrElse(which) { 2 }
        if (selectedHours != settings.intervalHours) {
          ScoreSyncSettings.saveIntervalHours(this, selectedHours)
          renderSettings()
          HeadlessScoreSyncScheduler.onSettingsChanged(this)
          AppDebugLog.append(
            this,
            "HEADLESS_SCORE_SCHEDULE",
            "INFO",
            "用户修改后台成绩拉取频率为 ${selectedHours}h"
          )
        }
        dialog.dismiss()
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun formatModeLabel(mode: ScoreSyncSettings.Mode): String {
    return when (mode) {
      ScoreSyncSettings.Mode.ENABLED -> "是"
      ScoreSyncSettings.Mode.DISABLED -> "否"
      ScoreSyncSettings.Mode.WIFI_ONLY -> "仅 Wi-Fi"
    }
  }

  private fun formatIntervalLabel(hours: Int): String = "${hours}h"

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
