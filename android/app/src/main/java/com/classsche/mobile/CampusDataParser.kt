package com.classsche.mobile

import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class CampusRoom(val id: String, val name: String, val capacity: String, val free: Boolean)
data class CampusBook(val id: String, val title: String, val author: String, val publisher: String, val year: String, val callNumber: String, val copies: Int, val available: Int)
data class CampusHolding(val callNumber: String, val location: String, val status: String, val barcode: String)
data class CampusLoan(val title: String, val barcode: String, val borrowedAt: String, val dueAt: String, val location: String)

/** Pure parsers shared by the Android query pages; no requests or cache writes. */
object CampusDataParser {
  private fun text(s: String) = s.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

  fun rooms(document: Document): List<CampusRoom> {
    val table = document.selectFirst("#dataList") ?: error("空闲教室解析错误：结果表缺失")
    val rows = table.select("tr")
    require(rows.size >= 2 && rows[0].text().contains("星期")) { "空闲教室解析错误：星期表头缺失" }
    val columnCount = rows[1].children().size - 1
    require(columnCount > 0 && rows[1].children().drop(1).all { it.attr("tdvalue").matches(Regex("(?:\\d{2})+")) }) { "空闲教室解析错误：节次列格式变化" }
    val data = rows.drop(2).filter { it.hasAttr("jsbh") || it.selectFirst("input[name=jsids]") != null }
    if (data.isEmpty()) {
      require(rows.drop(2).all { it.text().isBlank() || it.text().startsWith("符号说明") || Regex("暂无|没有|未查询到|无数据").containsMatchIn(it.text()) }) { "空闲教室解析错误：未知数据行" }
    }
    return data.map { row ->
      val cells = row.children()
      require(cells.size == columnCount + 1) { "空闲教室解析错误：数据列数变化" }
      val label = text(cells[0].text())
      val capacity = Regex("\\((\\d+)/(\\d+)\\)$").find(label)
      val id = row.attr("jsbh").ifBlank { row.selectFirst("input[name=jsids]")?.attr("value").orEmpty() }
      require(id.isNotBlank() && label.isNotBlank()) { "空闲教室解析错误：缺少教室编号或名称" }
      CampusRoom(id, capacity?.let { label.substring(0, it.range.first) } ?: label, capacity?.groupValues?.get(1).orEmpty(),
        cells.drop(1).all { text(it.text()).isBlank() && it.select("img,svg").isEmpty() && !it.hasAttr("bgcolor") && !it.attr("style").contains("background", true) })
    }
  }

  /** Every chosen interval must be free. An unselected gap is deliberately not queried. */
  fun intersectFreeRooms(results: List<List<CampusRoom>>): List<CampusRoom> {
    if (results.isEmpty()) return emptyList()
    val sets = results.map { list -> list.filter { it.free }.map { it.id }.toSet() }
    return results.first().filter { it.free && sets.all { ids -> it.id in ids } }.distinctBy { it.id }
  }

  fun bookTotal(document: Document): Int {
    val content = document.body().text()
    if (Regex("本馆没有您(?:要)?检索的图书|没有检索到|未检索到").containsMatchIn(content)) return 0
    return Regex("检索到\\s*([\\d,]+)\\s*条").find(content)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
      ?: error("图书搜索解析错误：未找到结果数量")
  }

