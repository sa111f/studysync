'use strict';
/**
 * ai-exam.js — Shared "Add Exam with AI" modal (Phase 6.8).
 *
 * Cloned from ai-task.js per spec option A. All function names and IDs
 * are suffixed with `Exam` / `-exam` so both modals can live on the same
 * homepage without symbol collision.
 *
 * Differences vs. the task modal:
 *   - Endpoint: POST /api/ai/parse-exam
 *   - Confirmation form has date+time (two inputs), course, material,
 *     location — no type/priority.
 *   - Server response returns dateTime as an ISO instant with offset;
 *     the save path splits it back into date + time fields matching the
 *     manual form, then posts those to /api/exams as shape B (see
 *     ExamRequest docstring for the rationale).
 */

let _aiExamLastTrigger  = null;
let _aiExamRawInput     = '';
let _aiExamScrollY      = 0;
let _aiExamKeydownBound = false;

const AI_EXAM_STATES = ['input', 'loading', 'confirm', 'fallback'];

// ── Public API ───────────────────────────────────────────────

function openAiExamModal(triggerEl) {
    const modal = document.getElementById('ai-exam-modal');
    if (!modal) return;

    _aiExamLastTrigger = triggerEl instanceof Element
        ? triggerEl
        : (document.activeElement instanceof HTMLElement ? document.activeElement : null);

    _resetAiExamFields();
    _aiExamSetState('input');

    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');

    _aiExamScrollY = window.scrollY || document.documentElement.scrollTop || 0;
    document.body.style.top = '-' + _aiExamScrollY + 'px';
    document.body.classList.add('ai-body-locked');

    if (!_aiExamKeydownBound) {
        document.addEventListener('keydown', _aiExamKeydownHandler);
        _aiExamKeydownBound = true;
    }

    setTimeout(() => {
        const input = document.getElementById('ai-exam-input');
        if (input) input.focus();
    }, 0);

    _aiExamUpdateCharCount();
}
window.openAiExamModal = openAiExamModal;

function closeAiExamModal() {
    const modal = document.getElementById('ai-exam-modal');
    if (!modal) return;
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');

    document.body.classList.remove('ai-body-locked');
    document.body.style.top = '';
    window.scrollTo(0, _aiExamScrollY);

    if (_aiExamKeydownBound) {
        document.removeEventListener('keydown', _aiExamKeydownHandler);
        _aiExamKeydownBound = false;
    }

    if (_aiExamLastTrigger && typeof _aiExamLastTrigger.focus === 'function') {
        try { _aiExamLastTrigger.focus(); } catch (_) { /* noop */ }
    }
}
window.closeAiExamModal = closeAiExamModal;

function backToAiExamInput() {
    _aiExamSetState('input');
    _aiExamClearError('ai-exam-error');
    _aiExamClearError('ai-exam-confirm-error');
    const input = document.getElementById('ai-exam-input');
    if (input) {
        if (_aiExamRawInput && !input.value) input.value = _aiExamRawInput;
        input.focus();
        _aiExamUpdateCharCount();
    }
}
window.backToAiExamInput = backToAiExamInput;

async function submitAiExamParse() {
    const input = document.getElementById('ai-exam-input');
    const raw   = input ? input.value.trim() : '';

    if (!raw) {
        _aiExamShowError('ai-exam-error', 'Please enter a short description.');
        return;
    }
    if (raw.length > 500) {
        _aiExamShowError('ai-exam-error', 'Description is too long (max 500 characters).');
        return;
    }

    _aiExamRawInput = raw;
    _aiExamClearError('ai-exam-error');
    _aiExamSetState('loading');

    let tz = 'UTC';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) {}

    let res;
    try {
        res = await fetch('/api/ai/parse-exam', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ input: raw, timezone: tz }),
        });
    } catch (_) {
        _enterExamFallback('Could not reach the server.');
        return;
    }

    let data = {};
    try { data = await res.json(); } catch (_) { }

    if (res.ok) {
        _populateExamConfirm(data.parsed || {}, data.rawInput || raw);
        _aiExamSetState('confirm');
        return;
    }

    switch (res.status) {
        case 400:
            _aiExamSetState('input');
            _aiExamShowError('ai-exam-error', data.error || 'Invalid input.');
            return;
        case 401:
            window.location.href = '/';
            return;
        case 429:
            _aiExamSetState('input');
            _aiExamShowError('ai-exam-error',
                data.error || 'Too many AI requests — try again in an hour.');
            return;
        case 502:
            _enterExamFallback('AI returned something we couldn’t understand.');
            return;
        case 503:
            _enterExamFallback('AI is currently unavailable.');
            return;
        case 504:
            _enterExamFallback('AI took too long to respond.');
            return;
        default:
            _enterExamFallback(data.error || 'Unexpected AI error.');
    }
}
window.submitAiExamParse = submitAiExamParse;

