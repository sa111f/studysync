'use strict';

// ══════════════════════════════════════════════════════════════
//  exams.js — /exams page UI layer (Phase 6.7).
//
//  Responsibilities:
//    - Fetch the user's exams from /api/exams
//    - Group Upcoming into bucket sections (This week / Next week /
//      Later) using server-computed .bucket
//    - Flat chronological list for Past / All
//    - Inline expand/collapse for detail + edit + delete
//    - Manual create form (date + time combined client-side and sent
//      to the server as separate strings — shape B of ExamRequest)
// ══════════════════════════════════════════════════════════════

let exams           = [];
let currentFilter   = 'upcoming';     // upcoming | past | all
let expandedExamId  = null;
let isLoggedIn      = false;
let editingExamId   = null;

// ── Boot ───────────────────────────────────────────────────────
async function initExams() {
    let username = null;
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            username   = data.username || null;
            isLoggedIn = true;
        }
    } catch (_) { /* guest */ }

    window.currentUser = isLoggedIn ? username : null;
    _updateHeaderAuthBar(username);

    if (!isLoggedIn) { _showGuestState(); return; }
    _showLoggedInState();
    await fetchExams();
}

function _updateHeaderAuthBar(username) {
    const guest = document.getElementById('auth-guest');
    const user  = document.getElementById('auth-user');
    const name  = document.getElementById('auth-username');
    if (isLoggedIn) {
        guest?.classList.add('hidden');
        user?.classList.remove('hidden');
        if (name) name.textContent = `Hi, ${username}`;
    } else {
        guest?.classList.remove('hidden');
        user?.classList.add('hidden');
    }
}

async function examsLogout() {
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (_) {}
    window.location.href = '/';
}
window.examsLogout = examsLogout;

function _showLoggedInState() {
    document.getElementById('exams-guest-banner')?.classList.add('hidden');
    document.getElementById('exam-add-btn')?.classList.remove('hidden');
    document.getElementById('exam-ai-add-btn')?.classList.remove('hidden');
    document.querySelector('.task-filter-bar')?.classList.remove('hidden');
}
function _showGuestState() {
    document.getElementById('exams-guest-banner')?.classList.remove('hidden');
    document.getElementById('exam-add-btn')?.classList.add('hidden');
    document.getElementById('exam-ai-add-btn')?.classList.add('hidden');
    document.getElementById('exam-create-form')?.classList.add('hidden');
    document.querySelector('.task-filter-bar')?.classList.add('hidden');
    document.getElementById('exam-sections')?.classList.add('hidden');
    document.getElementById('exam-flat-list')?.classList.add('hidden');
}

// ── Fetch + render ─────────────────────────────────────────────
async function fetchExams() {
    if (!isLoggedIn) return;
    let tz = 'UTC';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) {}

    try {
        const url = '/api/exams?filter=' + encodeURIComponent(currentFilter)
                  + '&tz='     + encodeURIComponent(tz);
        const res = await fetch(url);
        if (res.status === 401) { window.location.href = '/'; return; }
        if (!res.ok) { exams = []; }
        else         { exams = await res.json(); }
    } catch (_) { exams = []; }

    renderExams();
}
window.fetchExams = fetchExams;

