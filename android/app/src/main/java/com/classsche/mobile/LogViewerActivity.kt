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
import com.classsche.mobile.databinding.ActivityLogViewerBinding

class LogViewerActivity : AppCompatActivity() {
  private lateinit var binding: ActivityLogViewerBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

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
    binding.copyLogsButton.setOnClickListener {
      val logs = AppDebugLog.read(this)
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
    val logs = AppDebugLog.read(this).trim()
    binding.logContentText.text = if (logs.isBlank()) {
      getString(R.string.log_empty_hint)
    } else {
      logs
    }
  }

  private fun applyToolbarLayout() {
    val minHeight = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      48f + (lastStatusBarInsetTop / resources.displayMetrics.density),
      resources.displayMetrics
    ).toInt()
    binding.toolbar.minimumHeight = minHeight
    binding.toolbar.setPadding(
      baseToolbarPaddingLeft,
      baseToolbarPaddingTop + lastStatusBarInsetTop,
      baseToolbarPaddingRight,
      baseToolbarPaddingBottom
    )
  }

  private fun updateToolbarNavigationButtonLayout() {
    binding.toolbar.post {
      val buttonSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics).toInt()
      val horizontalPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
      for (index in 0 until binding.toolbar.childCount) {
        val child = binding.toolbar.getChildAt(index)
        if (child is ImageButton) {
          child.layoutParams = child.layoutParams.apply {
            width = buttonSize
            height = buttonSize
          }
          child.setPadding(horizontalPadding, horizontalPadding, horizontalPadding, horizontalPadding)
          child.requestLayout()
        }
      }
    }
  }
}