async function saveAiExam() {
    const title    = _aiExamFieldVal('ai-exam-field-title');
    const date     = _aiExamFieldVal('ai-exam-field-date');
    const time     = _aiExamFieldVal('ai-exam-field-time');
    const course   = _aiExamFieldVal('ai-exam-field-course')   || null;
    const material = _aiExamFieldVal('ai-exam-field-material') || null;
    const location = _aiExamFieldVal('ai-exam-field-location') || null;

    if (!title) { _aiExamShowError('ai-exam-confirm-error', 'Title is required.'); return; }
    if (!date)  { _aiExamShowError('ai-exam-confirm-error', 'Date is required.');  return; }
    if (!time)  { _aiExamShowError('ai-exam-confirm-error', 'Time is required.');  return; }

    _aiExamClearError('ai-exam-confirm-error');
    _setExamConfirmSaving(true);

    let tz = 'UTC';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) {}

    try {
        const res = await fetch('/api/exams', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            // Shape B — send date + time + timezone strings (ExamRequest
            // docstring explains why we avoid client-side ISO arithmetic).
            body: JSON.stringify({
                title,
                date, time, timezone: tz,
                course,
                material,
                location,
            }),
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            _aiExamShowError('ai-exam-confirm-error', err.error || 'Could not save the exam.');
            _setExamConfirmSaving(false);
            return;
        }
    } catch (_) {
        _aiExamShowError('ai-exam-confirm-error', 'Could not reach the server.');
        _setExamConfirmSaving(false);
        return;
    }

    _setExamConfirmSaving(false);
    closeAiExamModal();
    if (typeof window.showToast === 'function') window.showToast('Exam created');

    // Host-agnostic refresh.
    if (typeof window.refreshDashboard === 'function') window.refreshDashboard();
    if (typeof window.fetchExams       === 'function') window.fetchExams();
}
window.saveAiExam = saveAiExam;

// ── Internal helpers ─────────────────────────────────────────

function _aiExamSetState(name) {
    AI_EXAM_STATES.forEach(s => {
        const el = document.getElementById('ai-exam-state-' + s);
        if (el) el.classList.toggle('hidden', s !== name);
    });

    const fallbackMsg = document.getElementById('ai-exam-fallback-msg');
    const echo        = document.getElementById('ai-exam-raw-echo');
    if (name === 'fallback') {
        if (fallbackMsg) fallbackMsg.classList.remove('hidden');
        if (echo)        echo.classList.add('hidden');
        const confirmPane = document.getElementById('ai-exam-state-confirm');
        if (confirmPane) confirmPane.classList.remove('hidden');
    } else if (name === 'confirm') {
        if (fallbackMsg) fallbackMsg.classList.add('hidden');
        if (echo)        echo.classList.remove('hidden');
    }

    const parseBtn = document.getElementById('ai-exam-parse-btn');
    if (parseBtn) parseBtn.disabled = (name === 'loading');
}

function _enterExamFallback(reason) {
    _aiExamSetState('fallback');
    _clearExamConfirmFields();
    const reasonEl = document.getElementById('ai-exam-fallback-reason');
    if (reasonEl) reasonEl.textContent = reason;
}

/**
 * Split the server-supplied ISO instant ("2026-04-30T14:00:00-04:00")
 * into the two separate input fields so the user can edit date and time
 * independently without juggling offsets.
 */
function _populateExamConfirm(parsed, rawInput) {
    const echoEl = document.getElementById('ai-exam-raw-echo-text');
    if (echoEl) echoEl.textContent = rawInput || '';

    _aiExamSetFieldValue('ai-exam-field-title',    parsed.title    || '');
    _aiExamSetFieldValue('ai-exam-field-course',   parsed.course   || '');
    _aiExamSetFieldValue('ai-exam-field-material', parsed.material || '');
    _aiExamSetFieldValue('ai-exam-field-location', parsed.location || '');

    // Pull local date + local time out of the ISO string. Use the browser's
    // timezone so the rendered fields match what the user will see on /exams.
    const iso = parsed.dateTime;
    if (iso) {
        try {
            const d = new Date(iso);
            const pad = n => String(n).padStart(2, '0');
            _aiExamSetFieldValue('ai-exam-field-date',
                d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()));
            _aiExamSetFieldValue('ai-exam-field-time',
                pad(d.getHours()) + ':' + pad(d.getMinutes()));
        } catch (_) {
            // Server's `parsed.dateTime` should always be parseable, but fall through.
        }
    }

    const human = document.getElementById('ai-exam-date-human');
    if (human) human.textContent = parsed.resolvedDateHuman || '';

    _aiExamClearError('ai-exam-confirm-error');
}

