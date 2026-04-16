interface HomeScriptParams {
  coursesJson: string;
  imagesJson: string;
  weekdaysJson: string;
  periodSlotsJson: string;
  menuItemsJson: string;
  timetablePageHrefJson: string;
  anchorWeek: number;
  anchorMondayJson: string;
}

export const buildHomePageScript = (params: HomeScriptParams): string => `
  <script>
    let courses = ${params.coursesJson};
    const images = ${params.imagesJson};
    const weekdays = ${params.weekdaysJson};
    const periodSlots = ${params.periodSlotsJson};
    const menuItems = ${params.menuItemsJson};
    const timetablePageHref = ${params.timetablePageHrefJson};
    const anchorWeek = ${params.anchorWeek};
    const anchorMonday = new Date(${params.anchorMondayJson});

    const state = {
      toastTimer: null,
      galleryIndex: 0,
      touchStartX: null,
      galleryLoadedCount: 1,
      lightboxOpen: false,
      lightboxHistoryPushed: false,
      lightboxScale: 1,
      lightboxPanX: 0,
      lightboxPanY: 0,
      pinchStartDistance: null,
      pinchStartScale: 1,
      dragStartX: null,
      dragStartY: null,
      dragOriginX: 0,
      dragOriginY: 0,
      lightboxSwipeStartX: null
    };
    const weekTitles = ["\\u5468\\u4e00", "\\u5468\\u4e8c", "\\u5468\\u4e09", "\\u5468\\u56db", "\\u5468\\u4e94", "\\u5468\\u516d", "\\u5468\\u65e5"];

    const escapeHtml = (value) =>
      String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");

    const currentDayIndex = () => (new Date().getDay() + 6) % 7;
    const shortWeekday = (weekday) => weekday.replace("\\u661f\\u671f", "\\u5468");
    const dateLabel = (date) => date.getMonth() + 1 + "\\u6708" + date.getDate() + "\\u65e5";

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

    const normalizedCourses = () =>
      courses.map((course) => {
        const periodMatch = String(course.periods).match(/(\\d+)(?:-(\\d+))?/);
        const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
        const endPeriod = periodMatch ? Number(periodMatch[2] || periodMatch[1]) : startPeriod;

        return { ...course, startPeriod, endPeriod, weeks: parseWeeks(course.weeks) };
      });

    const allScheduledCourses = (courseList) =>
      courseList
        .flatMap((course) => (course.weeks.length > 0 ? course.weeks : [anchorWeek]).map((week) => ({ ...course, week })))
        .sort((left, right) => {
          const weekDiff = left.week - right.week;
          if (weekDiff !== 0) return weekDiff;
          const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
          return dayDiff !== 0 ? dayDiff : left.startPeriod - right.startPeriod;
        });

    const visibleCourses = (courseList, week) =>
      courseList
        .filter((course) => course.weeks.length === 0 || course.weeks.includes(week))
        .sort((left, right) => {
          const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
          return dayDiff !== 0 ? dayDiff : left.startPeriod - right.startPeriod;
        });

    const upcomingCourses = () => {
      const now = new Date();
      const result = [];
      const courseList = normalizedCourses();
      const wantedOffsets = new Set([0, 1]);
      const nowMinutes = now.getHours() * 60 + now.getMinutes();

      for (let offset = 0; offset < 21; offset += 1) {
        const date = new Date(now);
        date.setHours(0, 0, 0, 0);
        date.setDate(date.getDate() + offset);

        const week = anchorWeek + Math.floor((date - anchorMonday) / 86400000 / 7);
        const weekday = weekdays[(date.getDay() + 6) % 7];
        const coursesOfDay = visibleCourses(courseList, week)
          .filter((course) => course.weekday === weekday)
          .filter((course) => offset !== 0 || toMinutes(periodSlots[course.endPeriod]?.end || "23:59") >= now.getHours() * 60 + now.getMinutes());

        if (wantedOffsets.has(offset)) {
          for (const course of coursesOfDay) {
            const startMinutes = toMinutes(periodSlots[course.startPeriod]?.start || "00:00");
            const endMinutes = toMinutes(periodSlots[course.endPeriod]?.end || "23:59");
            const isAlert =
              offset === 0 &&
              (
                (nowMinutes >= startMinutes && nowMinutes <= endMinutes) ||
                (startMinutes >= nowMinutes && startMinutes - nowMinutes <= 15)
              );
            result.push({
              ...course,
              displayDay: offset === 0 ? "\\u4eca\\u65e5" : "\\u660e\\u65e5",
              displayDate: dateLabel(date),
              isToday: offset === 0,
              isAlert
            });
          }

          if (offset === 1) {
            break;
          }
        } else if (offset > 1 && result.length > 0) {
          break;
        }
      }

      if (result.length > 0) {
        return result;
      }

      const fallback = allScheduledCourses(courseList).slice(0, 6).map((course) => {
        const weekOffset = course.week - anchorWeek;
        const date = new Date(anchorMonday);
        date.setDate(anchorMonday.getDate() + weekOffset * 7 + weekdays.indexOf(course.weekday));

        return {
          ...course,
          displayDay: shortWeekday(course.weekday),
          displayDate: "\\u7b2c" + course.week + "\\u5468 \\u00b7 " + dateLabel(date),
          isToday: false,
          isAlert: false
        };
      });

      if (fallback.length > 0) {
        return fallback;
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
      if (title) title.textContent = weekTitles[currentDayIndex()] + "\\u8bfe\\u8868";
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

    const renderGallery = () => {
      const track = document.getElementById("featureTrack");
      const caption = document.getElementById("featureCaption");
      const dots = document.querySelector(".feature-dots");
      const prev = document.getElementById("featurePrev");
      const next = document.getElementById("featureNext");
      if (!(track instanceof HTMLElement) || !(caption instanceof HTMLElement) || !(dots instanceof HTMLElement)) return;

      if (!images.length) {
        if (prev instanceof HTMLButtonElement) prev.hidden = true;
        if (next instanceof HTMLButtonElement) next.hidden = true;
        dots.innerHTML = "";
        return;
      }

      state.galleryIndex = ((state.galleryIndex % images.length) + images.length) % images.length;
      state.galleryLoadedCount = Math.max(state.galleryLoadedCount || 1, state.galleryIndex + 1);
      track.style.transform = "translateX(-" + state.galleryIndex * 100 + "%)";
      track.innerHTML = images.map((image, index) =>
        '<div class="feature-slide" data-gallery-index="' + index + '">' +
          '<img src="' + (index < state.galleryLoadedCount ? image.src : "") + '" data-src="' + image.src + '" alt="' + escapeHtml(image.caption) + '" loading="lazy" decoding="async" />' +
        "</div>"
      ).join("");

      caption.textContent = images[state.galleryIndex].caption;
      dots.innerHTML = images.map((_, index) =>
        '<span class="dot' + (index === state.galleryIndex ? " active" : "") + '"></span>'
      ).join("");

      const multiple = images.length > 1;
      if (prev instanceof HTMLButtonElement) prev.hidden = !multiple;
      if (next instanceof HTMLButtonElement) next.hidden = !multiple;
    };

    const openLightbox = (index) => {
      const lightbox = document.getElementById("lightbox");
      const image = document.getElementById("lightboxImage");
      const caption = document.getElementById("lightboxCaption");
      const prev = document.getElementById("lightboxPrev");
      const next = document.getElementById("lightboxNext");
      if (!(lightbox instanceof HTMLElement) || !(image instanceof HTMLImageElement) || !(caption instanceof HTMLElement)) return;
      state.galleryIndex = ((index % images.length) + images.length) % images.length;
      const current = images[state.galleryIndex];
      if (!current) return;
      image.src = current.src;
      image.alt = current.caption;
      caption.textContent = current.caption;
      [state.galleryIndex - 1, state.galleryIndex + 1].forEach((neighborIndex) => {
        const normalizedIndex = ((neighborIndex % images.length) + images.length) % images.length;
        const preloadImage = new Image();
        preloadImage.src = images[normalizedIndex].src;
      });
      if (prev instanceof HTMLButtonElement) prev.hidden = images.length < 2;
      if (next instanceof HTMLButtonElement) next.hidden = images.length < 2;
      state.lightboxOpen = true;
      state.lightboxScale = 1;
      state.lightboxPanX = 0;
      state.lightboxPanY = 0;
      state.pinchStartDistance = null;
      state.pinchStartScale = 1;
      state.dragStartX = null;
      state.dragStartY = null;
      state.lightboxSwipeStartX = null;
      updateLightboxTransform();
      lightbox.classList.add("show");
      lightbox.setAttribute("aria-hidden", "false");

      if (!state.lightboxHistoryPushed) {
        history.pushState({ lightbox: true }, "", location.href);
        state.lightboxHistoryPushed = true;
      }
    };

    const closeLightbox = (fromHistory = false) => {
      const lightbox = document.getElementById("lightbox");
      if (!(lightbox instanceof HTMLElement)) return;
      state.lightboxOpen = false;
      lightbox.classList.remove("show");
      lightbox.setAttribute("aria-hidden", "true");
      state.lightboxScale = 1;
      state.lightboxPanX = 0;
      state.lightboxPanY = 0;
      state.pinchStartDistance = null;
      state.pinchStartScale = 1;
      state.dragStartX = null;
      state.dragStartY = null;
      state.lightboxSwipeStartX = null;
      updateLightboxTransform();

      if (state.lightboxHistoryPushed) {
        state.lightboxHistoryPushed = false;
        if (!fromHistory && history.length > 1) {
          history.back();
        }
      }
    };

    const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
    const touchDistance = (first, second) => Math.hypot(second.clientX - first.clientX, second.clientY - first.clientY);
    const updateLightboxTransform = () => {
      const image = document.getElementById("lightboxImage");
      if (!(image instanceof HTMLImageElement)) return;
      image.style.transform =
        "translate(" + state.lightboxPanX + "px, " + state.lightboxPanY + "px) scale(" + state.lightboxScale + ")";
    };
    const updateLightboxScale = (scale) => {
      state.lightboxScale = clamp(scale, 1, 4);
      if (state.lightboxScale <= 1) {
        state.lightboxPanX = 0;
        state.lightboxPanY = 0;
      }
      updateLightboxTransform();
    };

    const renderHome = () => {
      const list = upcomingCourses();
      const noticeText = document.getElementById("noticeText");
      const recent = document.getElementById("recentList");

      if (noticeText) {
        noticeText.textContent = list.length > 0
          ? "\\u6700\\u8fd1\\u8bfe\\u8868\\u5df2\\u6309\\u672c\\u5730\\u7f13\\u5b58\\u66f4\\u65b0"
          : "\\u6700\\u8fd1\\u6ca1\\u6709\\u5f85\\u4e0a\\u7684\\u8bfe\\u7a0b\\uff0c\\u53ef\\u4ee5\\u70b9\\u51fb\\u4e0b\\u65b9\\u6309\\u94ae\\u67e5\\u770b\\u5b8c\\u6574\\u8bfe\\u8868";
      }

      if (!(recent instanceof HTMLElement)) return;

      const items = list.length > 0
        ? list
        : [{
            displayDay: "\\u7a7a",
            courseName: "\\u6700\\u8fd1\\u6ca1\\u6709\\u5f85\\u4e0a\\u7684\\u8bfe\\u7a0b",
            displayDate: "\\u53ef\\u4ee5\\u70b9\\u51fb\\u4e0b\\u65b9\\u6309\\u94ae\\u67e5\\u770b\\u5b8c\\u6574\\u8bfe\\u8868",
            classroom: "\\u5f85\\u5b9a",
            isToday: false,
            isAlert: false,
            startPeriod: null,
            endPeriod: 1
          }];

      recent.innerHTML = items.map((course) => {
        const timeText = typeof course.startPeriod === "number"
          ? "\\u7b2c" + course.startPeriod + "\\u5927\\u8282 " + (periodSlots[course.startPeriod]?.start || "") + "-" + (periodSlots[course.endPeriod]?.end || "")
          : course.displayDate;

        return (
          '<div class="recent-item">' +
            '<div class="' + (course.isAlert ? "badge alert" : (course.isToday ? "badge today" : "badge")) + '">' + escapeHtml(course.displayDay) + "</div>" +
            '<div class="recent-main">' +
              '<div class="recent-title">' + escapeHtml(course.courseName) + "</div>" +
              '<div class="recent-meta">' + escapeHtml(timeText) + "</div>" +
            "</div>" +
            '<div class="recent-room">' + escapeHtml(course.classroom || "\\u5f85\\u5b9a") + "</div>" +
          "</div>"
        );
      }).join("");
    };

    window.ClassScheHome = {
      updateRecentCourses(nextCourses) {
        try {
          courses = Array.isArray(nextCourses) ? nextCourses : JSON.parse(String(nextCourses || "[]"));
          renderHome();
          return true;
        } catch (_) {
          return false;
        }
      }
    };

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;

      if (target.closest("#featurePrev")) {
        state.galleryIndex -= 1;
        renderGallery();
        return;
      }

      if (target.closest("#featureNext")) {
        state.galleryIndex += 1;
        renderGallery();
        return;
      }

      if (target.closest("#lightboxPrev")) {
        openLightbox(state.galleryIndex - 1);
        return;
      }

      if (target.closest("#lightboxNext")) {
        openLightbox(state.galleryIndex + 1);
        return;
      }

      const featureSlide = target.closest("[data-gallery-index]");
      if (featureSlide instanceof HTMLElement) {
        openLightbox(Number(featureSlide.dataset.galleryIndex || "0"));
        return;
      }

      if (target.closest("#lightboxClose") || target.id === "lightbox") {
        closeLightbox();
        return;
      }

      if (target.closest("[data-menu]")) {
        showToast("\\u8be5\\u529f\\u80fd\\u5165\\u53e3\\u5df2\\u9884\\u7559\\uff0c\\u6682\\u672a\\u5b9e\\u73b0");
      }

      if (target.closest("[data-action='menu']")) {
        showToast("\\u66f4\\u591a\\u529f\\u80fd\\u5165\\u53e3\\u5df2\\u9884\\u7559\\uff0c\\u540e\\u7eed\\u53ef\\u4ee5\\u7ee7\\u7eed\\u8865\\u5145");
      }
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        closeLightbox();
      }
    });

    window.addEventListener("popstate", () => {
      if (state.lightboxOpen) {
        closeLightbox(true);
      }
    });

    const track = document.getElementById("featureTrack");
    if (track instanceof HTMLElement) {
      track.addEventListener("touchstart", (event) => {
        state.touchStartX = event.touches[0]?.clientX ?? null;
      }, { passive: true });

      track.addEventListener("touchend", (event) => {
        const endX = event.changedTouches[0]?.clientX ?? null;
        if (state.touchStartX == null || endX == null) return;
        const delta = endX - state.touchStartX;
        state.touchStartX = null;
        if (Math.abs(delta) < 28 || images.length < 2) return;
        state.galleryIndex += delta < 0 ? 1 : -1;
        state.galleryLoadedCount = Math.max(state.galleryLoadedCount || 1, state.galleryIndex + 2);
        renderGallery();
      });
    }

    const lightboxImage = document.getElementById("lightboxImage");
    if (lightboxImage instanceof HTMLImageElement) {
      lightboxImage.addEventListener("touchstart", (event) => {
        if (event.touches.length === 2) {
          state.pinchStartDistance = touchDistance(event.touches[0], event.touches[1]);
          state.pinchStartScale = state.lightboxScale;
          state.dragStartX = null;
          state.dragStartY = null;
          return;
        }

        if (event.touches.length === 1) {
          const touch = event.touches[0];
          if (state.lightboxScale > 1) {
            state.dragStartX = touch.clientX;
            state.dragStartY = touch.clientY;
            state.dragOriginX = state.lightboxPanX;
            state.dragOriginY = state.lightboxPanY;
          } else {
            state.lightboxSwipeStartX = touch.clientX;
          }
        }
      }, { passive: true });

      lightboxImage.addEventListener("touchmove", (event) => {
        if (event.touches.length === 2 && state.pinchStartDistance != null) {
          event.preventDefault();
          const distance = touchDistance(event.touches[0], event.touches[1]);
          if (!distance) return;
          updateLightboxScale(state.pinchStartScale * (distance / state.pinchStartDistance));
          return;
        }

        if (event.touches.length === 1 && state.lightboxScale > 1 && state.dragStartX != null && state.dragStartY != null) {
          event.preventDefault();
          const touch = event.touches[0];
          state.lightboxPanX = state.dragOriginX + (touch.clientX - state.dragStartX);
          state.lightboxPanY = state.dragOriginY + (touch.clientY - state.dragStartY);
          updateLightboxTransform();
        }
      }, { passive: false });

      lightboxImage.addEventListener("touchend", (event) => {
        if (event.touches.length < 2) {
          state.pinchStartDistance = null;
          state.pinchStartScale = state.lightboxScale;
        }

        if (event.touches.length === 0) {
          if (state.lightboxScale <= 1 && state.lightboxSwipeStartX != null && images.length > 1) {
            const endX = event.changedTouches[0]?.clientX ?? null;
            if (endX != null) {
              const delta = endX - state.lightboxSwipeStartX;
              if (Math.abs(delta) >= 36) {
                openLightbox(state.galleryIndex + (delta < 0 ? 1 : -1));
              }
            }
          }
          state.dragStartX = null;
          state.dragStartY = null;
          state.lightboxSwipeStartX = null;
        }
      });

      lightboxImage.addEventListener("touchcancel", () => {
        state.pinchStartDistance = null;
        state.pinchStartScale = state.lightboxScale;
        state.dragStartX = null;
        state.dragStartY = null;
        state.lightboxSwipeStartX = null;
      });
    }

    renderHeader();
    renderMenu();
    renderGallery();
    renderHome();
  </script>`;

