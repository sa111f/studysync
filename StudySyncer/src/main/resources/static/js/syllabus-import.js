'use strict';
/**
 * syllabus-import.js — Homepage "Import from Syllabus" flow (Phase 7.5).
 *
 * State machine:
 *     upload → extracting → review → importing → done
 *                                 ↘  error  ↙
 *
 * Only loaded on the homepage (the dashboard hosts the trigger button).
 * The modal fragment is included from templates/fragments/syllabus-modal.html.
 */

// ── Module state ─────────────────────────────────────────────
let _syllabusLastTrigger = null;
let _syllabusScrollY     = 0;
let _syllabusKeydown     = false;
let _syllabusFile        = null;

// Review-state data. Items come back from the server as plain objects;
// we wrap each with a local { included, item } so the user can toggle
// rows without mutating the original server payload.
let _syllabusItems = [];

const SYLL_STATES = ['upload', 'extracting', 'review', 'importing', 'done', 'error'];

// ── Public entry points ──────────────────────────────────────

function openSyllabusModal(triggerEl) {
    const modal = document.getElementById('syllabus-modal');
    if (!modal) return;

    _syllabusLastTrigger = triggerEl instanceof Element
        ? triggerEl
        : (document.activeElement instanceof HTMLElement ? document.activeElement : null);

    _syllabusFile   = null;
    _syllabusItems  = [];
    _resetSyllabusUpload();
    setSyllabusState('upload');

    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');

    _syllabusScrollY = window.scrollY || document.documentElement.scrollTop || 0;
    document.body.style.top = '-' + _syllabusScrollY + 'px';
    document.body.classList.add('ai-body-locked');

    if (!_syllabusKeydown) {
        document.addEventListener('keydown', _syllabusKeyHandler);
        _syllabusKeydown = true;
    }
}
window.openSyllabusModal = openSyllabusModal;

function closeSyllabusModal() {
    const modal = document.getElementById('syllabus-modal');
    if (!modal) return;
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');

    document.body.classList.remove('ai-body-locked');
    document.body.style.top = '';
    window.scrollTo(0, _syllabusScrollY);

    if (_syllabusKeydown) {
        document.removeEventListener('keydown', _syllabusKeyHandler);
        _syllabusKeydown = false;
    }

    if (_syllabusLastTrigger && typeof _syllabusLastTrigger.focus === 'function') {
        try { _syllabusLastTrigger.focus(); } catch (_) { /* noop */ }
    }
}
window.closeSyllabusModal = closeSyllabusModal;

function setSyllabusState(name) {
    SYLL_STATES.forEach(s => {
        const el = document.getElementById('syllabus-state-' + s);
        if (el) el.classList.toggle('hidden', s !== name);
    });
    // Progress breadcrumb highlighting.
    const stepMap = { upload: 'upload', extracting: 'upload', review: 'review',
                      importing: 'review', done: 'done', error: 'upload' };
    const active = stepMap[name] || 'upload';
    document.querySelectorAll('.syllabus-step').forEach(s => {
        s.classList.toggle('active', s.dataset.step === active);
    });
}
window.setSyllabusState = setSyllabusState;

// ── State 1: file picker + drag-and-drop ─────────────────────

function onSyllabusFileChosen(event) {
    const files = event.target.files;
    if (!files || files.length === 0) return;
    _acceptSyllabusFile(files[0]);
}
window.onSyllabusFileChosen = onSyllabusFileChosen;

function onSyllabusDragOver(e) {
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'copy';
    document.getElementById('syllabus-dropzone')?.classList.add('dragging');
}
window.onSyllabusDragOver = onSyllabusDragOver;

function onSyllabusDragLeave(e) {
    // Only clear the drag state when we actually leave the zone (not
    // when crossing into a child element). relatedTarget is null when
    // the pointer leaves the window entirely.
    if (!e.relatedTarget || !e.currentTarget.contains(e.relatedTarget)) {
        document.getElementById('syllabus-dropzone')?.classList.remove('dragging');
    }
}
window.onSyllabusDragLeave = onSyllabusDragLeave;

function onSyllabusDrop(e) {
    e.preventDefault();
    e.stopPropagation();
    document.getElementById('syllabus-dropzone')?.classList.remove('dragging');
    const file = e.dataTransfer?.files?.[0];
    if (file) _acceptSyllabusFile(file);
}
window.onSyllabusDrop = onSyllabusDrop;

