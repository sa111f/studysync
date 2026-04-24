'use strict';

// ══════════════════════════════════════════════════════════════
//  StudySync — Deadline-Aware Study Priority
//  Manages deadline CRUD, urgency computation, and the
//  "What to Study Next" recommendation engine.
//
//  Storage strategy:
//    Logged-in users → backend REST API (/api/deadlines)
//    Guests          → localStorage ('ss_deadlines_v1')
// ══════════════════════════════════════════════════════════════

const DEADLINE_LS_KEY = 'ss_deadlines_v1';

// ── State ──────────────────────────────────────────────────────
let _deadlines   = [];   // cached array of deadline objects (rich, with urgency)
let _dlLoggedIn  = false;
let _editingId   = null; // null = add mode, number = edit mode
let _activeFilter = 'all';

// ── Boot ───────────────────────────────────────────────────────

async function initDeadlines() {
    // Chain off auth.js — `window.authReady` resolves once /api/auth/me
    // has been probed and `window.currentUser` is settled. We re-use that
    // state instead of firing a second /api/auth/me round-trip.
    // On /tracker the auth state is owned by tracker.js, which sets
    // `window.currentUser` before calling initDeadlines.
    if (window.authReady && typeof window.authReady.then === 'function') {
        try { await window.authReady; } catch (_) {}
    }
    _dlLoggedIn = !!window.currentUser;
    await loadDeadlines();
}

// ── Data layer ─────────────────────────────────────────────────

async function loadDeadlines() {
    if (_dlLoggedIn) {
        try {
            const res  = await fetch('/api/deadlines');
            if (res.ok) {
                _deadlines = await res.json();
                // Sort by priority descending (most urgent first)
                _deadlines.sort((a, b) => b.priorityScore - a.priorityScore);
            }
        } catch (_) {
            _deadlines = [];
        }
    } else {
        _deadlines = _lsLoad();
        // Recompute client-side urgency for guest deadlines
        _deadlines = _deadlines.map(d => ({ ...d, ..._computeUrgency(d.dueDate) }));
        _deadlines.sort((a, b) => b.priorityScore - a.priorityScore);
    }

    renderRecommendation();
    renderDeadlineList();
    renderPriorityWidget(); // index.html compact widget (no-op if element absent)
}