  fun books(document: Document): List<CampusBook> {
    val total = bookTotal(document)
    val books = document.select("#search_book_list > li").map { item ->
      val link = item.selectFirst("h3 a[href]") ?: error("图书搜索解析错误：书目链接缺失")
      val href = link.attr("href")
      require(href.startsWith("item.php?") || href.startsWith("/uopac/opac/item.php?")) { "图书搜索目标网站跳转错误：非预期书目地址" }
      val encodedId = Regex("[?&]marc_no=([^&]+)").find(href)?.groupValues?.get(1) ?: error("图书搜索解析错误：书目编号缺失")
      val id = URLDecoder.decode(encodedId, "UTF-8")
      val heading = item.selectFirst("h3")!!.clone().apply { select("span,a").remove() }
      val paragraph = item.selectFirst("p") ?: error("图书搜索解析错误：书目信息缺失")
      val summary = paragraph.text()
      val copies = Regex("馆藏复本[：:]\\s*(\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: error("图书搜索解析错误：馆藏复本缺失")
      val available = Regex("可借复本[：:]\\s*(\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: error("图书搜索解析错误：可借复本缺失")
      val info = paragraph.clone().apply { select("span,img,a,div").remove() }
      val lines = info.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)).map { text(Jsoup.parse(it).text()) }.filter { it.isNotBlank() }
      val publication = lines.getOrElse(1) { "" }
      val year = Regex("(?:19|20)\\d{2}(?:[.年/-]\\d{1,2})?").find(publication)?.value.orEmpty()
      CampusBook(id, text(link.text()).replace(Regex("^\\d+\\."), ""), lines.firstOrNull().orEmpty(), publication.replace(year, "").trim(), year,
        text(heading.text()), copies, available)
    }
    require(total == 0 || books.isNotEmpty()) { "图书搜索解析错误：网站报告有结果但列表缺失" }
    return books
  }

  fun holdings(document: Document): List<CampusHolding> {
    val table = document.selectFirst("table#item") ?: error("馆藏解析错误：缺少馆藏明细表")
    require(table.select("tr").first()?.text()?.contains("书刊状态") == true) { "馆藏解析错误：状态列缺失" }
    return table.select("tr").drop(1).map { row ->
      val cells = row.children()
      require(cells.size == 6) { "馆藏解析错误：明细列数变化" }
      CampusHolding(text(cells[0].text()), text(cells[3].text()), text(cells[4].text()), text(cells[1].text()))
    }
  }

  fun readerAuthenticated(document: Document): Boolean = document.select("a[href]").any { it.attr("href").contains("logout") && Regex("注销|退出").containsMatchIn(it.text()) }

  /** The reader home contains four status tiles. Never inspect profile, charts or loan history. */
  fun readerSummary(document: Document): List<Pair<String,String>> {
    require(readerAuthenticated(document)) { "请先完成图书馆独立登录" }
    val panels=document.select(".infobox-container .infobox")
    require(panels.size==4) { "图书馆首页四栏结构已变化，请打开官网查看" }
    return panels.map { panel ->
      val label=text(panel.selectFirst(".infobox-content")?.text().orEmpty())
      val valueNode=panel.selectFirst(".infobox-data-number,.infobox-text") ?: error("图书馆首页状态字段缺失，请打开官网查看")
      val rawValue=text(valueNode.text())
      require(label.length in 1..48 && rawValue.length<=80) { "图书馆首页状态字段缺失，请打开官网查看" }
      label to rawValue.ifBlank { "—" }
    }
  }

  fun loans(document: Document): List<CampusLoan> {
    require(readerAuthenticated(document)) { "图书馆登录状态已失效，请先完成统一认证" }
    require(document.body().text().contains("当前借阅")) { "借阅信息解析错误：当前借阅页面标识缺失" }
    if (document.body().text().contains("您的该项记录为空")) return emptyList()
    val table = document.select("table").firstOrNull { it.select("tr").first()?.text()?.let { header -> header.contains("题名") && header.contains("应还") } == true }
      ?: error("借阅信息解析错误：未找到借阅表，且网站没有说明记录为空")
    val rows = table.select("tr")
    val labels = rows[0].children().map { text(it.text()) }
    fun column(pattern: String) = labels.indexOfFirst { Regex(pattern).containsMatchIn(it) }.also { require(it >= 0) { "借阅信息解析错误：缺少 $pattern 列" } }
    val title = column("题名"); val barcode = column("条码"); val start = column("借阅日期|借书日期"); val due = column("应还"); val location = column("馆藏地|借阅地")
    return rows.drop(1).map { row ->
      val cells = row.children().map { text(it.text()) }
      require(cells.size == labels.size) { "借阅信息解析错误：数据列数变化" }
      CampusLoan(cells[title], cells[barcode], cells[start], cells[due], cells[location])
    }
  }

