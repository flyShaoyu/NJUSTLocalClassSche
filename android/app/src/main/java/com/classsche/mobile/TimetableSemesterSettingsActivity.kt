package com.classsche.mobile

import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.classsche.mobile.databinding.ActivityTimetableSemesterSettingsBinding

class TimetableSemesterSettingsActivity : AppCompatActivity() {
  private lateinit var binding: ActivityTimetableSemesterSettingsBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityTimetableSemesterSettingsBinding.inflate(layoutInflater)
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
    binding.toolbar.title = getString(R.string.timetable_semester_settings_title)
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
    binding.selectedSemesterRow.setOnClickListener {
      showSemesterChoiceDialog()
    }
  }

  private fun renderSettings() {
    TimetableSemesterStore.refreshCatalogFromRawHtmlIfNeeded(this)
    val catalog = TimetableSemesterStore.readCatalog(this)
    val explicitSelected = TimetableSemesterStore.readSelectedSemester(this)
    val effectiveSelected = explicitSelected.ifBlank { catalog.currentSemester }

    binding.selectedSemesterSummary.text = if (effectiveSelected.isBlank()) {
      getString(R.string.timetable_semester_follow_summary)
    } else if (explicitSelected.isBlank()) {
      getString(R.string.timetable_semester_follow_summary_with_value, effectiveSelected)
    } else {
      getString(R.string.timetable_semester_selected_summary, effectiveSelected)
    }

    binding.currentSemesterSummary.text = if (catalog.currentSemester.isBlank()) {
      getString(R.string.timetable_semester_unknown_current)
    } else {
      getString(
        R.string.timetable_semester_current_summary,
        catalog.currentSemester,
        catalog.availableSemesters.size
      )
    }

    binding.catalogHintText.text = if (catalog.availableSemesters.isEmpty()) {
      getString(R.string.timetable_semester_no_options)
    } else {
      getString(R.string.timetable_semester_catalog_ready, catalog.availableSemesters.size)
    }
  }

  private fun showSemesterChoiceDialog() {
    TimetableSemesterStore.refreshCatalogFromRawHtmlIfNeeded(this)
    val catalog = TimetableSemesterStore.readCatalog(this)
    val options = buildList {
      add("")
      addAll(catalog.availableSemesters)
    }
    if (options.size == 1) {
      Toast.makeText(this, getString(R.string.timetable_semester_no_options), Toast.LENGTH_SHORT).show()
      return
    }

    val labels = options.map { semester ->
      if (semester.isBlank()) {
        getString(R.string.timetable_semester_follow_current)
      } else {
        semester
      }
    }.toTypedArray()

    val explicitSelected = TimetableSemesterStore.readSelectedSemester(this)
    val checkedIndex = options.indexOf(explicitSelected).let { if (it >= 0) it else 0 }
    AlertDialog.Builder(this)
      .setTitle(R.string.timetable_semester_option_dialog_title)
      .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
        val selected = options.getOrElse(which) { "" }
        val changed = selected != explicitSelected
        if (changed) {
          TimetableSemesterStore.saveSelectedSemester(this, selected)
          TimetableSemesterStore.requestRefresh(this)
          AppDebugLog.append(
            this,
            "TIMETABLE_SEMESTER",
            "INFO",
            if (selected.isBlank()) {
              "已切换为跟随网页当前学期，并请求刷新课表缓存"
            } else {
              "已选择课表学期 $selected，并请求刷新课表缓存"
            }
          )
          setResult(RESULT_OK)
        } else {
          AppDebugLog.append(
            this,
            "TIMETABLE_SEMESTER",
            "INFO",
            "课表学期未变化，跳过刷新请求"
          )
        }
        renderSettings()
        Toast.makeText(
          this,
          getString(
            if (changed) R.string.timetable_semester_saved_and_refreshing
            else R.string.timetable_semester_saved
          ),
          Toast.LENGTH_SHORT
        ).show()
        dialog.dismiss()
        return@setSingleChoiceItems
        TimetableSemesterStore.saveSelectedSemester(this, selected)
        AppDebugLog.append(
          this,
          "TIMETABLE_SEMESTER",
          "INFO",
          if (selected.isBlank()) {
            "已切换为跟随网页当前学期"
          } else {
            "已选择课表学期 $selected"
          }
        )
        renderSettings()
        Toast.makeText(this, getString(R.string.timetable_semester_saved), Toast.LENGTH_SHORT).show()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
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
