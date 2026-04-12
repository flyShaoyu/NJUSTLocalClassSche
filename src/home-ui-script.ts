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

    const currentWeekByAnchor = () => {
      const now = new Date();
      const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      return anchorWeek + Math.floor((dayStart - anchorMonday) / 86400000 / 7);
    };

    const currentDayIndex = () => (new Date().getDay() + 6) % 7;
    const shortWeekday = (weekday) => weekday.replace("鏄熸湡", "鍛?);
    const dateLabel = (date) => (date.getMonth() + 1) + "鏈? + date.getDate() + "鏃?;
    const toMinutes = (time) => {
      const [hourText, minuteText] = time.split(":");
      return Number(hourText) * 60 + Number(minuteText);
    };

    const normalizeCourses = courses.map((course) => {
      const periodMatch = course.periods.match(/(\\d+)(?:-(\\d+))?/);
      const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
      const endPeriod = periodMatch ? Number(periodMatch[2] || periodMatch[1]) : startPeriod;
      const weeks = [];

      for (const token of course.weeks.match(/\\d+(?:-\\d+)?/g) || []) {
        if (token.includes("-")) {
          const [startText, endText] = token.split("-");
          const start = Number(startText);
          const end = Number(endText);
          for (let week = start; week <= end; week += 1) weeks.push(week);
        } else {
          weeks.push(Number(token));
        }
      }

      return { ...course, startPeriod, endPeriod, weeks };
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

      for (let offset = 0; offset < 21 && result.length < 3; offset += 1) {
        const date = new Date(now);
        date.setHours(0, 0, 0, 0);
        date.setDate(date.getDate() + offset);
        const week = anchorWeek + Math.floor((date - anchorMonday) / 86400000 / 7);
        const weekday = weekdays[(date.getDay() + 6) % 7];
        const coursesOfDay = visibleCourses(week)
          .filter((course) => course.weekday === weekday)
          .filter((course) => offset !== 0 || toMinutes((periodSlots[course.endPeriod] || {}).end || "23:59") >= now.getHours() * 60 + now.getMinutes());

        for (const course of coursesOfDay) {
          result.push({
            ...course,
            displayDay: offset === 0 ? "浠婃棩" : (offset === 1 ? "鏄庢棩" : shortWeekday(weekday)),
            displayDate: dateLabel(date)
          });
          if (result.length >= 3) break;
        }
      }

      return result;
    };

    const showToast = (message) => {
      const toast = document.getElementById("toast");
      toast.textContent = message;
      toast.classList.add("show");
      if (state.toastTimer) window.clearTimeout(state.toastTimer);
      state.toastTimer = window.setTimeout(() => toast.classList.remove("show"), 2200);
    };

    const renderMenu = () => {
      const menu = document.getElementById("menuGrid");
      menu.innerHTML = menuItems.map((item) => {
        if (item.key === "schedule") {
          return "<a class=\\"menu-item\\" href=\\"" + timetablePageHref + "\\">" +
            "<span class=\\"menu-icon\\">" + item.icon + "</span>" +
            "<span class=\\"menu-label\\">" + item.label + "</span>" +
          "</a>";
        }

        return "<button type=\\"button\\" class=\\"menu-item disabled\\" data-menu=\\"" + item.key + "\\">" +
          "<span class=\\"menu-icon\\">" + item.icon + "</span>" +
          "<span class=\\"menu-label\\">" + item.label + "</span>" +
        "</button>";
      }).join("");
    };

    const renderHome = () => {
      const list = upcomingCourses();
      document.getElementById("noticeText").textContent = list.length > 0
        ? "鏈湴璇捐〃宸插姞杞斤紝鍙洿鎺ユ煡鐪嬫渶杩戣绋嬩笌瀹屾暣璇捐〃銆?
        : "鏆傛湭鎵惧埌杩戞湡璇剧▼璁板綍锛屽彲浠ュ垏鎹㈠埌瀹屾暣璇捐〃缁х画鏌ョ湅銆?;

      const recent = document.getElementById("recentList");
      const items = list.length > 0
        ? list
        : [{ displayDay: "绌?, courseName: "鏈€杩戞病鏈夊緟涓婄殑璇剧▼", displayDate: "鍙互鐐瑰嚮涓嬫柟鎸夐挳鏌ョ湅瀹屾暣璇捐〃", classroom: "-", startPeriod: null, endPeriod: 1 }];

      recent.innerHTML = items.map((course) =>
        "<div class=\\"recent-item\\">" +
          "<div class=\\"badge\\">" + course.displayDay + "</div>" +
          "<div class=\\"recent-main\\">" +
            "<div class=\\"recent-title\\">" + course.courseName + "</div>" +
            "<div class=\\"recent-meta\\">" +
              (typeof course.startPeriod === "number"
                ? ("绗? + course.startPeriod + "澶ц妭 " + (((periodSlots[course.startPeriod] || {}).start) || "") + "-" + (((periodSlots[course.endPeriod] || {}).end) || "") + " 路 " + course.displayDate)
                : course.displayDate) +
            "</div>" +
          "</div>" +
          "<div class=\\"recent-room\\">" + (course.classroom || "寰呭畾") + "</div>" +
        "</div>"
      ).join("");
    };

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;

      const menuButton = target.closest("[data-menu]");
      if (menuButton instanceof HTMLElement && menuButton.dataset.menu !== "schedule") {
        showToast("璇ュ姛鑳藉叆鍙ｅ凡棰勭暀锛屾殏鏈疄鐜?);
      }

      const actionButton = target.closest("[data-action='menu']");
      if (actionButton instanceof HTMLElement) {
        showToast("鏇村鍔熻兘鍏ュ彛宸查鐣欙紝鍚庣画鍙户缁墿灞?);
      }

    });

    renderMenu();
    renderHome();
  </script>`;

