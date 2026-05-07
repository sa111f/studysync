'use strict';
/**
 * ai-task.js — Shared "Add Task with AI" modal (Phase 5.7).
 *
 * Loaded on both / (homepage dashboard) and /tasks. The modal markup is
 * a single Thymeleaf fragment (templates/fragments/ai-modal.html) so
 * there's one set of IDs for this script to bind against.
 *
 * State machine:
 *     input → loading → confirm        (happy path)
 *     input → loading → input          (400 / 429)
 *     input → loading → fallback       (503 / 504 / 502)
 *     confirm / fallback → saved       (closes modal, refreshes host)
 *     * → input                         (back button)
 *
 * Confirm and fallback share one set of ai-field-* inputs — only the
 * pre-form messaging differs (echo vs "couldn't parse" notice).
 */

// ── Module state ─────────────────────────────────────────────
let _aiLastTrigger  = null;   // element that opened the modal — focus is returned here on close
let _aiRawInput     = '';     // last submitted prompt; retained when user clicks "Try again"
let _aiScrollY      = 0;      // body scrollTop at open time — restored on close
let _aiKeydownBound = false;  // guard: bind keyboard listener once

const AI_STATES = ['input', 'loading', 'confirm', 'fallback'];

// ── Public API (called from inline onclick handlers) ─────────

function openAiModal(triggerEl) {
    const modal = document.getElementById('ai-task-modal');
    if (!modal) return;

    _aiLastTrigger = triggerEl instanceof Element
        ? triggerEl
        : (document.activeElement instanceof HTMLElement ? document.activeElement : null);

    // Reset to the input pane + clear any prior state / errors.
    _resetModalFields();
    _aiSetState('input');

    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');

    // Freeze body scroll so the overlay feels fixed. Preserve offset to
    // restore on close — avoids the jump-to-top issue with position: fixed.
    _aiScrollY = window.scrollY || document.documentElement.scrollTop || 0;
    document.body.style.top      = '-' + _aiScrollY + 'px';
    document.body.classList.add('ai-body-locked');

    if (!_aiKeydownBound) {
        document.addEventListener('keydown', _aiKeydownHandler);
        _aiKeydownBound = true;
    }

    // Focus the textarea so the user can start typing immediately.
    setTimeout(() => {
        const input = document.getElementById('ai-input');
        if (input) input.focus();
    }, 0);

    _aiUpdateCharCount();
}
window.openAiModal = openAiModal;

function closeAiModal() {
    const modal = document.getElementById('ai-task-modal');
    if (!modal) return;
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');

    document.body.classList.remove('ai-body-locked');
    document.body.style.top = '';
    window.scrollTo(0, _aiScrollY);

    if (_aiKeydownBound) {
        document.removeEventListener('keydown', _aiKeydownHandler);
        _aiKeydownBound = false;
    }

    // Return focus to whatever opened the modal, for screen-reader users.
    if (_aiLastTrigger && typeof _aiLastTrigger.focus === 'function') {
        try { _aiLastTrigger.focus(); } catch (_) { /* noop */ }
    }
}
window.closeAiModal = closeAiModal;

function backToAiInput() {
    _aiSetState('input');
    _aiClearError('ai-error');
    _aiClearError('ai-confirm-error');
    // Keep textarea value so the user can tweak + re-submit without retyping.
    const input = document.getElementById('ai-input');
    if (input) {
        if (_aiRawInput && !input.value) input.value = _aiRawInput;
        input.focus();
        _aiUpdateCharCount();
    }
}
window.backToAiInput = backToAiInput;

async function submitAiParse() {
    const input = document.getElementById('ai-input');
    const raw   = input ? input.value.trim() : '';

    if (!raw) {
        _aiShowError('ai-error', 'Please enter a short description.');
        return;
    }
    if (raw.length > 500) {
        _aiShowError('ai-error', 'Description is too long (max 500 characters).');
        return;
    }

    _aiRawInput = raw;
    _aiClearError('ai-error');
    _aiSetState('loading');

    let tz = 'UTC';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) {}

    let res;
    try {
        res = await fetch('/api/ai/parse-task', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ input: raw, timezone: tz }),
        });
    } catch (_) {
        // Network / CORS / DNS — treat the same as a 503 fallback.
        _enterFallback('Could not reach the server.');
        return;
    }

    // Parse the body even for non-2xx — the project's standard error shape
    // is {"error": "..."} and we want to surface the server's message.
    let data = {};
    try { data = await res.json(); } catch (_) { /* empty body */ }

    if (res.ok) {
        _populateConfirmFromParsed(data.parsed || {}, data.rawInput || raw);
        _aiSetState('confirm');
        return;
    }

    switch (res.status) {
        case 400:
            _aiSetState('input');
            _aiShowError('ai-error', data.error || 'Invalid input.');
            return;
        case 401:
            // Session expired while the modal was open — bounce to home
            // so the user can log back in; there's no inline way to recover.
            window.location.href = '/';
            return;
        case 429:
            _aiSetState('input');
            _aiShowError('ai-error',
                data.error || 'Too many AI requests — try again in an hour.');
            return;
        case 502:
            _enterFallback('AI returned something we couldn’t understand.');
            return;
        case 503:
            _enterFallback('AI is currently unavailable.');
            return;
        case 504:
            _enterFallback('AI took too long to respond.');
            return;
        default:
            _enterFallback(data.error || 'Unexpected AI error.');
    }
}
window.submitAiParse = submitAiParse;

