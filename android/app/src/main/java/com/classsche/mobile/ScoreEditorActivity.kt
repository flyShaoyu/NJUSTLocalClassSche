package com.classsche.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.classsche.mobile.databinding.ActivityScoreEditorBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ScoreEditorActivity : AppCompatActivity() {
  private lateinit var binding: ActivityScoreEditorBinding
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0
  private var editingIndex: Int? = null
  private var scores = mutableListOf<JSONObject>()

  companion object {
    private const val SCORE_JSON_FILE = "score-list.json"
    private const val SCORE_UPDATE_META_FILE = "score-update-meta.json"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityScoreEditorBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupToolbar()
    setupActions()
    loadScores()
    resetEditor()
    renderEntries()
  }

  private fun setupToolbar() {
    baseToolbarPaddingLeft = binding.toolbar.paddingLeft
    baseToolbarPaddingTop = binding.toolbar.paddingTop
    baseToolbarPaddingRight = binding.toolbar.paddingRight
    baseToolbarPaddingBottom = binding.toolbar.paddingBottom
    binding.toolbar.title = getString(R.string.score_editor_title)
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
    binding.newEntryButton.setOnClickListener { resetEditor() }
    binding.cancelEditButton.setOnClickListener { resetEditor() }
    binding.saveEntryButton.setOnClickListener { saveCurrentEntry() }
  }

  private fun loadScores() {
    scores = readLatestScoreArray().toMutableList()
  }

  private fun readLatestScoreArray(): List<JSONObject> {
    val runtimeFile = File(filesDir, SCORE_JSON_FILE)
    val raw = when {
      runtimeFile.exists() && runtimeFile.length() > 0L -> runtimeFile.readText(Charsets.UTF_8)
      else -> runCatching {
        assets.open(SCORE_JSON_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
      }.getOrNull().orEmpty()
    }.trim()
    if (raw.isBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(JSONObject(item.toString()))
      }
    }
  }

  private fun saveCurrentEntry() {
    val courseName = binding.courseNameInput.editText?.text?.toString().orEmpty().trim()
    val score = binding.scoreInput.editText?.text?.toString().orEmpty().trim()
    if (courseName.isBlank() || score.isBlank()) {
      Toast.makeText(this, getString(R.string.score_editor_missing_required), Toast.LENGTH_SHORT).show()
      return
    }

    val item = JSONObject().apply {
      put("index", editingIndex?.let { scores.getOrNull(it)?.optInt("index", it + 1) } ?: nextEntryIndex())
      put("semester", binding.semesterInput.editText?.text?.toString().orEmpty().trim())
      put("courseCode", binding.courseCodeInput.editText?.text?.toString().orEmpty().trim())
      put("courseName", courseName)
      put("score", score)
      put("scoreIdentifier", binding.scoreIdentifierInput.editText?.text?.toString().orEmpty().trim())
      put("credits", binding.creditsInput.editText?.text?.toString().orEmpty().trim())
      put("totalHours", binding.totalHoursInput.editText?.text?.toString().orEmpty().trim())
      put("assessmentMethod", binding.assessmentMethodInput.editText?.text?.toString().orEmpty().trim())
      put("courseAttribute", binding.courseAttributeInput.editText?.text?.toString().orEmpty().trim())
      put("courseNature", binding.courseNatureInput.editText?.text?.toString().orEmpty().trim())
      put("isHighlighted", false)
      put("rawText", listOf(courseName, score).joinToString(" ").trim())
    }

    val editing = editingIndex
    if (editing != null && editing in scores.indices) {
      scores[editing] = item
    } else {
      scores.add(0, item)
    }
    persistScores()
    AppDebugLog.append(this, "SCORE_EDITOR", "SUCCESS", "手动保存成绩条目：$courseName $score")
    Toast.makeText(this, getString(R.string.score_editor_saved), Toast.LENGTH_SHORT).show()
    resetEditor()
    renderEntries()
    setResult(Activity.RESULT_OK)
  }

  private fun persistScores() {
    val array = JSONArray()
    scores.forEachIndexed { index, item ->
      val normalized = JSONObject(item.toString())
      normalized.put("index", index + 1)
      array.put(normalized)
    }
    File(filesDir, SCORE_JSON_FILE).writeText(array.toString(), Charsets.UTF_8)
    File(filesDir, SCORE_UPDATE_META_FILE).writeText(
      JSONObject().apply {
        put("pending", false)
        put("items", JSONArray())
      }.toString(),
      Charsets.UTF_8
    )
  }

  private fun nextEntryIndex(): Int =
    (scores.maxOfOrNull { it.optInt("index", 0) } ?: 0) + 1

  private fun resetEditor() {
    editingIndex = null
    listOf(
      binding.semesterInput,
      binding.courseCodeInput,
      binding.courseNameInput,
      binding.scoreInput,
      binding.scoreIdentifierInput,
      binding.creditsInput,
      binding.totalHoursInput,
      binding.assessmentMethodInput,
      binding.courseAttributeInput,
      binding.courseNatureInput
    ).forEach { it.editText?.setText("") }
  }

  private fun beginEdit(index: Int) {
    val item = scores.getOrNull(index) ?: return
    editingIndex = index
    binding.semesterInput.editText?.setText(item.optString("semester"))
    binding.courseCodeInput.editText?.setText(item.optString("courseCode"))
    binding.courseNameInput.editText?.setText(item.optString("courseName"))
    binding.scoreInput.editText?.setText(item.optString("score"))
    binding.scoreIdentifierInput.editText?.setText(item.optString("scoreIdentifier"))
    binding.creditsInput.editText?.setText(item.optString("credits"))
    binding.totalHoursInput.editText?.setText(item.optString("totalHours"))
    binding.assessmentMethodInput.editText?.setText(item.optString("assessmentMethod"))
    binding.courseAttributeInput.editText?.setText(item.optString("courseAttribute"))
    binding.courseNatureInput.editText?.setText(item.optString("courseNature"))
    binding.courseNameInput.requestFocus()
  }

  private fun deleteEntry(index: Int) {
    val item = scores.getOrNull(index) ?: return
    val courseName = item.optString("courseName").ifBlank { "未命名课程" }
    scores.removeAt(index)
    persistScores()
    AppDebugLog.append(this, "SCORE_EDITOR", "SUCCESS", "手动删除成绩条目：$courseName")
    Toast.makeText(this, getString(R.string.score_editor_deleted), Toast.LENGTH_SHORT).show()
    if (editingIndex == index) {
      resetEditor()
    } else if (editingIndex != null && editingIndex!! > index) {
      editingIndex = editingIndex!! - 1
    }
    renderEntries()
    setResult(Activity.RESULT_OK)
  }

  private fun renderEntries() {
    binding.entryList.removeAllViews()
    binding.entryCountText.text = "当前共 ${scores.size} 条本地成绩"
    binding.emptyHintText.visibility = if (scores.isEmpty()) View.VISIBLE else View.GONE
    if (scores.isEmpty()) {
      return
    }

    scores.forEachIndexed { index, item ->
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dpToPx(10), 0, dpToPx(10))
      }

      val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }

      val title = TextView(this).apply {
        text = item.optString("courseName").ifBlank { "未命名课程" }
        textSize = 16f
        setTextColor(Color.parseColor("#4A5E77"))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }

      val editButton = TextView(this).apply {
        text = "编辑"
        textSize = 13f
        setTextColor(Color.parseColor("#5B89BF"))
        setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        setOnClickListener { beginEdit(index) }
      }

      val deleteButton = TextView(this).apply {
        text = "删除"
        textSize = 13f
        setTextColor(Color.parseColor("#C86767"))
        setPadding(dpToPx(8), dpToPx(4), dpToPx(2), dpToPx(4))
        setOnClickListener { deleteEntry(index) }
      }

      top.addView(title)
      top.addView(editButton)
      top.addView(deleteButton)

      val meta = TextView(this).apply {
        text = listOf(
          item.optString("semester"),
          item.optString("score"),
          item.optString("credits").takeIf { it.isNotBlank() }?.let { "${it}学分" }.orEmpty(),
          item.optString("courseNature"),
          item.optString("courseAttribute")
        ).filter { it.isNotBlank() }.joinToString(" · ")
        textSize = 12f
        setTextColor(Color.parseColor("#8FA0B3"))
      }

      row.addView(top)
      row.addView(meta)
      binding.entryList.addView(row)
      if (index < scores.lastIndex) {
        binding.entryList.addView(View(this).apply {
          layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(1)
          )
          setBackgroundColor(Color.parseColor("#22B0BCCB"))
        })
      }
    }
  }

  private fun dpToPx(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).toInt()

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