  fun publicCourses(document: Document): List<TimetableCourse> {
    val table = document.selectFirst("#kbtable") ?: error("课程搜索解析错误：课程课表缺失")
    val rows = table.select("tr")
    require(rows.size >= 2 && rows[0].text().contains("星期")) { "课程搜索解析错误：星期表头缺失" }
    val weekdays = rows[0].children().drop(1).flatMap { cell -> List(cell.attr("colspan").toIntOrNull() ?: 1) { text(cell.text()) } }
    val periods = rows[1].children().drop(1).map { cell ->
      val encoded = cell.text().trim()
      require(encoded.matches(Regex("(?:\\d{2})+"))) { "课程搜索解析错误：节次编码变化" }
      encoded.chunked(2).map { it.toInt() }
    }
    require(weekdays.size == periods.size) { "课程搜索解析错误：星期与节次列数不同" }
    return buildList {
      rows.drop(2).forEach { row ->
        val cells = row.children()
        if (cells.size != weekdays.size + 1) return@forEach
        val name = text(cells[0].text())
        cells.drop(1).forEachIndexed { index, cell ->
          cell.select(".kbcontent1,.kbcontent").forEach { info ->
            val lines = info.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").let { Jsoup.parse(it).wholeText() }.lines().map(::text).filter { it.isNotBlank() }
            val content = lines.joinToString("\n")
            val weeks = Regex("[（(]([^()（）]*周)[）)]").find(content)?.groupValues?.get(1) ?: error("课程搜索解析错误：周次缺失")
            val weekLine = lines.indexOfFirst { it.contains(weeks) }
            val teacher = lines.getOrElse(1) { "" }.substringBefore('(').substringBefore('（').trim()
            val room = lines.drop(weekLine + 1).lastOrNull().orEmpty()
            add(TimetableCourse(name, weekdays[index], "${periods[index].first()}-${periods[index].last()}节", room, weeks, teacher = teacher, courseType = "课程搜索"))
          }
        }
      }
    }.distinctBy { listOf(it.courseName, it.weekday, it.periods, it.weeks, it.classroom, it.teacher) }
  }

  fun teacherCourses(document: Document): List<TimetableCourse> {
    val table = document.selectFirst("#dataList") ?: error("教师课表解析错误：明细表缺失")
    require(table.select("tr").first()?.text()?.contains("开课时间") == true) { "教师课表解析错误：明细表头变化" }
    val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    return table.select("tr").drop(1).flatMap { row ->
      val c = row.children().map { text(it.text()) }
      require(c.size >= 11) { "教师课表解析错误：明细列数不足" }
      val encoded = c[7]
      require(encoded.matches(Regex("[1-7](?:\\d{2})+"))) { "教师课表解析错误：开课时间编码变化" }
      val periods = encoded.drop(1).chunked(2).map { it.toInt() }
      require(periods.all { it in 1..14 }) { "教师课表解析错误：节次不正确" }
      var weeks = c[9].replace("周", "")
      if (c[10].contains("单") || c[10].contains("双")) {
        val parity = if (c[10].contains("单")) 1 else 0
        weeks = Regex("\\d+(?:-\\d+)?").findAll(weeks).flatMap { m -> val n = m.value.split('-').map(String::toInt); (n[0]..n.getOrElse(1) { n[0] }).asSequence() }.filter { it % 2 == parity }.distinct().joinToString(",")
      }
      val ranges = mutableListOf<MutableList<Int>>()
      periods.forEach { value -> if (ranges.isEmpty() || ranges.last().last() + 1 != value) ranges.add(mutableListOf(value)) else ranges.last().add(value) }
      ranges.map { range -> TimetableCourse(c[4], days[encoded.first().digitToInt() - 1], "${range.first()}-${range.last()}节", c[8], weeks + "周", teacher = c[5], courseCode = c[3], courseType = "教师课表") }
    }
  }
}