function _acceptSyllabusFile(file) {
    _syllabusClearError('syllabus-upload-error');

    // Client-side guard rails — server enforces the same, but short-
    // circuiting here saves a round-trip for obvious rejections.
    const looksLikePdf = (file.type === 'application/pdf')
            || (file.name || '').toLowerCase().endsWith('.pdf');
    if (!looksLikePdf) {
        _syllabusShowError('syllabus-upload-error', 'Only PDF files are accepted.');
        return;
    }
    if (file.size > 10 * 1024 * 1024) {
        _syllabusShowError('syllabus-upload-error', 'File is too large. Maximum 10 MB.');
        return;
    }

    _syllabusFile = file;

    // Swap the dropzone into "file chosen" visual state.
    const primary = document.getElementById('syllabus-dropzone-primary');
    const sub     = document.getElementById('syllabus-dropzone-sub');
    if (primary) primary.textContent = file.name;
    if (sub)     sub.textContent     = _humanSize(file.size);
    document.getElementById('syllabus-dropzone')?.classList.add('has-file');

    const btn = document.getElementById('syllabus-parse-btn');
    if (btn) btn.disabled = false;
}

function _resetSyllabusUpload() {
    _syllabusFile = null;
    const input = document.getElementById('syllabus-file-input');
    if (input) input.value = '';
    const primary = document.getElementById('syllabus-dropzone-primary');
    const sub     = document.getElementById('syllabus-dropzone-sub');
    if (primary) primary.textContent = 'Drag a PDF here, or click to choose';
    if (sub)     sub.textContent     = 'PDF only · max 10 MB';
    document.getElementById('syllabus-dropzone')?.classList.remove('has-file', 'dragging');
    const btn = document.getElementById('syllabus-parse-btn');
    if (btn) btn.disabled = true;
    _syllabusClearError('syllabus-upload-error');
}

// ── State 2 → 3: upload + extract ────────────────────────────

async function uploadSyllabus() {
    if (!_syllabusFile) {
        _syllabusShowError('syllabus-upload-error', 'Please choose a PDF first.');
        return;
    }

    setSyllabusState('extracting');
    _setExtractingText('Reading your syllabus…');

    let tz = 'UTC';
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) {}

    const form = new FormData();
    form.append('file',     _syllabusFile);
    form.append('timezone', tz);

    // Flip the progress copy after ~1.5s so a long PDF + AI round-trip
    // doesn't feel frozen — pure UX nicety.
    const copyFlip = setTimeout(() => _setExtractingText('Extracting items…'), 1500);

    let res;
    try {
        res = await fetch('/api/syllabus/upload', { method: 'POST', body: form });
    } catch (_) {
        clearTimeout(copyFlip);
        _enterError('Could not reach the server.');
        return;
    }
    clearTimeout(copyFlip);

    let data = {};
    try { data = await res.json(); } catch (_) { /* empty body */ }

    if (!res.ok) {
        if (res.status === 401) { window.location.href = '/'; return; }
        // Upload errors (file too big, encrypted, non-PDF) should land
        // back on the upload state so the user can pick a different file.
        // Service-side failures (503/504/502/429) get the generic error pane.
        if (res.status === 400) {
            setSyllabusState('upload');
            _syllabusShowError('syllabus-upload-error', data.error || 'Could not read the PDF.');
            return;
        }
        _enterError(data.error || 'AI extraction failed.');
        return;
    }

    // Success — hydrate the review table.
    _syllabusItems = (data.items || []).map(item => ({ included: true, item: item }));
    _paintReviewState(data.courseCode || '', data.truncated === true);
    setSyllabusState('review');
}
window.uploadSyllabus = uploadSyllabus;

function _setExtractingText(msg) {
    const el = document.getElementById('syllabus-extracting-text');
    if (el) el.textContent = msg;
}

// ── State 3: review ──────────────────────────────────────────

function _paintReviewState(courseCode, truncated) {
    const courseEl = document.getElementById('syllabus-review-course');
    if (courseEl) courseEl.value = courseCode || '';

    const banner = document.getElementById('syllabus-truncated-banner');
    if (banner) banner.classList.toggle('hidden', !truncated);

    _renderReviewTable();
    _syncImportCount();
}

function _renderReviewTable() {
    const tbody = document.getElementById('syllabus-review-tbody');
    const count = document.getElementById('syllabus-found-count');
    if (!tbody) return;

    if (count) count.textContent = String(_syllabusItems.length);

    if (_syllabusItems.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="syllabus-empty-row">' +
            'No dated items found in this PDF. You can still add tasks manually.' +
            '</td></tr>';
        return;
    }

    tbody.innerHTML = _syllabusItems.map((row, idx) => _renderReviewRow(row, idx)).join('');
}

