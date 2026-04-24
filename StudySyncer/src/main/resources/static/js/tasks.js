'use strict';

// ══════════════════════════════════════════════════════════════
//  tasks.js — /tasks page UI layer.
//
//  Renders task list, create form, inline edit form, filter tabs,
//  and wires them to /api/tasks CRUD endpoints.
//
//  Auth:
//    window.currentUser is populated (or null) by initAuth() at load
//    time. When null we show a guest banner and hide the form + list.
//    window.authReady resolves once the /api/auth/me probe finishes
//    (exposed the same way tracker.js does it) so inline scripts can
//    chain off it if needed.
// ══════════════════════════════════════════════════════════════

// ── State ──────────────────────────────────────────────────────
let currentFilter = 'all';         // 'all' | 'active' | 'completed' | 'overdue'
let tasks         = [];            // cached TaskResponse[] from the last fetch
let editingTaskId = null;          // id of the task whose inline edit panel is open
let isLoggedIn    = false;

// ── Boot ───────────────────────────────────────────────────────
async function initTasks() {
    let username = null;
    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            username   = data.username || null;
            isLoggedIn = true;
        }
    } catch (_) { /* treat as guest */ }

    window.currentUser = isLoggedIn ? username : null;
    updateHeaderAuthBar(username);

    if (!isLoggedIn) {
        showGuestState();
        return;
    }

    showLoggedInState();
    await fetchTasks(currentFilter);

    // Phase 4.3 — dashboard row-body clicks route here with ?focus={id}.
    // After the list is rendered, scroll to that row, briefly pulse it,
    // then strip the query param so reloads don't re-trigger the pulse.
    const focusId = _readFocusParam();
    if (focusId != null) _highlightFocusedTask(focusId);
}

/** Read `?focus=123` from the current URL, or null if absent / non-numeric. */
function _readFocusParam() {
    try {
        const params = new URLSearchParams(window.location.search);
        const raw = params.get('focus');
        if (!raw) return null;
        const id = parseInt(raw, 10);
        return Number.isNaN(id) ? null : id;
    } catch (_) { return null; }
}

/**
 * Scroll-to + 1500ms pulse on a task row matched by data-id, then
 * remove the `focus` param so a browser reload doesn't re-pulse.
 * If the task isn't in the current filter's list, switches to 'all' and retries.
 */
