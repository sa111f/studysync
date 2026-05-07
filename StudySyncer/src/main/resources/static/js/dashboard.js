'use strict';
/**
 * dashboard.js — Homepage dashboard (Phase 4).
 *
 * Renders four sections (Today, Overdue, Upcoming, Next Exams) from one
 * /api/tasks?status=active call, bucketed client-side. Drives
 * window.refreshDashboard() for timer.js to call after logging a session.
 *
 * Boot order (set by index.html):
 *     util.js → timer-core.js → timer.js → auth.js → dashboard.js
 * auth.js resolves window.authReady, which we await below.
 */

// ── State ─────────────────────────────────────────────────────
let dashboardTasks = [];

// ── Boot ──────────────────────────────────────────────────────
async function initDashboard() {
    // Wait until auth.js has resolved the session — window.currentUser is null
    // for guests, or the username string for logged-in users.
    try { await window.authReady; } catch (_) {}

    const guestPane = document.getElementById('dashboard-guest');
    const sections  = document.getElementById('dashboard-sections');

    if (!window.currentUser) {
        // Guest: show the marketing lockup in the left column, leave the
        // timer + goal localStorage fallback running in the sidebar.
        guestPane?.classList.remove('hidden');
        sections?.classList.add('hidden');
        document.documentElement.classList.add('home-is-guest');
        return;
    }

    guestPane?.classList.add('hidden');
    sections?.classList.remove('hidden');
    document.documentElement.classList.add('home-is-logged-in');

    await fetchAndRender();
}

/**
 * Fetch active tasks and paint all four sections.
 * Exposed as window.refreshDashboard so timer.js can nudge after a session
 * logs, and so the dashboard re-reflects the new secondsLogged totals.
 */
async function fetchAndRender() {
    try {
        const res = await fetch('/api/tasks?status=active');
        if (!res.ok) { dashboardTasks = []; }
        else         { dashboardTasks = await res.json(); }
    } catch (_) {
        dashboardTasks = [];
    }

    const buckets = bucketTasks(dashboardTasks);
    renderSection('today',    buckets.today);
    renderSection('overdue',  buckets.overdue);
    renderSection('upcoming', buckets.upcoming);

    // Phase 6 — fetch the nearest 3 upcoming exams and render them.
    // Separate try/catch so exam fetch failures never break the task UI.
    let nextExams = [];
    try {
        const res = await fetch('/api/exams/next?count=3');
        if (res.ok) nextExams = await res.json();
    } catch (_) { /* silent — exam card falls back to empty state */ }
    renderExamsSection(nextExams);
}

window.refreshDashboard = fetchAndRender;

// ── Bucketing ─────────────────────────────────────────────────

/**
 * Split active tasks into three disjoint buckets by user-local calendar date.
 *
 *   Today    = dueDate === today
 *   Overdue  = dueDate <  today           (status already filtered to active)
 *   Upcoming = dueDate >  today && dueDate <= today + 7d
 *
 * Tasks with dueDate further out than 7 days or with a null dueDate don't
 * appear on the dashboard — they're available via /tasks.
 */
function bucketTasks(tasks) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const cutoff = new Date(today);
    cutoff.setDate(cutoff.getDate() + 7);

    const out = { today: [], overdue: [], upcoming: [] };

    for (const t of (tasks || [])) {
        if (!t.dueDate) continue;
        const [y, m, d] = t.dueDate.split('-').map(Number);
        if (!y || !m || !d) continue;
        const due = new Date(y, m - 1, d);

        if (due.getTime() === today.getTime()) {
            out.today.push(t);
        } else if (due < today) {
            out.overdue.push(t);
        } else if (due <= cutoff) {
            out.upcoming.push(t);
        }
        // else: past the 7-day window — not on dashboard.
    }

    // Sort each bucket by dueDate ASC (tie-break by created if present) so
    // closer-due items surface first within each section.
    const byDue = (a, b) =>
        (a.dueDate || '').localeCompare(b.dueDate || '');
    out.today.sort(byDue);
    out.overdue.sort(byDue);
    out.upcoming.sort(byDue);

    return out;
}

// ── Section render ────────────────────────────────────────────