function _renderReviewRow(row, idx) {
    const it   = row.item || {};
    const kind = (it.kind || 'task').toLowerCase();

    // When field — tasks use a date input, exams use datetime-local.
    let whenField;
    if (kind === 'exam') {
        const dtLocal = _isoInstantToLocalInput(it.dateTime);
        whenField = `<input type="datetime-local" class="field-input syllabus-when-input"
                            data-idx="${idx}" data-field="dateTime"
                            value="${_escAttr(dtLocal)}" onchange="onReviewFieldChange(${idx}, 'dateTime', this.value)" />`;
    } else {
        whenField = `<input type="date" class="field-input syllabus-when-input"
                            data-idx="${idx}" data-field="dueDate"
                            value="${_escAttr(it.dueDate || '')}" onchange="onReviewFieldChange(${idx}, 'dueDate', this.value)" />`;
    }

    // Type / material field — tasks show a TaskType dropdown, exams show
    // the free-text material field (spec 7.4 table columns).
    let typeField;
    if (kind === 'exam') {
        typeField = `<input type="text" class="field-input syllabus-type-input"
                            value="${_escAttr(it.material || '')}"
                            placeholder="Topics to study"
                            onchange="onReviewFieldChange(${idx}, 'material', this.value)" />`;
    } else {
        const types = ['ASSIGNMENT','LAB','HOMEWORK','PROJECT','READING','OTHER'];
        const current = (it.taskType || 'OTHER').toUpperCase();
        const opts = types.map(t =>
            `<option value="${t}" ${t === current ? 'selected' : ''}>${_titleCase(t)}</option>`).join('');
        typeField = `<select class="field-input syllabus-type-input"
                             onchange="onReviewFieldChange(${idx}, 'taskType', this.value)">${opts}</select>`;
    }

    const kindBadgeClass = kind === 'exam' ? 'syllabus-kind-badge exam' : 'syllabus-kind-badge task';
    const kindLabel      = kind === 'exam' ? 'Exam' : 'Task';

    return `
    <tr class="syllabus-review-row ${row.included ? '' : 'excluded'}" data-idx="${idx}">
        <td class="syllabus-col-include">
            <input type="checkbox" ${row.included ? 'checked' : ''}
                   onchange="toggleItemChecked(${idx})"
                   aria-label="Include this item" />
        </td>
        <td class="syllabus-col-kind">
            <span class="${kindBadgeClass}">${kindLabel}</span>
        </td>
        <td class="syllabus-col-title">
            <input type="text" class="field-input syllabus-title-input"
                   value="${_escAttr(it.title || '')}"
                   maxlength="200"
                   onchange="onReviewFieldChange(${idx}, 'title', this.value)" />
        </td>
        <td class="syllabus-col-when">${whenField}</td>
        <td class="syllabus-col-type">${typeField}</td>
        <td class="syllabus-col-remove">
            <button type="button" class="task-icon-btn danger"
                    onclick="toggleItemChecked(${idx})"
                    aria-label="Remove item">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="6" y1="6" x2="18" y2="18"/>
                    <line x1="18" y1="6" x2="6" y2="18"/>
                </svg>
            </button>
        </td>
    </tr>`;
}

function toggleItemChecked(idx) {
    const row = _syllabusItems[idx];
    if (!row) return;
    row.included = !row.included;
    // Partial re-render: just toggle the row class + checkbox instead of
    // re-rendering the whole table (avoids losing in-progress edits on
    // other rows).
    const tr = document.querySelector('.syllabus-review-row[data-idx="' + idx + '"]');
    if (tr) {
        tr.classList.toggle('excluded', !row.included);
        const cb = tr.querySelector('input[type=checkbox]');
        if (cb) cb.checked = row.included;
    }
    _syncImportCount();
}
window.toggleItemChecked = toggleItemChecked;

function onReviewFieldChange(idx, field, value) {
    const row = _syllabusItems[idx];
    if (!row || !row.item) return;
    // datetime-local needs re-serializing to an ISO instant on submit.
    // Store the raw string here and convert in submitBulkImport().
    row.item[field] = value;
}
window.onReviewFieldChange = onReviewFieldChange;

function _syncImportCount() {
    const n = _syllabusItems.filter(r => r.included).length;
    const btn = document.getElementById('syllabus-import-btn');
    if (btn) {
        btn.textContent = 'Import ' + n + ' item' + (n === 1 ? '' : 's');
        btn.disabled    = (n === 0);
    }
}

// ── State 3 → 4 → 5: bulk import ─────────────────────────────