function _clearExamConfirmFields() {
    _aiExamSetFieldValue('ai-exam-field-title',    '');
    _aiExamSetFieldValue('ai-exam-field-date',     '');
    _aiExamSetFieldValue('ai-exam-field-time',     '');
    _aiExamSetFieldValue('ai-exam-field-course',   '');
    _aiExamSetFieldValue('ai-exam-field-material', '');
    _aiExamSetFieldValue('ai-exam-field-location', '');
    const human = document.getElementById('ai-exam-date-human');
    if (human) human.textContent = '';
}

function _resetAiExamFields() {
    const input = document.getElementById('ai-exam-input');
    if (input && _aiExamRawInput) input.value = _aiExamRawInput;
    else if (input) input.value = '';
    _aiExamClearError('ai-exam-error');
    _aiExamClearError('ai-exam-confirm-error');
    _clearExamConfirmFields();
    _setExamConfirmSaving(false);
    _aiExamUpdateCharCount();
}

function _aiExamFieldVal(id) {
    const el = document.getElementById(id);
    return el ? (el.value || '').trim() : '';
}
function _aiExamSetFieldValue(id, v) {
    const el = document.getElementById(id);
    if (el) el.value = v;
}
function _aiExamShowError(id, msg) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
}
function _aiExamClearError(id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = '';
    el.classList.add('hidden');
}
function _setExamConfirmSaving(saving) {
    const btn = document.getElementById('ai-exam-save-btn');
    if (btn) {
        btn.disabled    = saving;
        btn.textContent = saving ? 'Saving…' : 'Save Exam';
    }
}
function _aiExamUpdateCharCount() {
    const input = document.getElementById('ai-exam-input');
    const count = document.getElementById('ai-exam-char-count');
    if (input && count) count.textContent = String((input.value || '').length);
}

function _aiExamKeydownHandler(e) {
    const modal = document.getElementById('ai-exam-modal');
    if (!modal || modal.classList.contains('hidden')) return;

    if (e.key === 'Escape') {
        e.preventDefault();
        closeAiExamModal();
        return;
    }
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        const loading = document.getElementById('ai-exam-state-loading');
        if (loading && !loading.classList.contains('hidden')) return;
        const input = document.getElementById('ai-exam-state-input');
        if (input && !input.classList.contains('hidden')) submitAiExamParse();
        else                                              saveAiExam();
        return;
    }
    if (e.key === 'Tab') _aiExamTrapFocus(modal, e);
}

function _aiExamTrapFocus(modal, e) {
    const focusables = modal.querySelectorAll(
        'button, [href], input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    const visible = Array.from(focusables).filter(el => {
        if (el.disabled) return false;
        let p = el.closest('.hidden');
        return !p || !modal.contains(p);
    });
    if (visible.length === 0) return;
    const first = visible[0];
    const last  = visible[visible.length - 1];
    if (e.shiftKey && document.activeElement === first) {
        e.preventDefault(); last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault(); first.focus();
    }
}

// ── Bindings ─────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('ai-exam-input');
    if (input) input.addEventListener('input', _aiExamUpdateCharCount);

    // Keep the human-readable date echo in sync when the user edits
    // the date or time inputs in confirm state.
    ['ai-exam-field-date', 'ai-exam-field-time'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('change', _aiExamSyncDateHuman);
    });

    const modal = document.getElementById('ai-exam-modal');
    if (modal) {
        modal.addEventListener('click', e => {
            if (e.target === modal) closeAiExamModal();
        });
    }
});

function _aiExamSyncDateHuman() {
    const date = _aiExamFieldVal('ai-exam-field-date');
    const time = _aiExamFieldVal('ai-exam-field-time');
    const el   = document.getElementById('ai-exam-date-human');
    if (!el) return;
    if (!date || !time) { el.textContent = ''; return; }
    const [y, m, d] = date.split('-').map(Number);
    const [hh, mm]  = time.split(':').map(Number);
    if (!y || !m || !d || Number.isNaN(hh) || Number.isNaN(mm)) {
        el.textContent = ''; return;
    }
    try {
        el.textContent = new Intl.DateTimeFormat(undefined, {
            weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
            hour: 'numeric', minute: '2-digit',
        }).format(new Date(y, m - 1, d, hh, mm));
    } catch (_) { el.textContent = ''; }
}
