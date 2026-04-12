interface TimetableScriptParams {
  coursesJson: string;
  weekdaysJson: string;
  periodSlotsJson: string;
  paletteJson: string;
  anchorWeek: number;
  anchorMondayJson: string;
  initialWeek: number;
}

export const buildTimetablePageScript = (params: TimetableScriptParams): string => `
  <script>
const courses = ${params.coursesJson};
    const weekdays = ${params.weekdaysJson};
    const periodSlots = ${params.periodSlotsJson};
    const palette = ${params.paletteJson};
    const anchorWeek = ${params.anchorWeek};
    const anchorMonday = new Date(${params.anchorMondayJson} + 'T00:00:00');

    const normalizeCourses = courses.map((course) => {
      const periodMatch = course.periods.match(/(\\d+)-(\\d+)/);
      const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
      const endPeriod = periodMatch ? Number(periodMatch[2]) : startPeriod;
      const weeks = [];

      for (const token of course.weeks.match(/\\d+(?:-\\d+)?/g) || []) {
        if (token.includes('-')) {
          const [startText, endText] = token.split('-');
          const start = Number(startText);
          const end = Number(endText);
          for (let week = start; week <= end; week += 1) weeks.push(week);
        } else {
          weeks.push(Number(token));
        }
      }

      return {
        ...course,
        startPeriod,
        endPeriod,
        weeks,
        weeksText: course.weeks,
        paletteKey: course.courseName,
        courseKind: course.courseType || '未分类'
      };
    });

    const allWeeks = [...new Set(normalizeCourses.flatMap((course) => course.weeks))].sort((a, b) => a - b);
    const colorMap = new Map();
    let paletteIndex = 0;

    for (const course of normalizeCourses) {
      if (!colorMap.has(course.paletteKey)) {
        colorMap.set(course.paletteKey, palette[paletteIndex % palette.length]);
        paletteIndex += 1;
      }
    }

    const headerRow = document.getElementById('headerRow');
    const board = document.querySelector('.board');
    const boardHighlightLayer = document.getElementById('boardHighlightLayer');
    const gridScroll = document.querySelector('.grid-scroll');
    const schedule = document.getElementById('schedule');
    const legend = document.getElementById('legend');
    const emptyState = document.getElementById('emptyState');
    const hero = document.querySelector('.hero');
    const footerBar = document.querySelector('.footer-bar');
    const footerDay = document.getElementById('footerDay');
    const weekLabel = document.getElementById('weekLabel');
    const sheetOverlay = document.getElementById('sheetOverlay');
    const focusedCardLayer = document.getElementById('focusedCardLayer');
    const detailSheet = document.getElementById('detailSheet');
    const sheetTopbarLeft = document.getElementById('sheetTopbarLeft');
    const sheetWeekDay = document.getElementById('sheetWeekDay');
    const sheetPeriodLine = document.getElementById('sheetPeriodLine');
    const sheetTitle = document.getElementById('sheetTitle');
    const sheetTeacher = document.getElementById('sheetTeacher');
    const sheetRoom = document.getElementById('sheetRoom');
    const sheetWeeks = document.getElementById('sheetWeeks');
    const sheetCode = document.getElementById('sheetCode');
    const sheetWatermark = document.getElementById('sheetWatermark');
    const sheetTag = document.getElementById('sheetTag');
    const sheetClose = document.getElementById('sheetClose');

    const formatDateLabel = (date) => (date.getMonth() + 1) + '月' + date.getDate();

    const getWeekDates = (week) => {
      const weekOffset = (week - anchorWeek) * 7;
      const monday = new Date(anchorMonday);
      monday.setDate(anchorMonday.getDate() + weekOffset);
      return weekdays.map((_, index) => {
        const date = new Date(monday);
        date.setDate(monday.getDate() + index);
        return date;
      });
    };

    const getCurrentWeekByAnchor = () => {
      const now = new Date();
      const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const diffDays = Math.floor((dayStart - anchorMonday) / (1000 * 60 * 60 * 24));
      return anchorWeek + Math.floor(diffDays / 7);
    };

    const getCurrentWeekdayLabel = () => {
      const now = new Date();
      const weekdayIndex = (now.getDay() + 6) % 7;
      return weekdays[weekdayIndex] || '星期一';
    };

    const getInitialStateWeek = () => {
      const currentWeek = getCurrentWeekByAnchor();
      if (allWeeks.includes(currentWeek)) {
        return currentWeek;
      }

      const futureWeek = allWeeks.find((week) => week >= currentWeek);
      if (futureWeek !== undefined) {
        return futureWeek;
      }

      return allWeeks[allWeeks.length - 1] || ${params.initialWeek};
    };

    const state = {
      week: getInitialStateWeek(),
      activeCourse: null
    };

    const touchState = {
      startX: 0,
      startY: 0
    };

    let focusedCardClone = null;

    const removeFocusedCardClone = () => {
      if (focusedCardClone) {
        focusedCardClone.remove();
        focusedCardClone = null;
      }
    };

    const showFocusedCardClone = (card) => {
      removeFocusedCardClone();
      const rect = card.getBoundingClientRect();
      const clone = card.cloneNode(true);
      clone.classList.remove('active');
      clone.classList.add('focused-card-clone');
      clone.style.left = rect.left + 'px';
      clone.style.top = rect.top + 'px';
      clone.style.width = rect.width + 'px';
      clone.style.height = rect.height + 'px';
      clone.style.transform = 'translateY(-3px) scale(1.03)';
      clone.style.boxShadow = '0 20px 34px rgba(64, 92, 138, 0.34)';
      focusedCardLayer.appendChild(clone);
      focusedCardClone = clone;
    };

    const closeSheet = () => {
      detailSheet.classList.remove('show');
      sheetOverlay.classList.remove('show');
      detailSheet.setAttribute('aria-hidden', 'true');
      document.body.classList.remove('sheet-open');
      schedule.querySelectorAll('.course-card.active').forEach((card) => card.classList.remove('active'));
      removeFocusedCardClone();
    };

    const toMinutes = (time) => {
      const [hourText, minuteText] = time.split(':');
      return Number(hourText) * 60 + Number(minuteText);
    };

    const getCourseTimeRange = (course) => {
      const start = periodSlots[course.startPeriod]?.start || '';
      const end = periodSlots[course.endPeriod]?.end || '';
      return start + '-' + end;
    };

    const openSheet = (course, card) => {
      state.activeCourse = course;
      schedule.querySelectorAll('.course-card.active').forEach((node) => node.classList.remove('active'));
      card.classList.add('active');
      showFocusedCardClone(card);
      sheetWeekDay.textContent = state.week + '周' + course.weekday.replace('星期', '周');
      sheetPeriodLine.textContent = course.periods.replace('小节', '节 ') + getCourseTimeRange(course);
      sheetTitle.textContent = course.courseName;
      sheetTeacher.textContent = course.teacher || '未标注教师';
      sheetRoom.textContent = course.classroom || '待定地点';
      sheetWeeks.textContent = course.weeksText || '全学期';
      sheetCode.textContent = course.courseCode || '-';
      sheetWatermark.textContent = String(course.startPeriod) + String(course.endPeriod);
      sheetTag.textContent = course.courseKind;
      detailSheet.classList.add('show');
      sheetOverlay.classList.add('show');
      detailSheet.setAttribute('aria-hidden', 'false');
      document.body.classList.add('sheet-open');
    };

    const getCurrentDayIndex = () => {
      if (state.week !== getCurrentWeekByAnchor()) return -1;
      const now = new Date();
      const labels = getWeekDates(state.week).map(formatDateLabel);
      return labels.indexOf(formatDateLabel(now));
    };

    const renderNowLine = () => {
      const dayIndex = getCurrentDayIndex();
      if (dayIndex === -1) return;

      const now = new Date();
      const minutesNow = now.getHours() * 60 + now.getMinutes();
      const timelineEntries = Object.entries(periodSlots)
        .map(([period, slot]) => {
          return {
            period: Number(period),
            startMinutes: toMinutes(slot.start),
            endMinutes: toMinutes(slot.end)
          };
        })
        .sort((left, right) => left.startMinutes - right.startMinutes);

      const firstMinutes = timelineEntries[0]?.startMinutes;
      const lastMinutes = timelineEntries[timelineEntries.length - 1]?.endMinutes;
      if (firstMinutes === undefined || lastMinutes === undefined) return;

      const rowHeight = schedule.clientHeight / 14;
      let top = 0;

      if (minutesNow <= firstMinutes) {
        top = 0;
      } else if (minutesNow >= lastMinutes) {
        const lastEntry = timelineEntries[timelineEntries.length - 1];
        top = lastEntry.period * rowHeight;
      } else {
        for (let index = 0; index < timelineEntries.length; index += 1) {
          const current = timelineEntries[index];

          if (minutesNow >= current.startMinutes && minutesNow <= current.endMinutes) {
            const slotSpan = Math.max(1, current.endMinutes - current.startMinutes);
            const slotOffset = (minutesNow - current.startMinutes) / slotSpan;
            top = ((current.period - 1) + slotOffset) * rowHeight;
            break;
          }

          const next = timelineEntries[index + 1];
          if (next && minutesNow > current.endMinutes && minutesNow < next.startMinutes) {
            const gapSpan = Math.max(1, next.startMinutes - current.endMinutes);
            const gapOffset = (minutesNow - current.endMinutes) / gapSpan;
            const gapStartTop = current.period * rowHeight;
            const gapEndTop = (next.period - 1) * rowHeight;
            top = gapStartTop + (gapEndTop - gapStartTop) * gapOffset;
            break;
          }
        }
      }

      const line = document.createElement('div');
      line.className = 'now-line';
      const targetCell = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="1"]');
      const scheduleRect = schedule.getBoundingClientRect();
      const targetRect = targetCell?.getBoundingClientRect();
      const left = targetRect ? (targetRect.left - scheduleRect.left) : 0;
      const width = targetRect ? targetRect.width : 0;
      line.style.left = left + 'px';
      line.style.width = Math.max(0, width) + 'px';
      line.style.top = top + 'px';
      line.innerHTML = '<span class="now-dot"></span>';
      schedule.appendChild(line);
    };

    const renderBoardHighlight = () => {
      boardHighlightLayer.innerHTML = '';
      const currentDayIndex = getCurrentDayIndex();
      if (currentDayIndex === -1 || !board || !gridScroll) return;

      const boardRect = board.getBoundingClientRect();
      const gridScrollRect = gridScroll.getBoundingClientRect();
      const headerCells = headerRow.querySelectorAll('.header-cell');
      const targetCell = headerCells[currentDayIndex + 1];
      if (!targetCell) return;

      const targetRect = targetCell.getBoundingClientRect();
      const highlight = document.createElement('div');
      highlight.className = 'board-column-highlight';
      highlight.style.top = targetRect.top - boardRect.top + 'px';
      highlight.style.height = gridScrollRect.bottom - targetRect.top + 'px';
      highlight.style.left = targetRect.left - boardRect.left + 4 + 'px';
      highlight.style.width = Math.max(0, targetRect.width - 8) + 'px';
      boardHighlightLayer.appendChild(highlight);
    };

    const renderHeader = () => {
      const weekDates = getWeekDates(state.week);
      const currentDayIndex = getCurrentDayIndex();
      headerRow.innerHTML = '<div class="header-cell"></div>' + weekdays.map((weekday, index) => {
        const isToday = index === currentDayIndex;
        return '<div class="header-cell' + (isToday ? ' today' : '') + '"><span class="header-date">' + formatDateLabel(weekDates[index]) + '</span><span class="header-weekday">' + weekday.replace('星期', '周') + '</span></div>';
      }).join('');
    };

    const syncViewportLayout = () => {
      const root = document.documentElement;
      const viewportWidth = window.innerWidth;
      const viewportHeight = window.innerHeight;
      const compact = viewportWidth <= 720;

      if (!compact) {
        root.style.removeProperty('--axis-width');
        root.style.removeProperty('--row-min-height');
        root.style.removeProperty('--row-height');
        return;
      }

      const heroHeight = hero?.getBoundingClientRect().height || 0;
      const headerHeight = headerRow.getBoundingClientRect().height || 32;
      const footerHeight = footerBar?.getBoundingClientRect().height || 56;
      const gap = 2;
      const gridGapHeight = gap * 13;
      const boardVerticalPadding = 12;
      const availableHeight = Math.max(14 * 20, viewportHeight - heroHeight - headerHeight - footerHeight - boardVerticalPadding - gridGapHeight);
      const rowHeight = Math.max(20, Math.floor(availableHeight / 14));
      const axisWidth = viewportWidth <= 390 ? 15 : 16;

      root.style.setProperty('--footer-height', Math.ceil(footerHeight) + 'px');
      root.style.setProperty('--axis-width', axisWidth + 'px');
      root.style.setProperty('--row-min-height', rowHeight + 'px');
      root.style.setProperty('--row-height', rowHeight + 'px');
    };

    const renderBackgroundGrid = () => {
      schedule.innerHTML = '';
      const currentDayIndex = getCurrentDayIndex();
      const dividerPeriods = [3, 5, 7, 10, 13];

      if (currentDayIndex !== -1) {
        const targetCell = headerRow.querySelectorAll('.header-cell')[currentDayIndex + 1];
        const headerRect = headerRow.getBoundingClientRect();
        const targetRect = targetCell?.getBoundingClientRect();
        const columnHighlight = document.createElement('div');
        columnHighlight.className = 'schedule-column-highlight';
        columnHighlight.style.left = (targetRect ? (targetRect.left - headerRect.left) : 0) + 'px';
        columnHighlight.style.width = Math.max(0, targetRect?.width || 0) + 'px';
        schedule.appendChild(columnHighlight);
      }

      for (let period = 1; period <= 14; period += 1) {
        const axis = document.createElement('div');
        axis.className = 'axis-cell';
        axis.style.gridColumn = '1';
        axis.style.gridRow = String(period);
        axis.textContent = String(period);
        schedule.appendChild(axis);

        for (let dayIndex = 0; dayIndex < weekdays.length; dayIndex += 1) {
          const slot = document.createElement('div');
          slot.className = 'slot-cell';
          slot.dataset.dayIndex = String(dayIndex);
          slot.dataset.period = String(period);
          slot.style.gridColumn = String(dayIndex + 2);
          slot.style.gridRow = String(period);
          schedule.appendChild(slot);
        }
      }

      for (const dayIndex of weekdays.map((_, index) => index)) {
        const firstSlot = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="1"]');
        const scheduleRect = schedule.getBoundingClientRect();
        const firstSlotRect = firstSlot?.getBoundingClientRect();
        if (!firstSlotRect) continue;

        const dayLeft = firstSlotRect.left - scheduleRect.left;
        const dayWidth = firstSlotRect.width;
        const lineWidth = dayWidth * 0.76;
        const lineLeft = dayLeft + (dayWidth - lineWidth) / 2;

        for (const period of dividerPeriods) {
          const upperSlot = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="' + period + '"]');
          const lowerSlot = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="' + (period + 1) + '"]');
          const upperRect = upperSlot?.getBoundingClientRect();
          const lowerRect = lowerSlot?.getBoundingClientRect();
          if (!upperRect || !lowerRect) continue;

          const divider = document.createElement('div');
          divider.className = 'session-divider';
          divider.style.left = lineLeft + 'px';
          divider.style.width = lineWidth + 'px';
          divider.style.top = ((upperRect.bottom + lowerRect.top) / 2 - scheduleRect.top) + 'px';
          schedule.appendChild(divider);
        }
      }
    };

    const renderLegend = (visibleCourses) => {
      const names = [...new Set(visibleCourses.map((course) => course.courseName))];
      legend.innerHTML = names.map((name) => {
        const [from, to] = colorMap.get(name);
        return '<div class="legend-item"><span class="legend-dot" style="background:linear-gradient(180deg,' + from + ',' + to + ')"></span>' + name + '</div>';
      }).join('');
    };

    const renderCards = (visibleCourses) => {
      for (const course of visibleCourses) {
        const dayIndex = weekdays.indexOf(course.weekday);
        if (dayIndex === -1) continue;

        const [from] = colorMap.get(course.paletteKey);
        const card = document.createElement('article');
        card.className = 'course-card';
        card.style.gridColumn = String(dayIndex + 2);
        card.style.gridRow = course.startPeriod + ' / ' + (course.endPeriod + 1);
        card.style.background = from;

        const time = periodSlots[course.startPeriod]?.start || '';
        const room = (course.classroom || '').trim();
        card.innerHTML = [
          '<div class="course-top"><div>' + time + '</div>' + (room ? ('<div class="course-room">' + room + '</div>') : '') + '</div>',
          '<div class="course-title"><div class="course-title-text">' + course.courseName + '</div></div>'
        ].join('');

        card.title = course.courseName + ' | ' + course.weekday + ' ' + course.periods;
        card.addEventListener('click', () => openSheet(course, card));
        schedule.appendChild(card);
      }
    };

    const render = () => {
      const visibleCourses = normalizeCourses
        .filter((course) => course.weeks.length === 0 || course.weeks.includes(state.week))
        .sort((left, right) => {
          const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
          if (dayDiff !== 0) return dayDiff;
          return left.startPeriod - right.startPeriod;
        });

      closeSheet();
      renderHeader();
      syncViewportLayout();
      weekLabel.textContent = '第' + state.week + '周';
      footerDay.textContent = getCurrentWeekdayLabel();
      renderBoardHighlight();
      renderBackgroundGrid();
      renderCards(visibleCourses);
      renderNowLine();
      renderLegend(visibleCourses);

      const hasCourses = visibleCourses.length > 0;
      emptyState.hidden = hasCourses;
      schedule.style.display = hasCourses ? 'grid' : 'none';
      legend.style.display = hasCourses ? 'flex' : 'none';
    };

    const shiftWeek = (offset) => {
      const currentIndex = allWeeks.indexOf(state.week);
      const nextIndex = Math.min(allWeeks.length - 1, Math.max(0, currentIndex + offset));
      state.week = allWeeks[nextIndex] || state.week;
      render();
    };

    document.getElementById('prevWeek').addEventListener('click', () => shiftWeek(-1));
    document.getElementById('nextWeek').addEventListener('click', () => shiftWeek(1));
    sheetOverlay.addEventListener('click', closeSheet);
    sheetClose.addEventListener('click', closeSheet);
    schedule.addEventListener('touchstart', (event) => {
      const touch = event.changedTouches[0];
      touchState.startX = touch.clientX;
      touchState.startY = touch.clientY;
    }, { passive: true });
    schedule.addEventListener('touchend', (event) => {
      if (detailSheet.classList.contains('show')) return;
      const touch = event.changedTouches[0];
      const deltaX = touch.clientX - touchState.startX;
      const deltaY = touch.clientY - touchState.startY;

      if (Math.abs(deltaX) < 48 || Math.abs(deltaX) <= Math.abs(deltaY)) {
        return;
      }

      shiftWeek(deltaX < 0 ? 1 : -1);
    }, { passive: true });
    window.addEventListener('resize', render);

    render();
  </script>`;