async function _highlightFocusedTask(focusId) {
    let row = document.querySelector('.task-row[data-id="' + focusId + '"]');
    if (!row && currentFilter !== 'all') {
        // The task might be completed / not in the active filter — fall back
        // to All and re-render before giving up.
        currentFilter = 'all';
        document.querySelectorAll('.task-filter-btn').forEach(b =>
            b.classList.toggle('active', b.dataset.filter === 'all'));
        await fetchTasks('all');
        row = document.querySelector('.task-row[data-id="' + focusId + '"]');
    }
    if (!row) return;

    try { row.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
    catch (_) { /* older browsers */ }

    row.classList.add('task-row-focus-pulse');
    setTimeout(() => row.classList.remove('task-row-focus-pulse'), 1500);

    // Strip ?focus so refresh doesn't replay the pulse.
    try {
        const url = new URL(window.location.href);
        url.searchParams.delete('focus');
        window.history.replaceState({}, '', url.pathname + (url.search || ''));
    } catch (_) { /* history API unavailable */ }
}

// Sync header auth bar the same way tracker.js does.
function updateHeaderAuthBar(username) {
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

async function tasksLogout() {
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (_) {}
    window.location.href = '/';
}

function showLoggedInState() {
    document.getElementById('tasks-guest-banner')?.classList.add('hidden');
    document.getElementById('task-add-btn')?.classList.remove('hidden');
    document.querySelector('.task-filter-bar')?.classList.remove('hidden');
    document.getElementById('task-list')?.classList.remove('hidden');
}

function showGuestState() {
    document.getElementById('tasks-guest-banner')?.classList.remove('hidden');
    // Hide everything that requires an account.
    document.getElementById('task-add-btn')?.classList.add('hidden');
    document.getElementById('task-create-form')?.classList.add('hidden');
    document.querySelector('.task-filter-bar')?.classList.add('hidden');
    document.getElementById('task-list')?.classList.add('hidden');
}

// ── Fetch + render ─────────────────────────────────────────────
async function fetchTasks(filter) {
    if (!isLoggedIn) return;
    try {
        const res = await fetch('/api/tasks?status=' + encodeURIComponent(filter));
        if (res.status === 401) { window.location.href = '/'; return; }
        if (!res.ok) { tasks = []; renderList(); return; }
        tasks = await res.json();
    } catch (_) { tasks = []; }
    renderList();
}

function setFilter(filter) {
    if (filter === currentFilter) return;
    currentFilter = filter;
    editingTaskId = null;
    document.querySelectorAll('.task-filter-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.filter === filter));
    fetchTasks(filter);
}

function renderList() {
    const list = document.getElementById('task-list');
    if (!list) return;

    if (!tasks || tasks.length === 0) {
        list.innerHTML = renderEmptyState(currentFilter);
        return;
    }

    list.innerHTML = tasks.map(renderTaskItem).join('');
}

function renderEmptyState(filter) {
    let msg;
    switch (filter) {
        case 'active':
            msg = 'No active tasks. 🎉 Add a new one, or ' +
                  '<a href="#" onclick="setFilter(\'completed\'); return false;">see completed →</a>';
            break;
        case 'completed':
            msg = 'No completed tasks yet. Get something done!';
            break;
        case 'overdue':
            msg = 'Nothing overdue. Great job staying on top of it.';
            break;
        default:
            msg = 'No tasks yet. Click "+ Add Task" to create your first one.';
    }
    return `<div class="tasks-empty">${msg}</div>`;
}

function renderTaskItem(t) {
    const isEditing = editingTaskId === t.id;
    const isCompleted = t.status === 'COMPLETED';

    const priorityClass = (t.priority || 'MEDIUM').toLowerCase();
    const typeLabel     = (t.type || 'OTHER').charAt(0) + (t.type || 'OTHER').slice(1).toLowerCase();

    const metaParts = [];
    if (t.course)  metaParts.push(`<span>${esc(t.course)}</span>`);
    metaParts.push(`<span class="task-type-badge">${esc(typeLabel)}</span>`);
    metaParts.push(`<span>${esc(formatDueDate(t.dueDate, t.status))}</span>`);
    // Phase 3.5 — show time logged against the task. Omit entirely when 0
    // so rows for unstarted tasks stay clean.
    if (t.secondsLogged && t.secondsLogged > 0 && window.SS && window.SS.formatDuration) {
        metaParts.push(
            `<span class="task-logged-note">${esc(window.SS.formatDuration(t.secondsLogged))} logged</span>`
        );
    }

    const overdueBadge = (t.overdue) ? '<span class="task-overdue-badge">Overdue</span>' : '';

    return `
    <div class="task-row ${isEditing ? 'editing' : ''}" data-id="${t.id}"
         onclick="onRowClick(event, ${t.id})">
        <button class="task-checkbox" data-status="${esc(t.status)}"
                onclick="event.stopPropagation(); cycleStatus(${t.id})"
                aria-label="Cycle status"></button>

        <div class="task-main">
            <div class="task-title ${isCompleted ? 'completed' : ''}">${esc(t.title)}</div>
            <div class="task-meta">
                ${metaParts.join('<span class="sep">·</span>')}
                ${overdueBadge}
            </div>
        </div>

        <span class="task-priority-dot ${priorityClass}" aria-label="Priority: ${priorityClass}"></span>

        <div class="task-row-actions">
            <button class="task-icon-btn" title="Edit"
                    onclick="event.stopPropagation(); showEditForm(${t.id})" aria-label="Edit">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
            </button>
            <button class="task-icon-btn danger" title="Delete"
                    onclick="event.stopPropagation(); deleteTask(${t.id})" aria-label="Delete">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                    <path d="M10 11v6"/>
                    <path d="M14 11v6"/>
                </svg>
            </button>
        </div>
    </div>
    ${isEditing ? renderEditPanel(t) : ''}`;
}

function renderEditPanel(t) {
    return `
    <form class="task-edit-panel" data-id="${t.id}"
          onsubmit="event.preventDefault(); submitEditForm(${t.id})">
        <div class="task-form-row task-form-row-2">
            <div class="task-field">
                <label class="task-label" for="edit-title-${t.id}">Title <span class="task-required">*</span></label>
                <input type="text" id="edit-title-${t.id}" class="field-input task-input"
                       maxlength="200" value="${escAttr(t.title || '')}" />
            </div>
            <div class="task-field">
                <label class="task-label" for="edit-due-${t.id}">Due Date <span class="task-required">*</span></label>
                <input type="date" id="edit-due-${t.id}" class="field-input task-input"
                       value="${escAttr(t.dueDate || '')}" />
            </div>
        </div>

        <div class="task-form-row task-form-row-3">
            <div class="task-field">
                <label class="task-label" for="edit-course-${t.id}">Course</label>
                <input type="text" id="edit-course-${t.id}" class="field-input task-input"
                       maxlength="100" value="${escAttr(t.course || '')}" />
            </div>
            <div class="task-field">
                <label class="task-label" for="edit-type-${t.id}">Type</label>
                <select id="edit-type-${t.id}" class="field-input task-input">
                    ${['ASSIGNMENT','LAB','HOMEWORK','PROJECT','READING','OTHER']
                        .map(v => `<option value="${v}" ${t.type === v ? 'selected' : ''}>${labelFor(v)}</option>`).join('')}
                </select>
            </div>
            <div class="task-field">
                <label class="task-label" for="edit-priority-${t.id}">Priority</label>
                <select id="edit-priority-${t.id}" class="field-input task-input">
                    ${['LOW','MEDIUM','HIGH']
                        .map(v => `<option value="${v}" ${t.priority === v ? 'selected' : ''}>${labelFor(v)}</option>`).join('')}
                </select>
            </div>
        </div>

        <div class="task-field">
            <label class="task-label" for="edit-status-${t.id}">Status</label>
            <select id="edit-status-${t.id}" class="field-input task-input">
                ${['NOT_STARTED','IN_PROGRESS','COMPLETED']
                    .map(v => `<option value="${v}" ${t.status === v ? 'selected' : ''}>${labelFor(v)}</option>`).join('')}
            </select>
        </div>

        <div class="task-field">
            <label class="task-label" for="edit-notes-${t.id}">Notes <span class="task-optional">optional</span></label>
            <textarea id="edit-notes-${t.id}" class="field-input task-input task-textarea"
                      maxlength="2000" rows="2">${esc(t.notes || '')}</textarea>
        </div>

        <div id="edit-error-${t.id}" class="auth-error hidden"></div>

        <div class="task-form-actions">
            <button type="button" class="delete-btn"
                    onclick="deleteTask(${t.id})">Delete</button>
            <button type="button" class="apply-btn"
                    onclick="hideEditForm()">Cancel</button>
            <button type="submit" class="save-btn">Save</button>
        </div>
    </form>`;
}

// Row click expands inline edit; clicks on the checkbox / action icons
// propagate-stop before reaching this handler.
function onRowClick(event, id) {
    if (editingTaskId === id) return;
    showEditForm(id);
}

// ── Create form ────────────────────────────────────────────────
function showCreateForm() {
    editingTaskId = null;
    const form = document.getElementById('task-create-form');
    if (!form) return;
    form.classList.remove('hidden');
    document.getElementById('task-add-btn')?.classList.add('hidden');
    // Default due-date input to today so the user doesn't see a validation blocker.
    const dueEl = document.getElementById('task-create-due');
    if (dueEl && !dueEl.value) dueEl.value = todayIso();
    document.getElementById('task-create-title')?.focus();
}

function hideCreateForm() {
    const form = document.getElementById('task-create-form');
    if (!form) return;
    form.classList.add('hidden');
    form.reset();
    document.getElementById('task-create-error')?.classList.add('hidden');
    document.getElementById('task-add-btn')?.classList.remove('hidden');
}

async function submitCreateForm() {
    const titleEl    = document.getElementById('task-create-title');
    const dueEl      = document.getElementById('task-create-due');
    const courseEl   = document.getElementById('task-create-course');
    const typeEl     = document.getElementById('task-create-type');
    const priorityEl = document.getElementById('task-create-priority');
    const notesEl    = document.getElementById('task-create-notes');
    const errEl      = document.getElementById('task-create-error');

    const body = {
        title:    (titleEl.value || '').trim(),
        dueDate:  dueEl.value || null,
        course:   (courseEl.value || '').trim() || null,
        type:     typeEl.value,
        priority: priorityEl.value,
        notes:    (notesEl.value || '').trim() || null,
    };

    // Client-side minimal sanity — server is authoritative.
    if (!body.title)   { showInlineError(errEl, 'Title is required.');    return; }
    if (!body.dueDate) { showInlineError(errEl, 'Due date is required.'); return; }

    try {
        const res  = await fetch('/api/tasks', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body),
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) { showInlineError(errEl, data.error || 'Failed to create task.'); return; }
        hideCreateForm();
        await fetchTasks(currentFilter);
    } catch (_) {
        showInlineError(errEl, 'Could not reach server. Try again.');
    }
}

// ── Edit form ──────────────────────────────────────────────────
function showEditForm(taskId) {
    editingTaskId = taskId;
    renderList();
    // Scroll the panel into view so the user sees the editor under a deep-scroll list.
    setTimeout(() => {
        const panel = document.querySelector(`.task-edit-panel[data-id="${taskId}"]`);
        panel?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        document.getElementById('edit-title-' + taskId)?.focus();
    }, 0);
}

function hideEditForm() {
    editingTaskId = null;
    renderList();
}

async function submitEditForm(taskId) {
    const g = (id) => document.getElementById(id).value;
    const body = {
        title:    (g('edit-title-' + taskId) || '').trim(),
        dueDate:  g('edit-due-' + taskId) || null,
        course:   (g('edit-course-' + taskId) || '').trim() || null,
        type:     g('edit-type-' + taskId),
        priority: g('edit-priority-' + taskId),
        status:   g('edit-status-' + taskId),
        notes:    (g('edit-notes-' + taskId) || '').trim() || null,
    };

    const errEl = document.getElementById('edit-error-' + taskId);
    if (!body.title)   { showInlineError(errEl, 'Title is required.');    return; }
    if (!body.dueDate) { showInlineError(errEl, 'Due date is required.'); return; }

    try {
        const res  = await fetch('/api/tasks/' + taskId, {
            method:  'PUT',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body),
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) { showInlineError(errEl, data.error || 'Failed to save changes.'); return; }
        editingTaskId = null;
        await fetchTasks(currentFilter);
    } catch (_) {
        showInlineError(errEl, 'Could not reach server. Try again.');
    }
}

// ── Status cycling ─────────────────────────────────────────────
async function cycleStatus(taskId) {
    const t = tasks.find(x => x.id === taskId);
    if (!t) return;
    const next = nextStatus(t.status);
    try {
        const res = await fetch('/api/tasks/' + taskId + '/status', {
            method:  'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ status: next }),
        });
        if (!res.ok) return;  // silent — list will refresh anyway
        await fetchTasks(currentFilter);
    } catch (_) { /* silent */ }
}

function nextStatus(current) {
    if (current === 'NOT_STARTED') return 'IN_PROGRESS';
    if (current === 'IN_PROGRESS') return 'COMPLETED';
    return 'NOT_STARTED';
}

// ── Delete ─────────────────────────────────────────────────────
async function deleteTask(taskId) {
    if (!window.confirm("Delete this task? This can't be undone.")) return;
    try {
        const res = await fetch('/api/tasks/' + taskId, { method: 'DELETE' });
        if (res.ok || res.status === 204) {
            editingTaskId = null;
            await fetchTasks(currentFilter);
        }
    } catch (_) { /* silent */ }
}

// ── Formatting helpers ─────────────────────────────────────────

/**
 * Human-readable due-date phrase.
 *   - Today / Tomorrow / Yesterday
 *   - "In N days" / "N days ago" relative window (±6 days)
 *   - "Overdue by N days" when status != COMPLETED and the date is past
 *   - Falls back to "Apr 30" for further-out dates
 */
function formatDueDate(isoDate, status) {
    if (!isoDate) return '';
    const [y, m, d] = isoDate.split('-').map(Number);
    if (!y || !m || !d) return isoDate;

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const due   = new Date(y, m - 1, d);
    const days  = Math.round((due - today) / 86_400_000);

    if (days === 0)  return 'Today';
    if (days === 1)  return 'Tomorrow';
    if (days === -1) return status === 'COMPLETED' ? 'Yesterday' : 'Overdue by 1 day';

    if (days < 0) {
        if (status === 'COMPLETED') return shortDate(y, m, d);
        return `Overdue by ${-days} days`;
    }

    if (days <= 6) return `In ${days} days`;
    return shortDate(y, m, d);
}

function shortDate(y, m, d) {
    const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return `${months[m - 1]} ${d}`;
}

function labelFor(enumVal) {
    // Convert "NOT_STARTED" → "Not started"
    return enumVal
        .toLowerCase()
        .replace(/_/g, ' ')
        .replace(/^./, c => c.toUpperCase());
}

function todayIso() {
    const d = new Date();
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function showInlineError(el, msg) {
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
    setTimeout(() => el.classList.add('hidden'), 5000);
}

function esc(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
function escAttr(s) { return esc(s); }

// ── Kick off ───────────────────────────────────────────────────
// Expose a boot-complete promise on `window.authReady` so any inline
// script that awaits it behaves consistently with the other pages.
window.authReady = initTasks();
