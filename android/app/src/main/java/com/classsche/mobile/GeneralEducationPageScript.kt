package com.classsche.mobile

/** Read the final DOM in the same WebView; no bridge or second HTTP request. */
internal object GeneralEducationPageScript {
  val READ_CATALOG = """
    (function() {
      // WebView.url can already contain the requested URL while the previous blank DOM is still active.
      if (location.href === 'about:blank') return JSON.stringify({state:'waiting'});
      if (location.origin !== 'https://jwc.njust.edu.cn' || !['/b1/7f/c1189a307583/page.htm','/b1/7f/c1189a307583/pagem.htm'].includes(location.pathname)) {
        return JSON.stringify({state:'redirect'});
      }
      if (document.readyState === 'loading') return JSON.stringify({state:'waiting'});
      var clean = function(value) { return String(value || '').replace(/\s+/g, ' ').trim(); };
      var table = Array.from(document.querySelectorAll('.wp_articlecontent table')).find(function(candidate) {
        var row = candidate.querySelector('tr');
        return row && Array.from(row.cells).map(function(cell) { return clean(cell.textContent); }).join('|') === '课程号|课程名|分类';
      });
      if (!table) {
        if (document.readyState === 'complete' && document.title.includes('通识教育选修课分类汇总')) {
          return JSON.stringify({state:'error',message:'分类表解析错误：未找到课程号、课程名和分类表头，原表已保留'});
        }
        return JSON.stringify({state:'waiting',challenge:!!document.querySelector('script[r="m"]'),complete:document.readyState === 'complete'});
      }
      if (table.rows.length < 2) return JSON.stringify({state:'error',message:'分类表解析错误：网站返回空表，原表已保留'});
      if (!document.title.includes('通识教育选修课分类汇总')) return JSON.stringify({state:'error',message:'分类表解析错误：网页标题不符'});
      var escape = function(value) { return String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); };
      var html = '<html><head><meta charset="utf-8"><title>' + escape(document.title) + '</title></head><body><div class="wp_articlecontent">' + table.outerHTML + '</div></body></html>';
      if (html.length > 4194304) return JSON.stringify({state:'error',message:'分类表解析错误：页面过大，原表已保留'});
      return JSON.stringify({state:'ready',html:html});
    })();
  """.trimIndent()
}