async function saveAiTask() {
    const title    = _aiFieldVal('ai-field-title');
    const dueDate  = _aiFieldVal('ai-field-duedate');
    const course   = _aiFieldVal('ai-field-course') || null;
    const type     = _aiFieldVal('ai-field-type');
    const priority = _aiFieldVal('ai-field-priority');

    if (!title)   { _aiShowError('ai-confirm-error', 'Title is required.');    return; }
    if (!dueDate) { _aiShowError('ai-confirm-error', 'Due date is required.'); return; }

    _aiClearError('ai-confirm-error');
    _setConfirmSaving(true);

    try {
        const res = await fetch('/api/tasks', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({
                title, dueDate,
                course: course || null,
                type, priority,
                // status defaults to NOT_STARTED server-side; don't send it.
            }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            _aiShowError('ai-confirm-error', err.error || 'Could not save the task.');
            _setConfirmSaving(false);
            return;
        }
    } catch (_) {
        _aiShowError('ai-confirm-error', 'Could not reach the server.');
        _setConfirmSaving(false);
        return;
    }

    _setConfirmSaving(false);
    closeAiModal();
    _showToast('Task created');

    // Refresh whichever host is active. Both globals are optional; the
    // modal script is host-agnostic and silently no-ops if absent.
    if (typeof window.refreshDashboard === 'function') window.refreshDashboard();
    if (typeof window.fetchTasks === 'function' && typeof window.currentFilter === 'string') {
        window.fetchTasks(window.currentFilter);
    } else if (typeof window.fetchTasks === 'function') {
        window.fetchTasks('all');
    }
}
window.saveAiTask = saveAiTask;

// ── Internal helpers ─────────────────────────────────────────

function _aiSetState(name) {
    AI_STATES.forEach(s => {
        const el = document.getElementById('ai-state-' + s);
        if (el) el.classList.toggle('hidden', s !== name);
    });

    // The confirm + fallback panes share one DOM block. Toggle the
    // "couldn't parse" banner + echo line based on which state we're in.
    const fallbackMsg = document.getElementById('ai-fallback-msg');
    const echo        = document.getElementById('ai-raw-echo');
    if (name === 'fallback') {
        if (fallbackMsg) fallbackMsg.classList.remove('hidden');
        if (echo)        echo.classList.add('hidden');
        // Reveal the shared form by un-hiding the confirm pane.
        const confirmPane = document.getElementById('ai-state-confirm');
        if (confirmPane) confirmPane.classList.remove('hidden');
    } else if (name === 'confirm') {
        if (fallbackMsg) fallbackMsg.classList.add('hidden');
        if (echo)        echo.classList.remove('hidden');
    }

    // Disable Parse + Cancel while loading so a second click can't fire
    // a duplicate request.
    const parseBtn = document.getElementById('ai-parse-btn');
    if (parseBtn) parseBtn.disabled = (name === 'loading');
}

function _enterFallback(reason) {
    _aiSetState('fallback');
    _clearConfirmFields();
    const reasonEl = document.getElementById('ai-fallback-reason');
    if (reasonEl) reasonEl.textContent = reason;
}

function _populateConfirmFromParsed(parsed, rawInput) {
    const esc = (window.SS && window.SS.escapeHtml)
        ? window.SS.escapeHtml
        : (s => String(s || ''));

    // Echo the raw input the server sent back (do NOT trust local state:
    // the server could have trimmed / sanitized).
    const echoEl = document.getElementById('ai-raw-echo-text');
    if (echoEl) echoEl.textContent = rawInput || '';

    _setFieldValue('ai-field-title',    parsed.title    || '');
    _setFieldValue('ai-field-duedate',  parsed.dueDate  || '');
    _setFieldValue('ai-field-course',   parsed.course   || '');
    _setFieldValue('ai-field-type',     parsed.type     || 'OTHER');
    _setFieldValue('ai-field-priority', parsed.priority || 'MEDIUM');

    const human = document.getElementById('ai-date-human');
    if (human) human.textContent = parsed.resolvedDateHuman || _humanizeIsoDate(parsed.dueDate);

    _aiClearError('ai-confirm-error');
}

function _clearConfirmFields() {
    _setFieldValue('ai-field-title',   '');
    _setFieldValue('ai-field-duedate', '');
    _setFieldValue('ai-field-course',  '');
    _setFieldValue('ai-field-type',    'OTHER');
    _setFieldValue('ai-field-priority','MEDIUM');
    const human = document.getElementById('ai-date-human');
    if (human) human.textContent = '';
}

function _resetModalFields() {
    const input = document.getElementById('ai-input');
    if (input && _aiRawInput) input.value = _aiRawInput;
    else if (input) input.value = '';
    _aiClearError('ai-error');
    _aiClearError('ai-confirm-error');
    _clearConfirmFields();
    _setConfirmSaving(false);
    _aiUpdateCharCount();
}

function _aiFieldVal(id) {
    const el = document.getElementById(id);
    return el ? (el.value || '').trim() : '';
}
function _setFieldValue(id, v) {
    const el = document.getElementById(id);
    if (el) el.value = v;
}

function _aiShowError(id, msg) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
}
function _aiClearError(id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = '';
    el.classList.add('hidden');
}