function setExamFilter(filter) {
    if (filter === currentFilter) return;
    currentFilter = filter;
    expandedExamId = null;
    editingExamId  = null;
    document.querySelectorAll('.task-filter-bar .task-filter-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.filter === filter));
    fetchExams();
}
window.setExamFilter = setExamFilter;

function renderExams() {
    const sections = document.getElementById('exam-sections');
    const flat     = document.getElementById('exam-flat-list');
    const empty    = document.getElementById('exam-empty');
    if (!sections || !flat || !empty) return;

    if (!exams || exams.length === 0) {
        sections.classList.add('hidden');
        flat.classList.add('hidden');
        empty.classList.remove('hidden');
        empty.innerHTML = _emptyStateHtml(currentFilter);
        return;
    }
    empty.classList.add('hidden');

    if (currentFilter === 'upcoming') {
        // Group by server-computed bucket.
        const groups = { THIS_WEEK: [], NEXT_WEEK: [], LATER: [] };
        for (const e of exams) {
            const b = e.bucket || 'LATER';
            if (groups[b]) groups[b].push(e);
        }
        _paintBucket('this-week', groups.THIS_WEEK);
        _paintBucket('next-week', groups.NEXT_WEEK);
        _paintBucket('later',     groups.LATER);
        sections.classList.remove('hidden');
        flat.classList.add('hidden');
    } else {
        // Past / All — one flat chronological list.
        flat.innerHTML = exams.map(e => _renderExamRow(e, expandedExamId === e.id)).join('');
        flat.classList.remove('hidden');
        sections.classList.add('hidden');
    }
}

function _paintBucket(slug, bucketExams) {
    const section = document.getElementById('exams-' + slug);
    const list    = document.getElementById('exams-' + slug + '-list');
    if (!section || !list) return;

    if (!bucketExams || bucketExams.length === 0) {
        section.classList.add('hidden');
        list.innerHTML = '';
        return;
    }
    section.classList.remove('hidden');
    list.innerHTML = bucketExams
            .map(e => _renderExamRow(e, expandedExamId === e.id))
            .join('');
}

function _emptyStateHtml(filter) {
    if (filter === 'past') return 'No past exams yet.';
    // Upcoming / All share the same nudge — future phases wire the
    // syllabus import link, so keep it visible now.
    return 'No exams scheduled. 🎉 Add one above, or ' +
           '<a href="/import-syllabus">import from a syllabus PDF →</a>';
}

// ── Row markup ─────────────────────────────────────────────────

function _renderExamRow(e, isExpanded) {
    const fmt = _formatExamDate(e.dateTime);
    const rel = _formatRelative(e.daysUntil, currentFilter === 'past');

    const courseHtml   = e.course   ? `<span class="exam-row-course">${_esc(e.course)}</span>`     : '';
    const locationHtml = e.location ? `<span class="exam-row-loc">${_esc(e.location)}</span>`      : '';

    const metaSep = (e.course && e.location) ? '<span class="dash-task-meta-sep">·</span>' : '';

    const rowHtml = `
    <div class="exam-row ${isExpanded ? 'expanded' : ''}" data-id="${e.id}"
         onclick="onExamRowClick(event, ${e.id})">
        <div class="exam-date-badge" aria-hidden="true">
            <span class="exam-date-month">${_esc(fmt.monthAbbr)}</span>
            <span class="exam-date-day">${_esc(fmt.day)}</span>
        </div>
        <div class="exam-row-body">
            <div class="exam-row-title">${_esc(e.title)}</div>
            <div class="exam-row-meta">
                ${courseHtml}${metaSep}${locationHtml}
            </div>
        </div>
        <div class="exam-row-right">
            <span class="exam-row-time">${_esc(fmt.time)}</span>
            <span class="exam-row-relative ${currentFilter === 'past' ? 'past' : ''}">${_esc(rel)}</span>
        </div>
    </div>`;

    return rowHtml + (isExpanded ? _renderExamDetail(e) : '');
}

function _renderExamDetail(e) {
    if (editingExamId === e.id) return _renderExamEditForm(e);

    const fmt    = _formatExamDate(e.dateTime);
    const course   = e.course   ? `<div class="exam-detail-field"><span class="exam-detail-label">Course</span><span>${_esc(e.course)}</span></div>` : '';
    const location = e.location ? `<div class="exam-detail-field"><span class="exam-detail-label">Location</span><span>${_esc(e.location)}</span></div>` : '';
    const material = e.material ? `<div class="exam-detail-field"><span class="exam-detail-label">Material</span><span>${_esc(e.material)}</span></div>` : '';
    const notes    = e.notes    ? `<div class="exam-detail-field"><span class="exam-detail-label">Notes</span><span>${_esc(e.notes)}</span></div>` : '';

    return `
    <div class="exam-detail-panel">
        <div class="exam-detail-field">
            <span class="exam-detail-label">When</span>
            <span>${_esc(fmt.fullDatetime)}</span>
        </div>
        ${course}
        ${location}
        ${material}
        ${notes}
        <div class="exam-detail-actions">
            <button class="save-btn" onclick="event.stopPropagation(); showExamEditForm(${e.id})">Edit</button>
            <button class="delete-btn" onclick="event.stopPropagation(); deleteExam(${e.id})">Delete</button>
        </div>
    </div>`;
}

function _renderExamEditForm(e) {
    const fmt = _formatExamDate(e.dateTime);
    // Split the server's ISO instant into date + time inputs using the
    // user's browser zone (matches how the manual form sends it back).
    const iso = new Date(e.dateTime);
    const pad = n => String(n).padStart(2, '0');
    const dateStr = iso.getFullYear() + '-' + pad(iso.getMonth() + 1) + '-' + pad(iso.getDate());
    const timeStr = pad(iso.getHours()) + ':' + pad(iso.getMinutes());

    return `
    <form class="task-create-form exam-edit-form" data-id="${e.id}"
          onclick="event.stopPropagation()"
          onsubmit="event.preventDefault(); submitExamEditForm(${e.id})">
        <div class="task-form-row task-form-row-2">
            <div class="task-field">
                <label class="task-label" for="edit-exam-title-${e.id}">Title</label>
                <input type="text" id="edit-exam-title-${e.id}" class="field-input task-input"
                       maxlength="200" value="${_escAttr(e.title || '')}" />
            </div>
            <div class="task-field">
                <label class="task-label" for="edit-exam-course-${e.id}">Course</label>
                <input type="text" id="edit-exam-course-${e.id}" class="field-input task-input"
                       maxlength="100" value="${_escAttr(e.course || '')}" />
            </div>
        </div>
        <div class="task-form-row task-form-row-2">
            <div class="task-field">
                <label class="task-label" for="edit-exam-date-${e.id}">Date</label>
                <input type="date" id="edit-exam-date-${e.id}" class="field-input task-input"
                       value="${_escAttr(dateStr)}" />
            </div>
            <div class="task-field">
                <label class="task-label" for="edit-exam-time-${e.id}">Time</label>
                <input type="time" id="edit-exam-time-${e.id}" class="field-input task-input"
                       value="${_escAttr(timeStr)}" />
            </div>
        </div>
        <div class="task-field">
            <label class="task-label" for="edit-exam-location-${e.id}">Location</label>
            <input type="text" id="edit-exam-location-${e.id}" class="field-input task-input"
                   maxlength="200" value="${_escAttr(e.location || '')}" />
        </div>
        <div class="task-field">
            <label class="task-label" for="edit-exam-material-${e.id}">Material</label>
            <textarea id="edit-exam-material-${e.id}" class="field-input task-input task-textarea"
                      maxlength="2000" rows="2">${_esc(e.material || '')}</textarea>
        </div>
        <div class="task-field">
            <label class="task-label" for="edit-exam-notes-${e.id}">Notes</label>
            <textarea id="edit-exam-notes-${e.id}" class="field-input task-input task-textarea"
                      maxlength="1000" rows="2">${_esc(e.notes || '')}</textarea>
        </div>
        <div id="edit-exam-error-${e.id}" class="auth-error hidden"></div>
        <div class="task-form-actions">
            <button type="button" class="apply-btn" onclick="hideExamEditForm()">Cancel</button>
            <button type="submit" class="save-btn">Save</button>
        </div>
    </form>`;
}

// ── Row interactions ───────────────────────────────────────────

function onExamRowClick(event, id) {
    if (editingExamId === id) return;
    if (expandedExamId === id) {
        // Collapse same row
        expandedExamId = null;
    } else {
        expandedExamId = id;
        editingExamId  = null;
    }
    renderExams();
}
window.onExamRowClick = onExamRowClick;

function showExamEditForm(id) {
    editingExamId = id;
    expandedExamId = id;
    renderExams();
}
window.showExamEditForm = showExamEditForm;

function hideExamEditForm() {
    editingExamId = null;
    renderExams();
}
window.hideExamEditForm = hideExamEditForm;

async function submitExamEditForm(id) {
    const g = (suffix) => document.getElementById('edit-exam-' + suffix + '-' + id)?.value || '';
    const body = {
        title:    g('title').trim(),
        course:   g('course').trim() || null,
        date:     g('date'),
        time:     g('time'),
        timezone: (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone; } catch(_) { return 'UTC'; } })(),
        location: g('location').trim() || null,
        material: g('material').trim() || null,
        notes:    g('notes').trim() || null,
    };

    const errEl = document.getElementById('edit-exam-error-' + id);
    if (!body.title) { _showExamErr(errEl, 'Title is required.'); return; }
    if (!body.date)  { _showExamErr(errEl, 'Date is required.');  return; }
    if (!body.time)  { _showExamErr(errEl, 'Time is required.');  return; }

    try {
        const res = await fetch('/api/exams/' + id, {
            method:  'PUT',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            _showExamErr(errEl, err.error || 'Could not save changes.');
            return;
        }
    } catch (_) { _showExamErr(errEl, 'Could not reach the server.'); return; }

    editingExamId = null;
    await fetchExams();
}
window.submitExamEditForm = submitExamEditForm;

