package com.classsche.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.classsche.mobile.databinding.ActivityLogViewerBinding

class LogViewerActivity : AppCompatActivity() {
  private lateinit var binding: ActivityLogViewerBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0
  private var allLogs = ""

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityLogViewerBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupToolbar()
    setupActions()
    renderLogs()
  }

  override fun onResume() {
    super.onResume()
    renderLogs()
  }

  private fun setupToolbar() {
    baseToolbarPaddingLeft = binding.toolbar.paddingLeft
    baseToolbarPaddingTop = binding.toolbar.paddingTop
    baseToolbarPaddingRight = binding.toolbar.paddingRight
    baseToolbarPaddingBottom = binding.toolbar.paddingBottom
    binding.toolbar.title = getString(R.string.log_viewer_title)
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
    binding.logFilterInput.doAfterTextChanged {
      renderLogs()
    }
    binding.copyLogsButton.setOnClickListener {
      val logs = filteredLogs()
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("classsche-logs", logs))
      Toast.makeText(this, getString(R.string.log_copy_done), Toast.LENGTH_SHORT).show()
    }
    binding.clearLogsButton.setOnClickListener {
      AppDebugLog.clear(this)
      renderLogs()
      Toast.makeText(this, getString(R.string.log_clear_done), Toast.LENGTH_SHORT).show()
    }
  }

  private fun renderLogs() {
    allLogs = AppDebugLog.read(this).trim()
    val logs = filteredLogs()
    binding.logContentText.text = if (logs.isBlank()) {
      if (allLogs.isBlank()) getString(R.string.log_empty_hint) else getString(R.string.log_filter_empty_hint)
    } else {
      logs
    }
  }

  private fun filteredLogs(): String {
    val keyword = binding.logFilterInput.text?.toString().orEmpty().trim()
    if (keyword.isBlank()) return allLogs
    return allLogs
      .lineSequence()
      .filter { it.contains(keyword, ignoreCase = true) }
      .joinToString("\n")
      .trim()
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
