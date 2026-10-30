package com.classsche.mobile

import org.jsoup.Jsoup

data class GeneralEducationCourse(val courseCode: String, val courseName: String, val category: String)
data class GeneralEducationCatalog(val title: String, val courses: List<GeneralEducationCourse>)

/** Public source only. Preserve duplicate/conflicting rows and source labels. */
object GeneralEducationCatalogParser {
  const val SOURCE = "https://jwc.njust.edu.cn/b1/7f/c1189a307583/page.htm"
  private fun text(value: String) = value.replace(Regex("\\s+"), " ").trim()

  fun parse(html: String): GeneralEducationCatalog {
    require(html.length <= 4 * 1024 * 1024) { "分类表解析错误：页面过大，原表已保留" }
    val document = Jsoup.parse(html)
    val title = text(document.title())
    require(title.contains("通识教育选修课分类汇总")) { "分类表解析错误：标题不符，可能是校验页或网站错误页" }
    val table = document.select(".wp_articlecontent table").firstOrNull { table ->
      table.selectFirst("tr")?.children()?.map { text(it.text()) } == listOf("课程号", "课程名", "分类")
    } ?: error("分类表解析错误：未找到课程号、课程名和分类表头")
    val rows = table.select("tr").drop(1)
    require(rows.size in 1..10000) { "分类表解析错误：课程表为空或记录数量异常" }
    val courses = rows.mapIndexed { index, row ->
      val cells = row.children().map { text(it.text()) }
      require(cells.size == 3 && cells[0].matches(Regex("[A-Za-z0-9]{8}")) && cells[1].length in 1..300 && cells[2].length in 1..100) {
        "分类表解析错误：第 ${index + 2} 行课程号、名称或分类不完整，原表已保留"
      }
      GeneralEducationCourse(cells[0], cells[1], cells[2])
    }
    return GeneralEducationCatalog(title, courses)
  }
}