async function deleteExam(id) {
    if (!window.confirm("Delete this exam? This can't be undone.")) return;
    try {
        const res = await fetch('/api/exams/' + id, { method: 'DELETE' });
        if (res.ok || res.status === 204) {
            expandedExamId = null;
            editingExamId  = null;
            await fetchExams();
        }
    } catch (_) { /* silent */ }
}
window.deleteExam = deleteExam;

// ── Manual create form ─────────────────────────────────────────

function showExamCreateForm() {
    editingExamId = null;
    const form = document.getElementById('exam-create-form');
    if (!form) return;
    form.classList.remove('hidden');
    document.getElementById('exam-add-btn')?.classList.add('hidden');
    const dateEl = document.getElementById('exam-create-date');
    if (dateEl && !dateEl.value) dateEl.value = _todayIso();
    document.getElementById('exam-create-title')?.focus();
}
window.showExamCreateForm = showExamCreateForm;

function hideExamCreateForm() {
    const form = document.getElementById('exam-create-form');
    if (!form) return;
    form.classList.add('hidden');
    form.reset();
    document.getElementById('exam-create-error')?.classList.add('hidden');
    document.getElementById('exam-add-btn')?.classList.remove('hidden');
}
window.hideExamCreateForm = hideExamCreateForm;

async function submitExamCreateForm() {
    const errEl = document.getElementById('exam-create-error');
    const body = {
        title:    document.getElementById('exam-create-title').value.trim(),
        course:   document.getElementById('exam-create-course').value.trim() || null,
        date:     document.getElementById('exam-create-date').value,
        time:     document.getElementById('exam-create-time').value,
        timezone: (() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone; } catch(_) { return 'UTC'; } })(),
        location: document.getElementById('exam-create-location').value.trim() || null,
        material: document.getElementById('exam-create-material').value.trim() || null,
        notes:    document.getElementById('exam-create-notes').value.trim() || null,
    };

    if (!body.title) { _showExamErr(errEl, 'Title is required.'); return; }
    if (!body.date)  { _showExamErr(errEl, 'Date is required.');  return; }
    if (!body.time)  { _showExamErr(errEl, 'Time is required.');  return; }

    try {
        const res = await fetch('/api/exams', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            _showExamErr(errEl, err.error || 'Failed to create exam.');
            return;
        }
    } catch (_) { _showExamErr(errEl, 'Could not reach the server.'); return; }

    hideExamCreateForm();
    if (typeof window.showToast === 'function') window.showToast('Exam created');
    await fetchExams();
}
window.submitExamCreateForm = submitExamCreateForm;

