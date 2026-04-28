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

    const normalizeCourses = courses.map((course, index) => {
      const periodMatch = course.periods.match(/(\\d+)-(\\d+)/);
      const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
      const endPeriod = periodMatch ? Number(periodMatch[2]) : startPeriod;
      const weeks = [];
      const sequenceNumber = Number(course.courseSequence);

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
        uid: 'course-' + index,
        startPeriod,
        endPeriod,
        duration: endPeriod - startPeriod + 1,
        weeks,
        weeksText: course.weeks,
        paletteKey: course.courseName,
        courseKind: course.courseType || '未分类',
        sequenceNumber: Number.isFinite(sequenceNumber) ? sequenceNumber : Number.MAX_SAFE_INTEGER,
        primaryMajorSectionId: 0,
        majorSectionIds: []
      };
    });

    const getMajorSectionId = (period) => {
      if (period >= 1 && period <= 3) return 0;
      if (period >= 4 && period <= 5) return 1;
      if (period >= 6 && period <= 7) return 2;
      if (period >= 8 && period <= 10) return 3;
      if (period >= 11 && period <= 13) return 4;
      return 5;
    };

    const getCoveredSectionIds = (startPeriod, endPeriod) => {
      const sectionIds = [];
      for (let period = startPeriod; period <= endPeriod; period += 1) {
        const sectionId = getMajorSectionId(period);
        if (!sectionIds.includes(sectionId)) {
          sectionIds.push(sectionId);
        }
      }
      return sectionIds;
    };

    normalizeCourses.forEach((course) => {
      course.majorSectionIds = getCoveredSectionIds(course.startPeriod, course.endPeriod);
      course.primaryMajorSectionId = course.majorSectionIds[0] ?? getMajorSectionId(course.startPeriod);
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
    const footerBar = document.querySelector('.footer-bar');
    const footerCenter = document.querySelector('.footer-center');
    const footerDay = document.getElementById('footerDay');
    const weekLabel = document.getElementById('weekLabel');
    const modeToggle = document.getElementById('modeToggle');
    const prevWeekButton = document.getElementById('prevWeek');
    const nextWeekButton = document.getElementById('nextWeek');
    const sheetOverlay = document.getElementById('sheetOverlay');
    const focusedCardLayer = document.getElementById('focusedCardLayer');
    const detailSheet = document.getElementById('detailSheet');
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
    const sheetDots = document.getElementById('sheetDots');

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
      viewMode: 'week',
      activeGroupId: null,
      activeCourseIndex: 0
    };

    const touchState = { startX: 0, startY: 0 };
    const sheetTouchState = { startX: 0, startY: 0 };

    let currentCardsByUid = new Map();
    let currentGroupsById = new Map();
    let currentGroupLayouts = new Map();
    let currentCourseMetaByUid = new Map();
    let focusedCardClones = [];

    const clearFocusedCardClones = () => {
      focusedCardClones.forEach((clone) => clone.remove());
      focusedCardClones = [];
    };

    const closeSheet = () => {
      state.activeGroupId = null;
      state.activeCourseIndex = 0;
      detailSheet.classList.remove('show');
      sheetOverlay.classList.remove('show');
      detailSheet.setAttribute('aria-hidden', 'true');
      document.body.classList.remove('sheet-open');
      schedule.querySelectorAll('.course-card.active').forEach((card) => card.classList.remove('active'));
      clearFocusedCardClones();
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

    const getCurrentDayIndex = () => {
      if (state.viewMode === 'full') return -1;
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
        .map(([period, slot]) => ({
          period: Number(period),
          startMinutes: toMinutes(slot.start),
          endMinutes: toMinutes(slot.end)
        }))
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

      const headerHeight = headerRow.getBoundingClientRect().height || 32;
      const footerHeight = footerBar?.getBoundingClientRect().height || 56;
      const gap = 2;
      const gridGapHeight = gap * 13;
      const boardVerticalPadding = 12;
      const availableHeight = Math.max(14 * 20, viewportHeight - headerHeight - footerHeight - boardVerticalPadding - gridGapHeight);
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

    const compareVisibleOrder = (left, right) => {
      const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
      if (dayDiff !== 0) return dayDiff;
      if (left.primaryMajorSectionId !== right.primaryMajorSectionId) return left.primaryMajorSectionId - right.primaryMajorSectionId;
      if (left.startPeriod !== right.startPeriod) return left.startPeriod - right.startPeriod;
      if (left.endPeriod !== right.endPeriod) return left.endPeriod - right.endPeriod;
      return left.sequenceNumber - right.sequenceNumber;
    };

    const compareConflictStackOrder = (left, right) => {
      if (left.duration !== right.duration) return right.duration - left.duration;
      if (left.startPeriod !== right.startPeriod) return left.startPeriod - right.startPeriod;
      if (left.sequenceNumber !== right.sequenceNumber) return left.sequenceNumber - right.sequenceNumber;
      return left.uid.localeCompare(right.uid);
    };

    const buildConflictState = (visibleCourses) => {
      const sortedCourses = [...visibleCourses].sort(compareVisibleOrder);
      const groupsById = new Map();
      const courseMetaByUid = new Map();
      let groupSerial = 0;

      weekdays.forEach((weekday) => {
        const dayCourses = sortedCourses.filter((course) => course.weekday === weekday);
        const bySection = new Map();

        dayCourses.forEach((course) => {
          course.majorSectionIds.forEach((key) => {
            if (!bySection.has(key)) {
              bySection.set(key, []);
            }
            bySection.get(key).push(course);
          });
        });

        [...bySection.entries()]
          .sort((left, right) => left[0] - right[0])
          .forEach(([majorSectionId, sectionCourses]) => {
            const ordered = [...sectionCourses].sort(compareConflictStackOrder);
            const groupId = 'group-' + groupSerial++;
            const group = {
              id: groupId,
              weekday,
              dayIndex: weekdays.indexOf(weekday),
              majorSectionId,
              courses: ordered
            };

            groupsById.set(groupId, group);
            ordered.forEach((course, stackIndex) => {
              const nextMeta = {
                groupId,
                stackIndex,
                stackSize: ordered.length,
                majorSectionId
              };
              const existing = courseMetaByUid.get(course.uid) || [];
              existing.push(nextMeta);
              courseMetaByUid.set(course.uid, existing);
            });
          });
      });

      return { sortedCourses, groupsById, courseMetaByUid };
    };

    const getSlotFrame = (dayIndex, startPeriod, endPeriod) => {
      const startCell = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="' + startPeriod + '"]');
      const endCell = schedule.querySelector('.slot-cell[data-day-index="' + dayIndex + '"][data-period="' + endPeriod + '"]');
      const startRect = startCell?.getBoundingClientRect();
      const endRect = endCell?.getBoundingClientRect();
      if (!startRect || !endRect) return null;

      return {
        left: startRect.left,
        top: startRect.top,
        width: startRect.width,
        height: endRect.bottom - startRect.top
      };
    };

    const buildSpreadTargets = (baseDayIndex, count) => {
      const targets = [];
      const deltas = [];
      const usedDays = new Set([baseDayIndex]);

      for (let step = 1; step < weekdays.length; step += 1) {
        deltas.push(step, -step);
      }

      let overflowLevel = 1;

      for (let index = 0; index < count; index += 1) {
        let targetDayIndex = null;

        for (const delta of deltas) {
          const candidate = baseDayIndex + delta;
          if (candidate < 0 || candidate >= weekdays.length || usedDays.has(candidate)) {
            continue;
          }

          usedDays.add(candidate);
          targetDayIndex = candidate;
          break;
        }

        if (targetDayIndex === null) {
          targets.push({ dayIndex: baseDayIndex, liftLevel: overflowLevel });
          overflowLevel += 1;
        } else {
          targets.push({ dayIndex: targetDayIndex, liftLevel: 0 });
        }
      }

      return targets;
    };

    const buildGroupLayout = (group) => {
      const placements = new Map();
      const spreadTargets = buildSpreadTargets(group.dayIndex, Math.max(0, group.courses.length - 1));
      placements.set(group.courses[0].uid, { dayIndex: group.dayIndex, liftLevel: 0 });
      group.courses.slice(1).forEach((course, index) => placements.set(course.uid, spreadTargets[index]));

      const displayCourses = [...group.courses].sort((left, right) => {
        const leftPlacement = placements.get(left.uid) || { dayIndex: group.dayIndex, liftLevel: 0 };
        const rightPlacement = placements.get(right.uid) || { dayIndex: group.dayIndex, liftLevel: 0 };
        if (leftPlacement.dayIndex !== rightPlacement.dayIndex) {
          return leftPlacement.dayIndex - rightPlacement.dayIndex;
        }
        if (leftPlacement.liftLevel !== rightPlacement.liftLevel) {
          return leftPlacement.liftLevel - rightPlacement.liftLevel;
        }
        return compareConflictStackOrder(left, right);
      });

      return { placements, displayCourses };
    };

    const detectClickedSectionId = (course, card, clientY) => {
      if (!course.majorSectionIds || course.majorSectionIds.length <= 1) {
        return course.primaryMajorSectionId;
      }

      const rect = card.getBoundingClientRect();
      const ratio = rect.height > 0 ? Math.min(0.999, Math.max(0, (clientY - rect.top) / rect.height)) : 0;
      const rawPeriod = course.startPeriod + ratio * course.duration;
      const approximatePeriod = Math.min(course.endPeriod, Math.max(course.startPeriod, Math.floor(rawPeriod)));
      const sectionId = getMajorSectionId(approximatePeriod);
      return course.majorSectionIds.includes(sectionId) ? sectionId : course.primaryMajorSectionId;
    };

    const createCardClone = (card, frame, selected) => {
      const clone = card.cloneNode(true);
      clone.classList.remove('active');
      clone.classList.add('focused-card-clone');
      clone.style.left = frame.left + 'px';
      clone.style.top = frame.top + 'px';
      clone.style.width = frame.width + 'px';
      clone.style.height = frame.height + 'px';
      clone.style.transform = selected ? 'translateY(-6px) scale(1.075)' : 'translateY(-1px) scale(1.01)';
      clone.style.zIndex = selected ? '1099' : '1092';
      clone.style.boxShadow = selected
        ? '0 26px 38px rgba(64, 92, 138, 0.4)'
        : '0 14px 24px rgba(64, 92, 138, 0.26)';
      clone.style.opacity = selected ? '1' : '0.96';
      focusedCardLayer.appendChild(clone);
      focusedCardClones.push(clone);
    };

    const updateSheetDots = (group) => {
      const total = group.courses.length;
      const showDots = total > 1;
      sheetDots.hidden = !showDots;
      if (!showDots) {
        sheetDots.innerHTML = '';
        return;
      }

      sheetDots.innerHTML = group.courses.map((_, index) =>
        '<button class="sheet-dot' + (index === state.activeCourseIndex ? ' active' : '') + '" type="button" data-dot-index="' + index + '" aria-label="切换到第' + (index + 1) + '门"></button>'
      ).join('');

      sheetDots.querySelectorAll('[data-dot-index]').forEach((dot) => {
        dot.addEventListener('click', () => {
          const nextIndex = Number(dot.getAttribute('data-dot-index'));
          if (!Number.isFinite(nextIndex) || nextIndex === state.activeCourseIndex) {
            return;
          }
          state.activeCourseIndex = nextIndex;
          refreshOpenSheet();
        });
      });
    };

    const updateSheetContent = (course, group) => {
      sheetWeekDay.textContent = state.week + '周' + course.weekday.replace('星期', '周');
      sheetPeriodLine.textContent = course.periods.replace('小节', '节 ') + getCourseTimeRange(course);
      sheetTitle.textContent = course.courseName;
      sheetTeacher.textContent = course.teacher || '未标注教师';
      sheetRoom.textContent = course.classroom || '待定地点';
      sheetWeeks.textContent = course.weeksText || '全学期';
      sheetCode.textContent = course.courseCode || '-';
      sheetWatermark.textContent = String(course.startPeriod) + String(course.endPeriod);
      sheetTag.textContent = course.courseKind;
      updateSheetDots(group);
    };

    const updateExpandedCards = () => {
      clearFocusedCardClones();
      schedule.querySelectorAll('.course-card.active').forEach((card) => card.classList.remove('active'));

      if (!state.activeGroupId) return;

      const group = currentGroupsById.get(state.activeGroupId);
      if (!group) {
        closeSheet();
        return;
      }

      const layout = currentGroupLayouts.get(group.id) || buildGroupLayout(group);
      currentGroupLayouts.set(group.id, layout);
      const selectedCourse = layout.displayCourses[state.activeCourseIndex] || layout.displayCourses[0] || group.courses[0];
      const placements = layout.placements;

      group.courses.forEach((course) => {
        const placement = placements.get(course.uid);
        const card = currentCardsByUid.get(course.uid);
        if (!placement || !card) return;

        const frame = getSlotFrame(placement.dayIndex, course.startPeriod, course.endPeriod);
        if (!frame) return;

        const liftedFrame = {
          ...frame,
          top: frame.top - placement.liftLevel * 14
        };

        if (course.uid === selectedCourse.uid) {
          card.classList.add('active');
        }

        createCardClone(card, liftedFrame, course.uid === selectedCourse.uid);
      });
    };

    const refreshOpenSheet = () => {
      const group = currentGroupsById.get(state.activeGroupId);
      if (!group) {
        closeSheet();
        return;
      }

      const layout = currentGroupLayouts.get(group.id) || buildGroupLayout(group);
      currentGroupLayouts.set(group.id, layout);
      const course = layout.displayCourses[state.activeCourseIndex] || layout.displayCourses[0] || group.courses[0];
      updateSheetContent(course, group);
      updateExpandedCards();
      detailSheet.classList.add('show');
      sheetOverlay.classList.add('show');
      detailSheet.setAttribute('aria-hidden', 'false');
      document.body.classList.add('sheet-open');
    };

    const openSheetForCourse = (course, event, card) => {
      const metaList = currentCourseMetaByUid.get(course.uid);
      if (!metaList || metaList.length === 0) return;

      const clickedSectionId = detectClickedSectionId(course, card, event.clientY);
      const meta = metaList.find((item) => item.majorSectionId === clickedSectionId) || metaList[0];
      if (!meta) return;

      const group = currentGroupsById.get(meta.groupId);
      if (!group) return;
      const layout = currentGroupLayouts.get(group.id) || buildGroupLayout(group);
      currentGroupLayouts.set(group.id, layout);

      state.activeGroupId = meta.groupId;
      state.activeCourseIndex = Math.max(0, layout.displayCourses.findIndex((item) => item.uid === course.uid));
      refreshOpenSheet();
    };

    const shiftActiveCourse = (offset) => {
      if (!state.activeGroupId) return;
      const group = currentGroupsById.get(state.activeGroupId);
      if (!group || group.courses.length <= 1) return;

      const layout = currentGroupLayouts.get(group.id) || buildGroupLayout(group);
      currentGroupLayouts.set(group.id, layout);
      const total = layout.displayCourses.length;
      if (total <= 1) return;

      state.activeCourseIndex = (state.activeCourseIndex + offset + total) % total;
      refreshOpenSheet();
    };

    const renderCards = (visibleCourses) => {
      const { sortedCourses, groupsById, courseMetaByUid } = buildConflictState(visibleCourses);
      currentCardsByUid = new Map();
      currentGroupsById = groupsById;
      currentGroupLayouts = new Map();
      currentCourseMetaByUid = courseMetaByUid;

      groupsById.forEach((group) => {
        currentGroupLayouts.set(group.id, buildGroupLayout(group));
      });

      for (const course of sortedCourses) {
        const dayIndex = weekdays.indexOf(course.weekday);
        if (dayIndex === -1) continue;

        const [from] = colorMap.get(course.paletteKey);
        const metaList = courseMetaByUid.get(course.uid) || [];
        const meta = metaList[0];
        const stackIndex = meta?.stackIndex ?? 0;
        const stackSize = meta?.stackSize ?? 1;
        const card = document.createElement('article');
        card.className = 'course-card' + (stackSize > 1 ? ' stacked' : '');
        card.style.gridColumn = String(dayIndex + 2);
        card.style.gridRow = course.startPeriod + ' / ' + (course.endPeriod + 1);
        card.style.background = from;
        card.style.zIndex = String(120 + stackIndex);
        card.style.transform = stackSize > 1 ? 'translate(' + (stackIndex * 2) + 'px,' + (-stackIndex * 2) + 'px)' : '';

        const time = periodSlots[course.startPeriod]?.start || '';
        const room = (course.classroom || '').trim();
        card.innerHTML = [
          '<div class="course-top"><div>' + time + '</div>' + (room ? ('<div class="course-room">' + room + '</div>') : '') + '</div>',
          '<div class="course-title"><div class="course-title-text">' + course.courseName + '</div></div>'
        ].join('');

        card.title = course.courseName + ' | ' + course.weekday + ' ' + course.periods;
        card.addEventListener('click', (event) => openSheetForCourse(course, event, card));
        currentCardsByUid.set(course.uid, card);
        schedule.appendChild(card);
      }
    };

    const render = () => {
      const visibleCourses = normalizeCourses.filter((course) =>
        state.viewMode === 'full' || course.weeks.length === 0 || course.weeks.includes(state.week)
      );

      closeSheet();
      renderHeader();
      syncViewportLayout();
      weekLabel.textContent = state.viewMode === 'full' ? '全学期' : ('第' + state.week + '周');
      footerDay.textContent = state.viewMode === 'full' ? '总课表' : getCurrentWeekdayLabel();
      modeToggle.textContent = state.viewMode === 'full' ? '全' : '周';
      if (prevWeekButton) prevWeekButton.hidden = state.viewMode === 'full';
      if (nextWeekButton) nextWeekButton.hidden = state.viewMode === 'full';
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
      if (state.viewMode === 'full') return;
      const currentIndex = allWeeks.indexOf(state.week);
      const nextIndex = Math.min(allWeeks.length - 1, Math.max(0, currentIndex + offset));
      state.week = allWeeks[nextIndex] || state.week;
      render();
    };

    const toggleViewMode = () => {
      state.viewMode = state.viewMode === 'week' ? 'full' : 'week';
      render();
    };

    document.getElementById('prevWeek').addEventListener('click', () => shiftWeek(-1));
    document.getElementById('nextWeek').addEventListener('click', () => shiftWeek(1));
    modeToggle.addEventListener('click', toggleViewMode);
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

    detailSheet.addEventListener('touchstart', (event) => {
      const touch = event.changedTouches[0];
      sheetTouchState.startX = touch.clientX;
      sheetTouchState.startY = touch.clientY;
    }, { passive: true });

    detailSheet.addEventListener('touchend', (event) => {
      if (!state.activeGroupId) return;
      const group = currentGroupsById.get(state.activeGroupId);
      if (!group || group.courses.length <= 1) return;

      const touch = event.changedTouches[0];
      const deltaX = touch.clientX - sheetTouchState.startX;
      const deltaY = touch.clientY - sheetTouchState.startY;

      if (Math.abs(deltaX) < 36 || Math.abs(deltaX) <= Math.abs(deltaY)) {
        return;
      }

      shiftActiveCourse(deltaX < 0 ? 1 : -1);
    }, { passive: true });

    detailSheet.addEventListener('wheel', (event) => {
      if (!state.activeGroupId) return;
      const group = currentGroupsById.get(state.activeGroupId);
      if (!group || group.courses.length <= 1) return;

      const primaryDelta = Math.abs(event.deltaX) >= Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
      if (Math.abs(primaryDelta) < 10) return;

      event.preventDefault();
      shiftActiveCourse(primaryDelta > 0 ? 1 : -1);
    }, { passive: false });

    window.addEventListener('resize', render);

    render();
  </script>`;