/**
 * Render one section's task list + count chip + empty state.
 *
 * Sections use a consistent id convention — list/empty/count elements live
 * at `#dash-{name}-list`, `#dash-{name}-empty`, `#dash-{name}-count`.
 * The Next Exams section reuses this contract by calling with `[]` so its
 * empty-state copy always shows (until Phase 6 wires real exam data in).
 */
function renderSection(name, tasks) {
    const listEl  = document.getElementById('dash-' + name + '-list');
    const emptyEl = document.getElementById('dash-' + name + '-empty');
    const countEl = document.getElementById('dash-' + name + '-count');
    if (!listEl || !emptyEl) return;

    if (!tasks || tasks.length === 0) {
        listEl.innerHTML = '';
        emptyEl.classList.remove('hidden');
        if (countEl) { countEl.textContent = ''; countEl.classList.add('hidden'); }
        return;
    }

    emptyEl.classList.add('hidden');
    listEl.innerHTML = tasks.map(t => renderDashTaskRow(t, name)).join('');

    if (countEl) {
        countEl.textContent = String(tasks.length);
        countEl.classList.remove('hidden');
    }
}

// ── Next Exams section (Phase 6.9) ────────────────────────────

/**
 * Render the Next Exams card using a compact exam-row layout — title,
 * course, location, time, and a relative "In N days" label. Clicking an
 * exam navigates to /exams?focus={id} (exams.js could later scroll-and-
 * pulse the matching row, same pattern as tasks.js's ?focus handler).
 */
function renderExamsSection(exams) {
    const listEl  = document.getElementById('dash-exams-list');
    const emptyEl = document.getElementById('dash-exams-empty');
    const countEl = document.getElementById('dash-exams-count');
    if (!listEl || !emptyEl) return;

    if (!exams || exams.length === 0) {
        listEl.innerHTML = '';
        emptyEl.classList.remove('hidden');
        // Swap the Phase 4 stub copy for a CTA into /exams.
        emptyEl.innerHTML = 'No upcoming exams. <a href="/exams">Add one →</a>';
        if (countEl) { countEl.textContent = ''; countEl.classList.add('hidden'); }
        return;
    }

    emptyEl.classList.add('hidden');
    listEl.innerHTML = exams.map(renderDashExamRow).join('');

    if (countEl) {
        countEl.textContent = String(exams.length);
        countEl.classList.remove('hidden');
    }
}

function renderDashExamRow(e) {
    const d = new Date(e.dateTime);
    const monthAbbrs = ['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC'];
    const pad = n => String(n).padStart(2, '0');

    let time;
    try {
        time = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(d);
    } catch (_) { time = pad(d.getHours()) + ':' + pad(d.getMinutes()); }

    let rel;
    if (e.daysUntil === 0)      rel = 'Today';
    else if (e.daysUntil === 1) rel = 'Tomorrow';
    else if (e.daysUntil > 1)   rel = 'In ' + e.daysUntil + ' days';
    else                        rel = Math.abs(e.daysUntil) + ' days ago';

    const metaBits = [];
    if (e.course)   metaBits.push(`<span>${_esc(e.course)}</span>`);
    if (e.location) metaBits.push(`<span>${_esc(e.location)}</span>`);
    const metaHtml = metaBits.join('<span class="dash-task-meta-sep">·</span>');

    return `
    <a class="dash-exam-row" href="/exams?focus=${encodeURIComponent(e.id)}">
        <div class="dash-exam-badge">
            <span class="dash-exam-month">${_esc(monthAbbrs[d.getMonth()])}</span>
            <span class="dash-exam-day">${_esc(String(d.getDate()))}</span>
        </div>
        <div class="dash-exam-body">
            <div class="dash-exam-title">${_esc(e.title || '')}</div>
            <div class="dash-exam-meta">${metaHtml}</div>
        </div>
        <div class="dash-exam-right">
            <span class="dash-exam-time">${_esc(time)}</span>
            <span class="dash-exam-relative">${_esc(rel)}</span>
        </div>
    </a>`;
}

// ── Row markup ────────────────────────────────────────────────

/**
 * One dashboard row. `sectionType` controls the due-date phrasing per
 * spec 4.3 (Today hides the date; Overdue says "Overdue by N days";
 * Upcoming uses "Tomorrow" / "In N days" / "Fri, May 3").
 */