function _setConfirmSaving(saving) {
    const btn = document.getElementById('ai-save-btn');
    if (btn) {
        btn.disabled    = saving;
        btn.textContent = saving ? 'Saving…' : 'Save Task';
    }
}

function _aiUpdateCharCount() {
    const input = document.getElementById('ai-input');
    const count = document.getElementById('ai-char-count');
    if (input && count) count.textContent = String((input.value || '').length);
}

function _humanizeIsoDate(iso) {
    if (!iso) return '';
    const [y, m, d] = iso.split('-').map(Number);
    if (!y || !m || !d) return '';
    try {
        return new Intl.DateTimeFormat(undefined, {
            weekday: 'long', month: 'long', day: 'numeric', year: 'numeric'
        }).format(new Date(y, m - 1, d));
    } catch (_) { return iso; }
}

// ── Keyboard shortcuts ───────────────────────────────────────

function _aiKeydownHandler(e) {
    const modal = document.getElementById('ai-task-modal');
    if (!modal || modal.classList.contains('hidden')) return;

    if (e.key === 'Escape') {
        e.preventDefault();
        closeAiModal();
        return;
    }

    // Cmd/Ctrl+Enter: submit whichever pane we're on.
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        const loading = document.getElementById('ai-state-loading');
        if (loading && !loading.classList.contains('hidden')) return;

        const input = document.getElementById('ai-state-input');
        if (input && !input.classList.contains('hidden')) {
            submitAiParse();
        } else {
            saveAiTask();
        }
        return;
    }

    // Simple focus trap on Tab — keeps focus inside the dialog.
    if (e.key === 'Tab') _aiTrapFocus(modal, e);
}

function _aiTrapFocus(modal, e) {
    const focusables = modal.querySelectorAll(
        'button, [href], input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    const visible = Array.from(focusables).filter(el => {
        if (el.disabled) return false;
        // Skip elements inside a hidden state-panel ancestor.
        let p = el.closest('.hidden');
        return !p || !modal.contains(p);
    });
    if (visible.length === 0) return;

    const first = visible[0];
    const last  = visible[visible.length - 1];
    if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
    }
}

// ── Live bindings ────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('ai-input');
    if (input) {
        input.addEventListener('input', _aiUpdateCharCount);
    }
    const dueEl = document.getElementById('ai-field-duedate');
    if (dueEl) {
        dueEl.addEventListener('change', () => {
            const human = document.getElementById('ai-date-human');
            if (human) human.textContent = _humanizeIsoDate(dueEl.value);
        });
    }
    // Overlay-click (not the dialog box itself) closes the modal.
    const modal = document.getElementById('ai-task-modal');
    if (modal) {
        modal.addEventListener('click', e => {
            if (e.target === modal) closeAiModal();
        });
    }
});

// ── Toast helper (shared — small enough to live here) ────────

/** Show a transient bottom-right toast for 2.5s. Idempotent — safe to spam. */
function _showToast(msg) {
    const container = _ensureToastContainer();
    const toast = document.createElement('div');
    toast.className   = 'toast toast-enter';
    toast.setAttribute('role', 'status');
    toast.textContent = msg;
    container.appendChild(toast);

    requestAnimationFrame(() => toast.classList.remove('toast-enter'));

    setTimeout(() => {
        toast.classList.add('toast-exit');
        setTimeout(() => toast.remove(), 220);
    }, 2500);
}
window.showToast = _showToast;

function _ensureToastContainer() {
    let c = document.getElementById('toast-container');
    if (!c) {
        c = document.createElement('div');
        c.id = 'toast-container';
        c.className = 'toast-container';
        document.body.appendChild(c);
    }
    return c;
}