// ── Formatting helpers ─────────────────────────────────────────

/**
 * Exam datetime → display components. dateTime is an ISO instant string;
 * formatting uses the browser timezone so what the user sees matches
 * what they entered on the manual form.
 *
 *   dateBadge    → "APR 27"
 *   time         → "10:00 AM"
 *   fullDatetime → "Monday, April 27, 2026 at 10:00 AM"
 */
function _formatExamDate(isoInstant) {
    if (!isoInstant) return { monthAbbr: '—', day: '—', time: '', fullDatetime: '' };
    const d = new Date(isoInstant);
    const monthAbbrs = ['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC'];
    const pad = n => String(n).padStart(2, '0');

    let time = '';
    try {
        time = new Intl.DateTimeFormat(undefined, {
            hour: 'numeric', minute: '2-digit'
        }).format(d);
    } catch (_) {
        time = pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    let fullDatetime = '';
    try {
        fullDatetime = new Intl.DateTimeFormat(undefined, {
            weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
            hour: 'numeric', minute: '2-digit',
        }).format(d);
    } catch (_) { fullDatetime = d.toString(); }

    return {
        monthAbbr:    monthAbbrs[d.getMonth()],
        day:          String(d.getDate()),
        time,
        fullDatetime,
    };
}

function _formatRelative(daysUntil, isPast) {
    if (daysUntil == null) return '';
    if (isPast) {
        const n = Math.abs(daysUntil);
        if (n === 0) return 'Today';
        if (n === 1) return 'Yesterday';
        return n + ' days ago';
    }
    if (daysUntil === 0) return 'Today';
    if (daysUntil === 1) return 'Tomorrow';
    if (daysUntil > 1)   return 'In ' + daysUntil + ' days';
    // daysUntil < 0 on upcoming filter shouldn't happen, but be defensive.
    return Math.abs(daysUntil) + ' days ago';
}

function _todayIso() {
    const d = new Date();
    const pad = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function _showExamErr(el, msg) {
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
    setTimeout(() => el.classList.add('hidden'), 5000);
}

function _esc(s) {
    return (window.SS && window.SS.escapeHtml) ? window.SS.escapeHtml(s) : String(s || '');
}
function _escAttr(s) { return _esc(s); }

// ── Kick off ───────────────────────────────────────────────────
window.authReady = initExams();