function renderDashTaskRow(t, sectionType) {
    const priorityClass = (t.priority || 'MEDIUM').toLowerCase();
    const priorityLabel = priorityClass.charAt(0).toUpperCase() + priorityClass.slice(1);

    // Status → aria-label for the checkbox (describes the action, not the state).
    // NOT_STARTED → "Mark in progress", IN_PROGRESS → "Mark complete",
    // COMPLETED shouldn't appear on an active list but we handle it anyway.
    const nextActionLabel =
        t.status === 'NOT_STARTED' ? 'Mark in progress'
      : t.status === 'IN_PROGRESS' ? 'Mark complete'
      :                              'Mark not started';

    const metaBits = [];
    if (t.course) metaBits.push(`<span class="dash-task-course">${_esc(t.course)}</span>`);
    const dueStr = formatRelativeDue(t.dueDate, sectionType);
    if (dueStr) metaBits.push(`<span class="dash-task-due">${_esc(dueStr)}</span>`);
    if (t.secondsLogged && t.secondsLogged > 0 && window.SS) {
        metaBits.push(`<span class="dash-task-logged">${_esc(window.SS.formatDuration(t.secondsLogged))} logged</span>`);
    }

    const metaHtml = metaBits.join('<span class="dash-task-meta-sep">·</span>');

    const titleEsc = _esc(t.title || '');

    return `
    <div class="dash-task-row" data-task-id="${t.id}"
         data-section="${sectionType}"
         onclick="dashOnRowClick(event, ${t.id})">
        <button class="dash-task-checkbox" data-status="${_esc(t.status)}"
                onclick="event.stopPropagation(); dashCycleStatus(${t.id})"
                aria-label="${nextActionLabel}"></button>

        <div class="dash-task-body">
            <div class="dash-task-title-row">
                <span class="dash-task-title">${titleEsc}</span>
                <span class="dash-task-priority-dot dash-task-priority-${priorityClass}"
                      title="${priorityLabel} priority">
                    <span class="sr-only">${priorityLabel} priority</span>
                </span>
            </div>
            <div class="dash-task-meta">${metaHtml}</div>
        </div>

        <button class="dash-task-start-btn"
                onclick="event.stopPropagation(); startTimerForTask(${t.id})"
                aria-label="Start timer for ${_esc(t.title || '')}"
                title="Start timer for this task">
            <svg viewBox="0 0 24 24" fill="currentColor" width="11" height="11" aria-hidden="true">
                <polygon points="6,4 20,12 6,20"/>
            </svg>
            Start
        </button>
    </div>`;
}

// ── Row interactions ──────────────────────────────────────────

/**
 * Click a row body (not checkbox, not Start button) → navigate to
 * /tasks?focus={id}. tasks.js handles the ?focus query param by scrolling
 * the matching row into view and briefly pulsing it.
 */
function dashOnRowClick(event, taskId) {
    // Safety: if the click originated inside a button, do nothing — the
    // button's own stopPropagation should have caught it, but double-check.
    const tag = (event && event.target && event.target.tagName) || '';
    if (tag === 'BUTTON' || event.target.closest('button')) return;
    window.location.href = '/tasks?focus=' + encodeURIComponent(taskId);
}

/**
 * Cycle status NOT_STARTED → IN_PROGRESS → COMPLETED → NOT_STARTED.
 * On COMPLETED: fade out the row (300ms) then re-fetch so counts update.
 */
async function dashCycleStatus(taskId) {
    const t = dashboardTasks.find(x => x.id === taskId);
    if (!t) return;
    const next = _nextStatus(t.status);

    // Optimistic: if transitioning to COMPLETED, fade the row before the
    // network round-trip lands so the UI feels snappy.
    const row = document.querySelector('.dash-task-row[data-task-id="' + taskId + '"]');
    if (next === 'COMPLETED' && row) row.classList.add('dash-row-fading-out');

    try {
        const res = await fetch('/api/tasks/' + taskId + '/status', {
            method:  'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ status: next }),
        });
        if (!res.ok) {
            if (row) row.classList.remove('dash-row-fading-out');
            return;
        }
    } catch (_) {
        if (row) row.classList.remove('dash-row-fading-out');
        return;
    }

    if (next === 'COMPLETED') {
        // Let the 300ms fade finish before refetching — otherwise a
        // re-render will pull the row back in before it animates out.
        setTimeout(fetchAndRender, 300);
    } else {
        // Local patch: flip the in-memory task's status and re-render the
        // affected bucket without a network round-trip. (Status stays in
        // "active" so it doesn't drop out of a dashboard bucket.)
        t.status = next;
        const buckets = bucketTasks(dashboardTasks);
        renderSection('today',    buckets.today);
        renderSection('overdue',  buckets.overdue);
        renderSection('upcoming', buckets.upcoming);
    }

    // Timer picker may be stale if we just completed a task it listed.
    if (typeof window.refreshTimerTaskPicker === 'function') window.refreshTimerTaskPicker();
}

