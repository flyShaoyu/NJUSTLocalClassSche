export const COMMON_SITES = [
  { key: "school", title: "学校官网", url: "https://www.njust.edu.cn/", authentication: "public" },
  { key: "academic-news", title: "教务处", url: "https://jwc.njust.edu.cn/", authentication: "public" },
  { key: "teaching", title: "本科教务系统", url: "https://bkjw.njust.edu.cn/njlgdx/indexsso.jsp", authentication: "sso" },
  { key: "portal", title: "智慧理工服务门户", url: "https://ehall2.njust.edu.cn/default/index.html", authentication: "sso" },
  { key: "library", title: "图书馆", url: "https://lib.njust.edu.cn/", authentication: "unknown" }
] as const;
