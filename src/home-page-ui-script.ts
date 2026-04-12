interface HomeScriptParams {
  coursesJson: string;
  weekdaysJson: string;
  periodSlotsJson: string;
  menuItemsJson: string;
  timetablePageHrefJson: string;
  anchorWeek: number;
  anchorMondayJson: string;
}

export const buildHomePageScript = (params: HomeScriptParams): string => `
  <script>
    const courses = ${params.coursesJson};
    const weekdays = ${params.weekdaysJson};
    const periodSlots = ${params.periodSlotsJson};
    const menuItems = ${params.menuItemsJson};
    const timetablePageHref = ${params.timetablePageHrefJson};
    const anchorWeek = ${params.anchorWeek};
    const anchorMonday = new Date(${params.anchorMondayJson});

    const state = { toastTimer: null };
    const weekTitles = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

    const escapeHtml = (value) =>
      String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");

    const currentDayIndex = () => (new Date().getDay() + 6) % 7;
    const shortWeekday = (weekday) => weekday.replace("星期", "周");
    const dateLabel = (date) => date.getMonth() + 1 + "月" + date.getDate() + "日";

    const toMinutes = (time) => {
      const [hourText, minuteText] = String(time || "00:00").split(":");
      return Number(hourText) * 60 + Number(minuteText);
    };

    const parseWeeks = (weeksText) => {
      const weeks = [];

      for (const token of String(weeksText).match(/\\d+(?:-\\d+)?/g) || []) {
        if (token.includes("-")) {
          const [startText, endText] = token.split("-");
          const start = Number(startText);
          const end = Number(endText);
          for (let week = start; week <= end; week += 1) weeks.push(week);
        } else {
          weeks.push(Number(token));
        }
      }

      return weeks;
    };

    const normalizeCourses = courses.map((course) => {
      const periodMatch = String(course.periods).match(/(\\d+)(?:-(\\d+))?/);
      const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
      const endPeriod = periodMatch ? Number(periodMatch[2] || periodMatch[1]) : startPeriod;

      return { ...course, startPeriod, endPeriod, weeks: parseWeeks(course.weeks) };
    });

    const visibleCourses = (week) =>
      normalizeCourses
        .filter((course) => course.weeks.length === 0 || course.weeks.includes(week))
        .sort((left, right) => {
          const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
          return dayDiff !== 0 ? dayDiff : left.startPeriod - right.startPeriod;
        });

    const upcomingCourses = () => {
      const now = new Date();
      const result = [];

      for (let offset = 0; offset < 21 && result.length < 2; offset += 1) {
        const date = new Date(now);
        date.setHours(0, 0, 0, 0);
        date.setDate(date.getDate() + offset);

        const week = anchorWeek + Math.floor((date - anchorMonday) / 86400000 / 7);
        const weekday = weekdays[(date.getDay() + 6) % 7];
        const coursesOfDay = visibleCourses(week)
          .filter((course) => course.weekday === weekday)
          .filter((course) => offset !== 0 || toMinutes(periodSlots[course.endPeriod]?.end || "23:59") >= now.getHours() * 60 + now.getMinutes());

        for (const course of coursesOfDay) {
          result.push({
            ...course,
            displayDay: offset === 0 ? "今日" : (offset === 1 ? "明日" : shortWeekday(weekday)),
            displayDate: dateLabel(date)
          });

          if (result.length >= 2) break;
        }
      }

      return result;
    };

    const showToast = (message) => {
      const toast = document.getElementById("toast");
      if (!(toast instanceof HTMLElement)) return;

      toast.textContent = message;
      toast.classList.add("show");

      if (state.toastTimer) window.clearTimeout(state.toastTimer);
      state.toastTimer = window.setTimeout(() => toast.classList.remove("show"), 2200);
    };

    const menuIcon = (key) => {
      const icons = {
        exam: '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="14" y="8" width="28" height="40" rx="3"></rect><path d="M24 20h20"></path><path d="M24 30h16"></path><circle cx="45" cy="44" r="11"></circle></svg>',
        score: '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="12" y="30" width="8" height="18" rx="2"></rect><rect x="28" y="18" width="8" height="30" rx="2"></rect><rect x="44" y="24" width="8" height="24" rx="2"></rect><path d="M10 54h44"></path></svg>',
        level: '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="16" y="10" width="32" height="44" rx="3"></rect><path d="M24 20h14"></path><path d="M24 28h10"></path><path d="M30 38l6-10 8 18"></path></svg>',
        add: '<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M18 18h28"></path><path d="M18 32h28"></path><path d="M18 46h28"></path><circle cx="12" cy="18" r="2"></circle><circle cx="12" cy="32" r="2"></circle><circle cx="12" cy="46" r="2"></circle></svg>',
        schedule: '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="10" y="16" width="44" height="34" rx="6"></rect><path d="M10 26h44"></path><path d="M20 10v12"></path><path d="M44 10v12"></path><path d="M18 34h8"></path><path d="M30 34h8"></path><path d="M42 34h4"></path></svg>',
        room: '<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M10 24l22-12 22 12"></path><path d="M14 28h36"></path><path d="M18 28v18"></path><path d="M28 28v18"></path><path d="M38 28v18"></path><path d="M48 28v18"></path><path d="M12 50h40"></path></svg>',
        site: '<svg viewBox="0 0 64 64" aria-hidden="true"><circle cx="32" cy="32" r="19"></circle><path d="M14 32h36"></path><path d="M32 13c6 6 9 12 9 19s-3 13-9 19"></path><path d="M32 13c-6 6-9 12-9 19s3 13 9 19"></path></svg>',
        refresh: '<svg viewBox="0 0 64 64" aria-hidden="true"><path d="M45 21a17 17 0 1 0 4 17"></path><path d="M46 11v14H32"></path></svg>',
        library: '<svg viewBox="0 0 64 64" aria-hidden="true"><circle cx="28" cy="28" r="14"></circle><path d="M39 39l11 11"></path><path d="M22 28c0-3 3-6 6-6"></path></svg>',
        borrow: '<svg viewBox="0 0 64 64" aria-hidden="true"><rect x="16" y="10" width="30" height="44" rx="3"></rect><path d="M24 20h14"></path><path d="M24 30h14"></path><path d="M24 40h10"></path></svg>'
      };

      return icons[key] || icons.schedule;
    };

    const renderHeader = () => {
      const title = document.getElementById("homeTitle");
      if (title) title.textContent = weekTitles[currentDayIndex()] + "课表";
    };

    const renderMenu = () => {
      const menu = document.getElementById("menuGrid");
      if (!(menu instanceof HTMLElement)) return;

      menu.innerHTML = menuItems.map((item) => {
        const classes = "menu-item" + (item.enabled ? "" : " disabled");
        const inner =
          '<span class="menu-icon">' + menuIcon(item.key) + "</span>" +
          '<span class="menu-label">' + escapeHtml(item.label) + "</span>";

        return item.key === "schedule"
          ? '<a class="' + classes + '" href="' + timetablePageHref + '">' + inner + "</a>"
          : '<button type="button" class="' + classes + '" data-menu="' + escapeHtml(item.key) + '">' + inner + "</button>";
      }).join("");
    };

    const renderHome = () => {
      const list = upcomingCourses();
      const noticeText = document.getElementById("noticeText");
      const recent = document.getElementById("recentList");

      if (noticeText) {
        noticeText.textContent = list.length > 0
          ? "服务器出错加载数据失败，请稍后下拉刷新，由于安全限定"
          : "暂无近期课程记录，可以点击下方按钮查看完整课表";
      }

      if (!(recent instanceof HTMLElement)) return;

      const items = list.length > 0
        ? list
        : [{
            displayDay: "空",
            courseName: "最近没有待上的课程",
            displayDate: "可以点击下方按钮查看完整课表",
            classroom: "待定",
            startPeriod: null,
            endPeriod: 1
          }];

      recent.innerHTML = items.map((course) => {
        const timeText = typeof course.startPeriod === "number"
          ? "第" + course.startPeriod + "大节 " + (periodSlots[course.startPeriod]?.start || "") + "-" + (periodSlots[course.endPeriod]?.end || "")
          : course.displayDate;

        return (
          '<div class="recent-item">' +
            '<div class="badge">' + escapeHtml(course.displayDay) + "</div>" +
            '<div class="recent-main">' +
              '<div class="recent-title">' + escapeHtml(course.courseName) + "</div>" +
              '<div class="recent-meta">' + escapeHtml(timeText) + "</div>" +
            "</div>" +
            '<div class="recent-room">' + escapeHtml(course.classroom || "待定") + "</div>" +
          "</div>"
        );
      }).join("");
    };

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;

      if (target.closest("[data-menu]")) {
        showToast("该功能入口已预留，暂未实现");
      }

      if (target.closest("[data-action='menu']")) {
        showToast("更多功能入口已预留，后续可以继续补充");
      }
    });

    renderHeader();
    renderMenu();
    renderHome();
  </script>`;