function _nextStatus(current) {
    if (current === 'NOT_STARTED') return 'IN_PROGRESS';
    if (current === 'IN_PROGRESS') return 'COMPLETED';
    return 'NOT_STARTED';
}

/**
 * Start the homepage timer targeted at a specific task.
 *   1. Set the <select> to that task's id and fire a `change` event.
 *   2. Smooth-scroll the timer card into the middle of the viewport.
 *   3. Add a 1.5s pulse ring so the user sees where the timer moved to.
 *
 * Intentionally does NOT auto-click Start — the user should confirm.
 */
function startTimerForTask(taskId) {
    const select = document.getElementById('timer-task-select');
    const card   = document.getElementById('home-timer-card');

    if (select) {
        // If the option isn't present (e.g. fresh task created via API and
        // picker not yet re-populated), inject a transient option so the
        // selection sticks. refreshTimerTaskPicker will re-render it
        // authoritatively moments later.
        if (!select.querySelector('option[value="' + taskId + '"]')) {
            const tmp = document.createElement('option');
            tmp.value = String(taskId);
            const t = dashboardTasks.find(x => x.id === taskId);
            tmp.textContent = t ? t.title : ('Task ' + taskId);
            select.appendChild(tmp);
        }
        select.value = String(taskId);
        select.dispatchEvent(new Event('change', { bubbles: true }));
    }

    if (card) {
        try { card.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
        catch (_) { /* graceful fallback — older browsers */ }

        card.classList.remove('timer-card-pulse'); // retrigger if called twice
        // Force reflow so the animation replays even if the class was just removed.
        void card.offsetWidth;
        card.classList.add('timer-card-pulse');
        setTimeout(() => card.classList.remove('timer-card-pulse'), 1500);
    }
}
window.startTimerForTask = startTimerForTask;
window.dashOnRowClick    = dashOnRowClick;
window.dashCycleStatus   = dashCycleStatus;

// ── Date formatting ───────────────────────────────────────────

/**
 * Relative due-date string per spec 4.3.
 *
 *   Today section    → '' (hidden — it's today, obviously)
 *   Overdue section  → 'Overdue by 1 day' / 'Overdue by N days'
 *   Upcoming section → 'Tomorrow' / 'In 2 days' / ... / 'Fri, May 3'
 */
function formatRelativeDue(isoDate, sectionType) {
    if (!isoDate) return '';

    // Today bucket hides the due date — no value-add.
    if (sectionType === 'today') return '';

    const [y, m, d] = isoDate.split('-').map(Number);
    if (!y || !m || !d) return isoDate;

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const due = new Date(y, m - 1, d);
    const days = Math.round((due - today) / 86_400_000);

    if (sectionType === 'overdue') {
        const n = Math.abs(days);
        return n === 1 ? 'Overdue by 1 day' : 'Overdue by ' + n + ' days';
    }

    // Upcoming bucket:
    if (days === 1) return 'Tomorrow';
    if (days <= 6 && days >= 2) return 'In ' + days + ' days';

    // Day-of-week + "Mon 3" past the immediate next-6 window (also applies
    // to the boundary day +7 for completeness).
    const dayNames   = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return dayNames[due.getDay()] + ', ' + monthNames[due.getMonth()] + ' ' + due.getDate();
}

// ── Esc ───────────────────────────────────────────────────────
function _esc(s) {
    return (window.SS && window.SS.escapeHtml) ? window.SS.escapeHtml(s) : String(s || '');
}

// ── Kick off ──────────────────────────────────────────────────
initDashboard();