async function submitBulkImport() {
    _syllabusClearError('syllabus-review-error');

    // Build the payload. Skip excluded rows; coerce datetime-local back
    // to an ISO instant with the user's offset.
    const courseOverride = (document.getElementById('syllabus-review-course')?.value || '').trim();
    let tzOffset = '';
    try {
        tzOffset = _formatTzOffset(new Date().getTimezoneOffset());
    } catch (_) { tzOffset = 'Z'; }

    const items = [];
    for (const row of _syllabusItems) {
        if (!row.included) continue;
        const it = row.item;
        const base = {
            kind:   it.kind,
            title:  (it.title || '').trim(),
            course: courseOverride || (it.course || null),
        };
        if (base.kind === 'exam') {
            // datetime-local format: "2026-06-15T14:00" → "2026-06-15T14:00:00{±HH:MM}"
            const raw = it.dateTime || '';
            const iso = raw.length === 16 ? (raw + ':00' + tzOffset) : raw;
            items.push({
                ...base,
                dateTime: iso,
                material: it.material || null,
            });
        } else {
            items.push({
                ...base,
                dueDate:  it.dueDate || null,
                taskType: it.taskType || 'OTHER',
            });
        }
    }

    if (items.length === 0) {
        _syllabusShowError('syllabus-review-error', 'Nothing selected to import.');
        return;
    }

    setSyllabusState('importing');

    let res;
    try {
        res = await fetch('/api/bulk/import', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ items }),
        });
    } catch (_) {
        setSyllabusState('review');
        _syllabusShowError('syllabus-review-error', 'Could not reach the server.');
        return;
    }

    let data = {};
    try { data = await res.json(); } catch (_) {}

    if (res.status === 401) { window.location.href = '/'; return; }

    if (!res.ok) {
        // Validation errors come back as {tasksCreated:0, examsCreated:0, errors:[...]}.
        setSyllabusState('review');
        const first = (data.errors && data.errors[0]) || { error: data.error || 'Import failed.' };
        _syllabusShowError('syllabus-review-error',
            'Import failed: ' + (first.error || 'unknown error')
            + (typeof first.index === 'number' ? ' (row ' + (first.index + 1) + ')' : ''));
        return;
    }

    const t = data.tasksCreated || 0;
    const e = data.examsCreated || 0;
    const title = 'Imported ' + t + ' task' + (t === 1 ? '' : 's')
                + ' and ' + e + ' exam' + (e === 1 ? '' : 's');
    const doneTitle = document.getElementById('syllabus-done-title');
    if (doneTitle) doneTitle.textContent = title;

    setSyllabusState('done');

    // Tell the dashboard to re-fetch so Today/Upcoming/Next Exams
    // reflect the new rows immediately.
    if (typeof window.refreshDashboard === 'function') window.refreshDashboard();
    if (typeof window.showToast       === 'function') window.showToast('Imported ' + (t + e) + ' items');
}
window.submitBulkImport = submitBulkImport;

// ── Error state ──────────────────────────────────────────────

function _enterError(reason) {
    setSyllabusState('error');
    const el = document.getElementById('syllabus-error-reason');
    if (el) el.textContent = reason;
}

// ── Helpers ──────────────────────────────────────────────────

function _isoInstantToLocalInput(isoInstant) {
    if (!isoInstant) return '';
    try {
        const d = new Date(isoInstant);
        const pad = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
             + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    } catch (_) { return ''; }
}

function _formatTzOffset(minutesWest) {
    // Date.getTimezoneOffset() returns positive when local is WEST of UTC,
    // but ISO-8601 offset strings use the opposite sign. Flip once here.
    const offsetMin = -minutesWest;
    const sign = offsetMin >= 0 ? '+' : '-';
    const abs  = Math.abs(offsetMin);
    const hh   = String(Math.floor(abs / 60)).padStart(2, '0');
    const mm   = String(abs % 60).padStart(2, '0');
    return sign + hh + ':' + mm;
}

function _humanSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function _titleCase(ENUM) {
    return ENUM.charAt(0) + ENUM.slice(1).toLowerCase();
}

function _syllabusShowError(id, msg) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
}
function _syllabusClearError(id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = '';
    el.classList.add('hidden');
}

function _escAttr(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// ── Keyboard ─────────────────────────────────────────────────

function _syllabusKeyHandler(e) {
    const modal = document.getElementById('syllabus-modal');
    if (!modal || modal.classList.contains('hidden')) return;
    if (e.key === 'Escape') {
        e.preventDefault();
        closeSyllabusModal();
    }
}

// Overlay-click (not the dialog box itself) closes the modal.
document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('syllabus-modal');
    if (modal) {
        modal.addEventListener('click', e => {
            if (e.target === modal) closeSyllabusModal();
        });
    }
});