/** Save a deadline (create or update). */
async function saveDeadline() {
    const title   = qs('#dl-title').value.trim();
    const dueDate = qs('#dl-due-date').value;
    const notes   = (qs('#dl-notes').value || '').trim();

    // Validate
    if (!title) { showFieldError('dl-title-err', 'Title is required.'); return; }
    clearFieldError('dl-title-err');
    if (!dueDate) { showFieldError('dl-date-err', 'Due date is required.'); return; }
    clearFieldError('dl-date-err');

    const payload = { title, subject: '', type: 'other', dueDate, dueTime: null, notes: notes || null };

    if (_dlLoggedIn) {
        try {
            const url    = _editingId ? `/api/deadlines/${_editingId}` : '/api/deadlines';
            const method = _editingId ? 'PUT' : 'POST';
            const res    = await fetch(url, {
                method,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                showFormError(err.error || 'Failed to save deadline.');
                return;
            }
        } catch (_) {
            showFormError('Network error. Please try again.');
            return;
        }
    } else {
        // Guest: localStorage
        if (_editingId) {
            _deadlines = _deadlines.map(d =>
                d.id === _editingId ? { ...d, ...payload, ..._computeUrgency(dueDate) } : d
            );
        } else {
            const newItem = {
                id: Date.now(),
                ...payload,
                ..._computeUrgency(dueDate),
                createdAt: new Date().toISOString(),
            };
            _deadlines.push(newItem);
        }
        _lsSave(_deadlines);
    }

    resetForm();
    collapseForm();
    await loadDeadlines();
}

async function deleteDeadline(id) {
    if (_dlLoggedIn) {
        try {
            const res = await fetch(`/api/deadlines/${id}`, { method: 'DELETE' });
            if (!res.ok) return;
        } catch (_) { return; }
    } else {
        _deadlines = _deadlines.filter(d => d.id !== id);
        _lsSave(_deadlines);
    }
    if (_editingId === id) { resetForm(); collapseForm(); }
    await loadDeadlines();
}

function startEdit(id) {
    const d = _deadlines.find(x => x.id === id);
    if (!d) return;
    _editingId = id;

    qs('#dl-title').value    = d.title   || '';
    qs('#dl-due-date').value = d.dueDate || '';
    qs('#dl-notes').value    = d.notes   || '';

    updateFormMode();
    expandForm();
    qs('#dl-title').focus();
}

function cancelEdit() {
    resetForm();
    collapseForm();
}

// ── Urgency (client-side, for guests) ─────────────────────────

/**
 * Compute urgency and priority score from a due-date string "YYYY-MM-DD".
 * Mirrors the server-side logic in DeadlineService.java exactly.
 *
 *   overdue   → days < 0   → score 10000 + |days|*200
 *   today     → days == 0  → score 9000
 *   tomorrow  → days == 1  → score 8000
 *   soon      → days 2-3   → score 7800 / 7600
 *   upcoming  → days 4-7   → score 4600..5000
 *   later     → days >= 8  → score 1..992
 */
function _computeUrgency(dueDateStr) {
    const today = _todayMidnight();
    const due   = new Date(dueDateStr + 'T00:00:00');
    const days  = Math.round((due - today) / 86_400_000);

    let urgency, priorityScore;

    if (days < 0) {
        urgency       = 'overdue';
        priorityScore = 10_000 + Math.abs(days) * 200;
    } else if (days === 0) {
        urgency       = 'today';
        priorityScore = 9_000;
    } else if (days === 1) {
        urgency       = 'tomorrow';
        priorityScore = 8_000;
    } else if (days <= 3) {
        urgency       = 'soon';
        priorityScore = 7_800 - (days - 2) * 200;
    } else if (days <= 7) {
        urgency       = 'upcoming';
        priorityScore = 5_000 - (days - 4) * 100;
    } else {
        urgency       = 'later';
        priorityScore = Math.max(1, 1_000 - Math.min(days, 999));
    }

    return { urgency, priorityScore, daysRemaining: days };
}

// ── Render: recommendation card ───────────────────────────────

/**
 * "What to Study Next" — picks the highest-priority deadline and
 * generates a concise, natural-language recommendation.
 */
function renderRecommendation() {
    const el = qs('#dl-recommendation');
    if (!el) return;

    if (_deadlines.length === 0) {
        el.innerHTML = `
            <div class="dl-rec-empty">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
                     stroke-linecap="round" stroke-linejoin="round">
                    <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/>
                    <rect x="9" y="3" width="6" height="4" rx="2"/>
                    <line x1="9" y1="12" x2="15" y2="12"/>
                    <line x1="9" y1="16" x2="12" y2="16"/>
                </svg>
                <span>No upcoming deadlines — add one to get a study priority recommendation.</span>
            </div>`;
        return;
    }

    const top = _deadlines[0]; // already sorted by priority desc
    const msg = _buildRecommendationText(top);
    const urgencyClass = `dl-rec-urgency-${top.urgency}`;

    el.innerHTML = `
        <div class="dl-rec-content">
            <div class="dl-rec-label">What to Study Next</div>
            <div class="dl-rec-message ${urgencyClass}">${esc(msg)}</div>
            <div class="dl-rec-meta">
                ${top.subject ? `<span class="dl-rec-subject">${esc(top.subject)}</span>` : ''}
                <span class="dl-urgency-badge dl-urgency-${top.urgency}">${urgencyLabel(top)}</span>
            </div>
        </div>
        <div class="dl-rec-icon ${urgencyClass}">
            ${urgencyIcon(top.urgency)}
        </div>`;
}

function _buildRecommendationText(d) {
    const typeLabel = d.type && d.type !== 'other' ? _capitalize(d.type) : 'task';
    const subj      = d.subject ? `${d.subject} — ` : '';
    const days      = d.daysRemaining;

    if (d.urgency === 'overdue') {
        const n = Math.abs(days);
        return `${subj}${d.title} was due ${n === 1 ? 'yesterday' : `${n} days ago`} — address this now`;
    }
    if (d.urgency === 'today') {
        const timeStr = d.dueTime ? ` at ${_fmtTime(d.dueTime)}` : '';
        return `Focus on ${subj}${d.title}${timeStr} — ${typeLabel} due today`;
    }
    if (d.urgency === 'tomorrow') {
        return `Prioritize ${subj}${d.title} — ${typeLabel} due tomorrow`;
    }
    if (d.urgency === 'soon') {
        return `Study ${subj}${d.title} — ${typeLabel} in ${days} days`;
    }
    if (d.urgency === 'upcoming') {
        return `Plan for ${subj}${d.title} — ${typeLabel} in ${days} days`;
    }
    return `Upcoming: ${subj}${d.title} — ${typeLabel} on ${_fmtDate(d.dueDate)}`;
}

// ── Render: deadline list ──────────────────────────────────────

function renderDeadlineList() {
    const container = qs('#dl-list');
    if (!container) return;

    const filtered = _activeFilter === 'all'   ? _deadlines
                   : _activeFilter === 'past'  ? _deadlines.filter(d => d.urgency === 'overdue')
                   : _activeFilter === 'still' ? _deadlines.filter(d => d.urgency !== 'overdue')
                   : _deadlines;

    if (_deadlines.length === 0) {
        container.innerHTML = `
            <div class="dl-empty-state">
                <div class="dl-empty-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
                         stroke-linecap="round" stroke-linejoin="round">
                        <rect x="3" y="4" width="18" height="18" rx="2"/>
                        <line x1="16" y1="2" x2="16" y2="6"/>
                        <line x1="8"  y1="2" x2="8"  y2="6"/>
                        <line x1="3"  y1="10" x2="21" y2="10"/>
                        <line x1="8"  y1="14" x2="8"  y2="14"/>
                        <line x1="12" y1="14" x2="12" y2="14"/>
                        <line x1="16" y1="14" x2="16" y2="14"/>
                    </svg>
                </div>
                <p>No deadlines yet.<br>Add your first one to get started.</p>
            </div>`;
        return;
    }

    if (filtered.length === 0) {
        container.innerHTML = `<div class="dl-empty-state"><p>No deadlines in this category.</p></div>`;
        return;
    }

    container.innerHTML = filtered.map(d => _renderDeadlineItem(d)).join('');
}

function _renderDeadlineItem(d) {
    const isPast  = d.urgency === 'overdue';
    const editing = _editingId === d.id;

    return `
    <div class="dl-item dl-item-${d.urgency}${editing ? ' dl-item-editing' : ''}" data-id="${d.id}">
        <div class="dl-item-urgency-bar"></div>
        <div class="dl-item-body">
            <div class="dl-item-top">
                <div class="dl-item-info">
                    <span class="dl-item-title">${esc(d.title)}</span>
                    <span class="dl-item-date">${_fmtDate(d.dueDate)}</span>
                </div>
                <div class="dl-item-actions">
                    <span class="dl-state-badge dl-state-${isPast ? 'past' : 'still'}">${isPast ? 'Past' : 'Upcoming'}</span>
                    <button class="dl-action-btn dl-edit-btn" onclick="startEdit(${d.id})"
                            title="Edit" aria-label="Edit deadline">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                             stroke-linecap="round" stroke-linejoin="round">
                            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                        </svg>
                    </button>
                    <button class="dl-action-btn dl-delete-btn" onclick="confirmDeleteDeadline(${d.id})"
                            title="Delete" aria-label="Delete deadline">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                             stroke-linecap="round" stroke-linejoin="round">
                            <polyline points="3 6 5 6 21 6"/>
                            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                            <path d="M10 11v6"/>
                            <path d="M14 11v6"/>
                            <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                        </svg>
                    </button>
                </div>
            </div>
            ${d.notes ? `<div class="dl-item-notes">${esc(d.notes)}</div>` : ''}
        </div>
    </div>`;
}

// ── Render: compact priority widget (index.html) ───────────────

function renderPriorityWidget() {
    const widget = qs('#priority-widget');
    if (!widget) return;

    if (_deadlines.length === 0) {
        widget.innerHTML = `
            <div class="pw-empty">
                <span class="pw-empty-icon">📋</span>
                <span>Add deadlines in the <a href="/tracker" class="pw-link">Study Tracker</a> to get priority recommendations.</span>
            </div>`;
        return;
    }

    const top  = _deadlines[0];
    const msg  = _buildRecommendationText(top);
    const next = _deadlines[1] || null;

    widget.innerHTML = `
        <div class="pw-header">
            <span class="pw-label">Study Priority</span>
            <a href="/tracker" class="pw-all-link">Manage deadlines →</a>
        </div>
        <div class="pw-top dl-rec-urgency-${top.urgency}">
            <div class="pw-top-icon">${urgencyIcon(top.urgency)}</div>
            <div class="pw-top-text">
                <div class="pw-top-msg">${esc(msg)}</div>
                <span class="dl-urgency-badge dl-urgency-${top.urgency}">${urgencyLabel(top)}</span>
            </div>
        </div>
        ${next ? `<div class="pw-next">Up next: <strong>${esc(next.title)}</strong>
            ${next.subject ? `(${esc(next.subject)})` : ''} — ${_daysLabel(next)}</div>` : ''}`;
}

// ── Form helpers ───────────────────────────────────────────────

function expandForm() {
    const form = qs('#dl-form-panel');
    if (form) form.classList.remove('hidden');
    const btn = qs('#dl-add-btn');
    if (btn) btn.classList.add('hidden');
    updateFormMode();
}

function collapseForm() {
    const form = qs('#dl-form-panel');
    if (form) form.classList.add('hidden');
    const btn = qs('#dl-add-btn');
    if (btn) btn.classList.remove('hidden');
}

function updateFormMode() {
    const title  = qs('#dl-form-title');
    const submit = qs('#dl-form-submit');
    if (_editingId) {
        if (title)  title.textContent  = 'Edit Deadline';
        if (submit) submit.textContent = 'Save Changes';
    } else {
        if (title)  title.textContent  = 'Add Deadline';
        if (submit) submit.textContent = 'Add Deadline';
    }
}

function resetForm() {
    _editingId = null;
    const form = qs('#dl-form');
    if (form) form.reset();
    clearFieldError('dl-title-err');
    clearFieldError('dl-date-err');
    clearFormError();
    updateFormMode();
}

function setFilter(filter) {
    _activeFilter = filter;
    document.querySelectorAll('.dl-filter-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.filter === filter));
    renderDeadlineList();
}

function confirmDeleteDeadline(id) {
    const d = _deadlines.find(x => x.id === id);
    if (!d) return;
    // Use the app's own toast / simple confirm
    if (window.confirm(`Delete "${d.title}"?`)) {
        deleteDeadline(id);
    }
}

// ── UI helpers ─────────────────────────────────────────────────

function urgencyLabel(d) {
    const days = d.daysRemaining;
    switch (d.urgency) {
        case 'overdue':  return `${Math.abs(days)}d overdue`;
        case 'today':    return 'Due today';
        case 'tomorrow': return 'Due tomorrow';
        case 'soon':     return `${days}d left`;
        case 'upcoming': return `${days}d left`;
        case 'later':    return `${days}d left`;
        default:         return '';
    }
}

function urgencyIcon(urgency) {
    switch (urgency) {
        case 'overdue':  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`;
        case 'today':    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`;
        case 'tomorrow': return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>`;
        case 'soon':     return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`;
        case 'upcoming': return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`;
        default:         return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/></svg>`;
    }
}

function _daysLabel(d) {
    const days = d.daysRemaining;
    if (days < 0)  return `${Math.abs(days)}d overdue`;
    if (days === 0) return 'Due today';
    if (days === 1) return 'Tomorrow';
    return `In ${days} days`;
}

function _fmtDate(iso) {
    // "2025-05-15" → "May 15"
    try {
        const [, m, dd] = iso.split('-').map(Number);
        const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
        return `${months[m - 1]} ${dd}`;
    } catch (_) { return iso; }
}

function _fmtTime(t) {
    // "14:00" → "2:00 PM"
    try {
        const [h, min] = t.split(':').map(Number);
        const ampm = h >= 12 ? 'PM' : 'AM';
        const hr   = h % 12 || 12;
        return `${hr}:${String(min).padStart(2, '0')} ${ampm}`;
    } catch (_) { return t; }
}

function _capitalize(s) {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : '';
}

function _todayMidnight() {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d;
}

// ── Form error helpers ─────────────────────────────────────────

function showFieldError(id, msg) {
    const el = qs(`#${id}`);
    if (el) { el.textContent = msg; el.classList.remove('hidden'); }
}

function clearFieldError(id) {
    const el = qs(`#${id}`);
    if (el) { el.textContent = ''; el.classList.add('hidden'); }
}

function showFormError(msg) {
    const el = qs('#dl-form-error');
    if (el) { el.textContent = msg; el.classList.remove('hidden'); }
}

function clearFormError() {
    const el = qs('#dl-form-error');
    if (el) { el.textContent = ''; el.classList.add('hidden'); }
}

// ── localStorage helpers ───────────────────────────────────────

function _lsLoad() {
    try { return JSON.parse(localStorage.getItem(DEADLINE_LS_KEY) || '[]'); }
    catch (_) { return []; }
}

function _lsSave(data) {
    try { localStorage.setItem(DEADLINE_LS_KEY, JSON.stringify(data)); }
    catch (_) {}
}

// ── Utility ────────────────────────────────────────────────────

function qs(sel) { return document.querySelector(sel); }

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
