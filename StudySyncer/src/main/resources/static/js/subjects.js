'use strict';

// ── State ──────────────────────────────────────────────
let subjectCounter = 0;
let planGenerated  = false;   // STUD-29: tracks whether a plan is currently shown
let _pdfPageCount  = 0;       // set by extractPdfText(), read by estimateStudyTime()

// ── Add / Remove (STUD-16) ─────────────────────────────
function addSubject() {
    subjectCounter++;
    const id   = subjectCounter;
    const list = document.getElementById('subject-list');

    const card = document.createElement('div');
    card.className  = 'subject-card';
    card.dataset.id = id;

    card.innerHTML = `
        <div class="subject-card-header">
            <span class="subject-card-title">Subject ${document.querySelectorAll('.subject-card').length + 1}</span>
            <button class="subject-remove-btn" onclick="removeSubject(${id})" title="Remove subject">&#x2715;</button>
        </div>

        <!-- Name (STUD-16) -->
        <div class="subject-field">
            <input  type="text"
                    class="subject-name field-input"
                    placeholder="Subject name *"
                    oninput="clearFieldError(this); markPlanStale()" />
            <span class="field-error hidden">Subject name is required.</span>
        </div>

        <!-- Difficulty + Workload row (STUD-17 + STUD-18) -->
        <div class="subject-row">
            <div class="subject-field">
                <select class="subject-difficulty field-input" onchange="clearFieldError(this); markPlanStale()">
                    <option value="">Difficulty *</option>
                    <option value="easy">Easy</option>
                    <option value="medium">Medium</option>
                    <option value="hard">Hard</option>
                </select>
                <span class="field-error hidden">Please select a difficulty.</span>
            </div>
            <div class="subject-field">
                <input  type="number"
                        class="subject-workload field-input"
                        placeholder="Hours / week *"
                        min="1" max="168"
                        oninput="clearFieldError(this); markPlanStale()" />
                <span class="field-error hidden">Enter estimated hours per week.</span>
            </div>
        </div>

        <!-- Notes (optional) -->
        <textarea class="subject-notes field-input"
                  placeholder="Topics, notes, syllabus... (optional)"
                  oninput="markPlanStale()"></textarea>
    `;

    list.appendChild(card);
    card.querySelector('.subject-name').focus();
    renumberSubjects();
}

function removeSubject(id) {
    const card = document.querySelector(`.subject-card[data-id="${id}"]`);
    if (card) card.remove();
    renumberSubjects();
    markPlanStale();
}

function renumberSubjects() {
    document.querySelectorAll('.subject-card').forEach((card, i) => {
        card.querySelector('.subject-card-title').textContent = `Subject ${i + 1}`;
    });
}

// ── Validation helpers (STUD-19) ───────────────────────
function clearFieldError(input) {
    const err = input.closest('.subject-field').querySelector('.field-error');
    if (err) err.classList.add('hidden');
    input.classList.remove('field-invalid');
}

function showFieldError(input) {
    const err = input.closest('.subject-field').querySelector('.field-error');
    if (err) err.classList.remove('hidden');
    input.classList.add('field-invalid');
}

// Returns true only when every subject card passes all required checks.
function validateSubjects() {
    const cards = document.querySelectorAll('.subject-card');
    if (cards.length === 0) return true;

    let valid = true;

    cards.forEach(card => {
        const nameInput     = card.querySelector('.subject-name');
        const diffSelect    = card.querySelector('.subject-difficulty');
        const workloadInput = card.querySelector('.subject-workload');

        if (!nameInput.value.trim()) {
            showFieldError(nameInput);
            valid = false;
        }

        if (!diffSelect.value) {
            showFieldError(diffSelect);
            valid = false;
        }

        const wl = parseFloat(workloadInput.value);
        if (!workloadInput.value.trim() || isNaN(wl) || wl < 1) {
            showFieldError(workloadInput);
            valid = false;
        }
    });

    return valid;
}

// ── Save / Load ────────────────────────────────────────
function saveSubjects() {
    if (!validateSubjects()) return;   // STUD-19: block if invalid

    const subjects = getSubjects();

    // Persist locally
    try {
        localStorage.setItem('studysync_subjects', JSON.stringify(subjects));
    } catch (e) { /* silently skip */ }

    // STUD-20: POST to backend and render the study plan
    // STUD-44: disable button while request is in-flight
    const btn = document.querySelector('#manual-input-panel .save-btn');
    if (btn) btn.disabled = true;
    console.log('Subjects being sent:', subjects);
    fetchStudyPlan(subjects).finally(() => { if (btn) btn.disabled = false; });
}

// ── API call (STUD-20) ─────────────────────────────────
async function fetchStudyPlan(subjects) {
    // STUD-57: update whichever status span is currently visible
    // STUD-44: add/remove .loading class for pulse animation
    function setStatus(msg) {
        const s1 = document.getElementById('save-status');
        const s2 = document.getElementById('doc-save-status');
        const loading = msg === 'Generating plan...';
        [s1, s2].forEach(el => {
            if (!el) return;
            el.textContent = msg;
            el.classList.toggle('loading', loading);
        });
    }

    setStatus('Generating plan...');

    try {
        const response = await fetch('/api/study-plan', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ subjects }),
        });

        // STUD-24: extract structured error message if backend sent one
        if (!response.ok) {
            let msg = `Server error (${response.status}).`;
            try {
                const errBody = await response.json();
                if (errBody && errBody.error) msg = errBody.error;
            } catch (_) { /* ignore parse failure */ }
            throw new Error(msg);
        }

        const data = await response.json();
        console.log('Study plan response:', data);

        // STUD-24: guard against empty or malformed response
        if (!data || !Array.isArray(data.subjectPlans) || data.subjectPlans.length === 0) {
            throw new Error('No study plan data returned.');
        }

        setStatus('Plan generated!');
        setTimeout(() => { setStatus(''); }, 2500);
        renderStudyPlan(data);

        // STUD-33: auto-save for logged-in users
        if (window.currentUser) {
            savePlanToBackend(data);
        }

    } catch (err) {
        // STUD-24: show the actual error message, not a generic string
        setStatus(err.message || 'Could not reach server.');
        setTimeout(() => { setStatus(''); }, 4000);
        console.error('Study plan fetch failed:', err);
    }
}

// ── Render study plan (STUD-25, 26, 27, 28, 29) ────────
function renderStudyPlan(plan) {
    const card   = document.getElementById('study-plan-card');
    const total  = document.getElementById('study-plan-total');
    const items  = document.getElementById('study-plan-items');
    const notice = document.getElementById('plan-stale-notice');

    // STUD-29: clear stale indicator on fresh render
    if (notice) notice.classList.add('hidden');
    planGenerated = true;

    // STUD-40: seed per-subject remaining-hours state from the fresh plan
    initSubjectProgress(plan);

    // STUD-25: weekly total
    total.textContent = `${plan.weeklyTotalHours} hrs / week total`;

    // STUD-26 + 27 + 28: per-subject card with hours, daily breakdown, reason
    items.innerHTML = plan.subjectPlans.map(s => {
        const daily = formatDailyBreakdown(s.recommendedDailyHours);  // STUD-27

        const reasonHtml = s.reason                                    // STUD-28
            ? `<div class="plan-reason">${escapeHtml(s.reason)}</div>`
            : '';

        const notesHtml = s.topicNotes                                 // STUD-21
            ? `<div class="plan-notes">${escapeHtml(s.topicNotes.length > 80 ? s.topicNotes.slice(0, 80) + '\u2026' : s.topicNotes)}</div>`
            : '';

        return `
        <div class="plan-item">
            <div class="plan-item-name">${escapeHtml(s.name)}</div>
            <div class="plan-item-meta">
                <span class="diff-badge diff-${s.difficulty}">${s.difficulty}</span>
                <span class="plan-hours">${s.recommendedWeeklyHours} hrs / week</span>
                <span class="plan-daily">${daily}</span>
            </div>
            ${reasonHtml}
            ${notesHtml}
            <div class="plan-remaining"></div>
        </div>`;
    }).join('');

    // STUD-40: fill in remaining hours now that DOM nodes exist
    renderRemainingHours();

    card.classList.remove('hidden');
    card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// STUD-27: readable daily breakdown text
function formatDailyBreakdown(dailyHours) {
    if (dailyHours >= 1) {
        return `5 days \u00d7 ${dailyHours} hrs/day`;
    }
    const mins = Math.round(dailyHours * 60);
    return `5 days \u00d7 ${mins} min/day`;
}

// STUD-29: show stale notice when inputs are edited after a plan is displayed
function markPlanStale() {
    if (!planGenerated) return;
    const notice = document.getElementById('plan-stale-notice');
    if (notice) notice.classList.remove('hidden');
}

// STUD-33: silently saves plan to backend; guest flow unaffected if this fails
async function savePlanToBackend(planData) {
    try {
        await fetch('/api/plans/save', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(planData),
        });
    } catch (_) { /* silent — never break guest or error flow */ }
}

// ── Data helpers ───────────────────────────────────────
// Returns a plain array of subject objects (for timer.js and future logic).
function getSubjects() {
    return Array.from(document.querySelectorAll('.subject-card')).map(card => ({
        name:       card.querySelector('.subject-name').value.trim(),
        difficulty: card.querySelector('.subject-difficulty').value,
        workload:   parseFloat(card.querySelector('.subject-workload').value) || 0,
        notes:      card.querySelector('.subject-notes').value.trim(),
    }));
}

// Used by timer.js to label completed sessions.
function getActiveSubjectName() {
    const first = document.querySelector('.subject-card .subject-name');
    return (first && first.value.trim()) ? first.value.trim() : 'General';
}

function loadSubjects() {
    try {
        const raw = localStorage.getItem('studysync_subjects');
        if (!raw) return;

        const list = document.getElementById('subject-list');
        if (list) list.innerHTML = '';
        subjectCounter = 0;

        JSON.parse(raw).forEach(s => {
            addSubject();
            const cards = document.querySelectorAll('.subject-card');
            const card  = cards[cards.length - 1];
            card.querySelector('.subject-name').value       = s.name       || '';
            card.querySelector('.subject-difficulty').value = s.difficulty || '';
            card.querySelector('.subject-workload').value   = s.workload   || '';
            card.querySelector('.subject-notes').value      = s.notes      || '';
        });
    } catch (e) { /* silently skip */ }
}

// ── Utilities ──────────────────────────────────────────
function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// STUD-67: upload PDF → backend extraction → fill notes textarea
async function extractPdfText() {
    const fileInput = document.getElementById('doc-pdf-input');
    const errorEl   = document.getElementById('doc-file-error');
    const statusEl  = document.getElementById('doc-extract-status');

    errorEl.classList.add('hidden');
    errorEl.textContent = '';
    statusEl.textContent = '';

    const file = fileInput.files[0];

    if (!file) {
        errorEl.textContent = 'Please select a PDF file first.';
        errorEl.classList.remove('hidden');
        return;
    }

    // STUD-56: frontend type check
    if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
        errorEl.textContent = 'Only PDF files are accepted.';
        errorEl.classList.remove('hidden');
        return;
    }

    // STUD-56: frontend size check — 10 MB limit
    if (file.size > 10 * 1024 * 1024) {
        errorEl.textContent = 'File is too large. Maximum size is 10 MB.';
        errorEl.classList.remove('hidden');
        return;
    }

    // STUD-75: loading state
    statusEl.textContent = 'Extracting text...';
    statusEl.classList.add('loading');

    const formData = new FormData();
    formData.append('file', file);

    // STUD-44: disable button while uploading
    const extractBtn = document.getElementById('btn-extract-pdf');
    if (extractBtn) extractBtn.disabled = true;

    try {
        // Do NOT set Content-Type manually — browser sets the multipart boundary
        const res  = await fetch('/api/document/extract', { method: 'POST', body: formData });
        const data = await res.json();

        if (!res.ok) {
            errorEl.textContent = data.error || 'Extraction failed.';
            errorEl.classList.remove('hidden');
            statusEl.textContent = '';
            return;
        }

        document.getElementById('doc-notes-input').value = data.text;
        _pdfPageCount = data.pageCount || 0;
        // STUD-75: success state
        const pageNote = _pdfPageCount > 0 ? ` (${_pdfPageCount} pages)` : '';
        statusEl.textContent = `Text extracted${pageNote} — ready to estimate.`;
        setTimeout(() => { statusEl.textContent = ''; }, 3500);
    } catch (_) {
        errorEl.textContent = 'Could not reach server.';
        errorEl.classList.remove('hidden');
        statusEl.textContent = '';
    } finally {
        statusEl.classList.remove('loading');
        if (extractBtn) extractBtn.disabled = false;
    }
}

// ── Study time estimation (STUD-68/69/72) ─────────────
async function estimateStudyTime() {
    const notesInput = document.getElementById('doc-notes-input');
    const titleInput = document.getElementById('doc-subject-name');
    const statusEl   = document.getElementById('doc-save-status');

    // STUD-68: validate content before sending
    const content = notesInput.value.trim();
    if (!content) {
        statusEl.textContent = 'Paste notes or upload/extract a PDF first.';
        setTimeout(() => { statusEl.textContent = ''; }, 4000);
        return;
    }

    // STUD-75: loading state
    statusEl.textContent = 'Estimating study time...';
    statusEl.classList.add('loading');

    const estBtn = document.getElementById('btn-estimate');
    if (estBtn) estBtn.disabled = true;

    try {
        // STUD-69: send title, content, and PDF metadata to structured endpoint
        const res = await fetch('/api/study-material/estimate', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({
                title:        titleInput ? titleInput.value.trim() : '',
                content:      content,
                pageCount:    _pdfPageCount,
                documentType: _pdfPageCount > 0 ? 'lecture slides' : 'notes',
            }),
        });
        const data = await res.json();

        if (!res.ok) {
            statusEl.classList.remove('loading');
            statusEl.textContent = data.error || 'Could not estimate study time. Please try again.';
            setTimeout(() => { statusEl.textContent = ''; }, 4000);
            return;
        }

        // STUD-75: success state
        statusEl.classList.remove('loading');
        statusEl.textContent = 'Study time options ready.';
        setTimeout(() => { statusEl.textContent = ''; }, 3000);

        console.log('[StudySyncer] /estimate response:', JSON.stringify(data));

        // STUD-72: render options with fresh response values
        renderEstimationOptions(data);

    } catch (_) {
        statusEl.classList.remove('loading');
        statusEl.textContent = 'Could not estimate study time. Please try again.';
        setTimeout(() => { statusEl.textContent = ''; }, 4000);
    } finally {
        if (estBtn) estBtn.disabled = false;
    }
}

// STUD-72: render Light / Standard / Deep option cards from structured AI response
function renderEstimationOptions(data) {
    const card        = document.getElementById('estimation-card');
    const optionsEl   = document.getElementById('estimation-options');
    const rationaleEl = document.getElementById('estimation-rationale');

    // STUD-71: read new field names; fall back to old names for backwards compat
    const light    = data.lightMinutes    ?? data.minMinutes;
    const standard = data.standardMinutes ?? data.recommendedMinutes;
    const deep     = data.deepMinutes     ?? data.maxMinutes;
    const reason   = data.reason          ?? data.rationale ?? '';

    rationaleEl.textContent = reason;

    const sourceEl = document.getElementById('estimate-source-badge');
    if (sourceEl) {
        const srcLabel = data.estimateSource === 'openai' ? 'AI estimate' : 'Local fallback';
        const meta = [
            data.pageCount ? `${data.pageCount} pages` : null,
            data.wordCount ? `${data.wordCount} words` : null,
        ].filter(Boolean).join(', ');
        sourceEl.textContent = meta ? `${srcLabel} · ${meta}` : srcLabel;
    }

    const opts = [
        { label: 'Light Review',   minutes: light },
        { label: 'Standard Study', minutes: standard },
        { label: 'Deep Study',     minutes: deep },
    ];

    // Replace contents to avoid duplicate cards on repeated clicks
    optionsEl.innerHTML = opts.map(o => `
        <button class="estimation-option" onclick="applyStudyTime(${o.minutes}, this)">
            <span class="est-label">${o.label}</span>
            <span class="est-time">${fmtMins(o.minutes)}</span>
        </button>
    `).join('');

    // Auto-select Standard Study by default so timer and target update immediately
    const standardBtn = optionsEl.querySelectorAll('.estimation-option')[1];
    if (standardBtn) applyStudyTime(standard, standardBtn);

    card.classList.remove('hidden');
    card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// STUD-60/61: highlight selection and update countdown timer
function applyStudyTime(minutes, btn) {
    // STUD-60: mark selected option; clear custom card selection
    document.querySelectorAll('.estimation-option').forEach(b => b.classList.remove('selected'));
    const cc = document.getElementById('custom-time-card');
    if (cc) cc.classList.remove('selected');
    if (btn) btn.classList.add('selected');

    // STUD-61/74: apply minutes directly to the timer — avoids input max="180" coercion bug
    applyTimerDuration(minutes);

    // STUD-79: record the chosen study target for progress tracking
    setStudyTarget(minutes);

    document.querySelector('.timer-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// Custom time option — validates hours/minutes inputs and applies to countdown timer
function applyCustomTime() {
    const hoursInput = document.getElementById('custom-hours');
    const minsInput  = document.getElementById('custom-minutes');
    const errorEl    = document.getElementById('custom-time-error');
    const customCard = document.getElementById('custom-time-card');

    errorEl.classList.add('hidden');
    errorEl.textContent = '';

    const h = parseInt(hoursInput.value, 10);
    const m = parseInt(minsInput.value,  10);
    const hVal = isNaN(h) ? 0 : h;
    const mVal = isNaN(m) ? 0 : m;

    if (hVal < 0 || mVal < 0) {
        errorEl.textContent = 'Hours and minutes cannot be negative.';
        errorEl.classList.remove('hidden');
        return;
    }
    if (mVal > 59) {
        errorEl.textContent = 'Minutes must be 0 – 59.';
        errorEl.classList.remove('hidden');
        return;
    }
    const total = hVal * 60 + mVal;
    if (total <= 0) {
        errorEl.textContent = 'Please enter a time greater than 0.';
        errorEl.classList.remove('hidden');
        return;
    }

    // Deselect recommended options, mark custom as selected
    document.querySelectorAll('.estimation-option').forEach(b => b.classList.remove('selected'));
    customCard.classList.add('selected');

    // Apply to countdown timer — applyTimerDuration() is defined in timer.js
    applyTimerDuration(total);

    // STUD-79: record custom target for progress tracking
    setStudyTarget(total);

    document.querySelector('.timer-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function fmtMins(mins) {
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

// ── localStorage helpers ───────────────────────────────

// STUD-79: selected study target
function saveStudyTarget(t) {
    try { localStorage.setItem('studysync_study_target', JSON.stringify(t)); } catch (_) {}
}

// STUD-79: set target when user selects a time option
function setStudyTarget(minutes) {
    const titleEl = document.getElementById('doc-subject-name');
    const name    = (titleEl && titleEl.value.trim()) || 'Study Material';
    // preserve accumulated progress if the same topic is re-selected
    const prevUsed = (studyTarget && studyTarget.topicName === name) ? studyTarget.usedMins : 0;
    studyTarget = { topicName: name, targetMins: minutes, usedMins: prevUsed };
    saveStudyTarget(studyTarget);
    renderProgressSummary();
}

// ── Progress tracking (STUD-36/37/38/39/40) ───────────

// Lightweight session mirror for daily/weekly sums (populated via onSessionCompleted)
let completedSessions = [];

// Allocated and used minutes per subject, keyed by subject name
let subjectProgress = {};

// STUD-79: study target from Study Material flow
let studyTarget = null;

// STUD-36: called by timer.js logSession after each completed work session
function onSessionCompleted(subject, durationMins, date) {
    completedSessions.push({ subject, durationMins, date });

    // STUD-37: reduce remaining hours for plan-based subjects
    const key = findProgressKey(subject);
    if (key) {
        subjectProgress[key].usedMins = Math.min(
            subjectProgress[key].usedMins + durationMins,
            subjectProgress[key].allocatedMins
        );
    }

    // STUD-79: reduce study material target remaining time
    if (studyTarget && studyTarget.targetMins > 0) {
        studyTarget.usedMins = Math.min(
            studyTarget.usedMins + durationMins,
            studyTarget.targetMins
        );
        saveStudyTarget(studyTarget);
    }

    renderProgressSummary();   // STUD-38/39
    renderRemainingHours();    // STUD-40
}

// Called by timer.js clearResults — resets all tracked progress
function onSessionsCleared() {
    completedSessions = [];
    Object.keys(subjectProgress).forEach(k => { subjectProgress[k].usedMins = 0; });
    // STUD-79: reset target progress but keep the target itself
    if (studyTarget) { studyTarget.usedMins = 0; saveStudyTarget(studyTarget); }
    renderProgressSummary();
    renderRemainingHours();
}

// STUD-40: seed subjectProgress when a study plan is (re-)rendered
function initSubjectProgress(plan) {
    const next = {};
    (plan.subjectPlans || []).forEach(s => {
        // Preserve usedMins if subject name matches an existing entry
        const prevUsed = (subjectProgress[s.name] || {}).usedMins || 0;
        next[s.name] = {
            allocatedMins: Math.round(s.recommendedWeeklyHours * 60),
            usedMins:      prevUsed,
        };
    });
    subjectProgress = next;
}

function findProgressKey(name) {
    if (!name) return null;
    if (subjectProgress[name] !== undefined) return name;
    const lower = name.toLowerCase();
    return Object.keys(subjectProgress).find(k => k.toLowerCase() === lower) || null;
}

// STUD-38/39/79: render today's totals, weekly totals, and study target progress
function renderProgressSummary() {
    const el = document.getElementById('progress-summary');
    if (!el) return;

    const today     = new Date().toISOString().split('T')[0];
    const weekStart = getWeekStart();

    let todayMins = 0, weekMins = 0;
    completedSessions.forEach(s => {
        if (s.date === today)    todayMins += s.durationMins;
        if (s.date >= weekStart) weekMins  += s.durationMins;
    });

    const hasTarget  = studyTarget && studyTarget.targetMins > 0;
    const hasSessions = weekMins > 0;

    // Hide when nothing to show
    if (!hasSessions && !hasTarget) { el.innerHTML = ''; return; }

    let html = '';

    if (hasSessions) {
        html += `
            <div class="progress-stat">
                <span class="progress-label">Today</span>
                <span class="progress-value">${fmtProgress(todayMins)}</span>
            </div>
            <div class="progress-stat">
                <span class="progress-label">This week</span>
                <span class="progress-value">${fmtProgress(weekMins)}</span>
            </div>
        `;
    }

    // STUD-79: study target progress
    if (hasTarget) {
        const remMins = Math.max(0, studyTarget.targetMins - studyTarget.usedMins);
        const done    = studyTarget.usedMins > 0;
        html += `
            <div class="progress-stat">
                <span class="progress-label">${escapeHtml(studyTarget.topicName)}</span>
                <span class="progress-value">${done
                    ? fmtProgress(studyTarget.usedMins) + ' / ' + fmtProgress(studyTarget.targetMins)
                    : fmtProgress(studyTarget.targetMins) + ' target'}</span>
            </div>
        `;
        if (done && remMins > 0) {
            html += `
                <div class="progress-stat">
                    <span class="progress-label">Remaining</span>
                    <span class="progress-value">${fmtProgress(remMins)}</span>
                </div>
            `;
        }
    }

    el.innerHTML = html;
}

// STUD-40: fill each .plan-remaining slot with current remaining hours
function renderRemainingHours() {
    document.querySelectorAll('.plan-item').forEach(item => {
        const nameEl = item.querySelector('.plan-item-name');
        if (!nameEl) return;
        const p = subjectProgress[nameEl.textContent.trim()];
        if (!p) return;

        const remMins = Math.max(0, p.allocatedMins - p.usedMins);
        const remEl   = item.querySelector('.plan-remaining');
        if (!remEl) return;

        if (p.usedMins > 0) {
            const usedHrs = (p.usedMins / 60).toFixed(1);
            const remHrs  = (remMins / 60).toFixed(1);
            remEl.textContent = `${remHrs} hrs remaining (${usedHrs} hrs done this week)`;
        } else {
            remEl.textContent = '';   // no sessions yet — keep the slot invisible
        }
    });
}

function getWeekStart() {
    const d = new Date();
    d.setDate(d.getDate() - d.getDay());   // rewind to Sunday
    return d.toISOString().split('T')[0];
}

function fmtProgress(mins) {
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

// ── Start New Material ─────────────────────────────────
function startNewMaterial() {
    // Clear material inputs
    const subjectInput = document.getElementById('doc-subject-name');
    const notesInput   = document.getElementById('doc-notes-input');
    const pdfInput     = document.getElementById('doc-pdf-input');
    if (subjectInput) subjectInput.value = '';
    if (notesInput)   notesInput.value   = '';
    if (pdfInput)     pdfInput.value     = '';
    _pdfPageCount = 0;

    // Clear status/error messages in the material panel
    const extractStatus = document.getElementById('doc-extract-status');
    const fileError     = document.getElementById('doc-file-error');
    const saveStatus    = document.getElementById('doc-save-status');
    if (extractStatus) { extractStatus.textContent = ''; extractStatus.classList.remove('loading'); }
    if (fileError)     { fileError.textContent = ''; fileError.classList.add('hidden'); }

    // Hide and clear the estimation card
    const estimationCard = document.getElementById('estimation-card');
    if (estimationCard) estimationCard.classList.add('hidden');
    const optionsEl   = document.getElementById('estimation-options');
    const rationaleEl = document.getElementById('estimation-rationale');
    const sourceEl    = document.getElementById('estimate-source-badge');
    if (optionsEl)   optionsEl.innerHTML      = '';
    if (rationaleEl) rationaleEl.textContent  = '';
    if (sourceEl)    sourceEl.textContent     = '';

    // Clear custom time inputs and selection state
    const customHours = document.getElementById('custom-hours');
    const customMins  = document.getElementById('custom-minutes');
    const customCard  = document.getElementById('custom-time-card');
    const customError = document.getElementById('custom-time-error');
    if (customHours) customHours.value = '';
    if (customMins)  customMins.value  = '';
    if (customCard)  customCard.classList.remove('selected');
    if (customError) { customError.textContent = ''; customError.classList.add('hidden'); }

    // Clear study target state and its localStorage key
    studyTarget = null;
    try { localStorage.removeItem('studysync_study_target'); } catch (_) {}
    renderProgressSummary();

    // Reset the timer to its default state via timer.js
    if (typeof resetTimerForNewMaterial === 'function') resetTimerForNewMaterial();

    // Scroll to the Study Material section
    const syllabusCard = document.querySelector('.syllabus-card');
    if (syllabusCard) syllabusCard.scrollIntoView({ behavior: 'smooth', block: 'start' });

    // Show inline success message — no alert()
    if (saveStatus) {
        saveStatus.textContent = 'Ready for a new study material.';
        setTimeout(() => { saveStatus.textContent = ''; }, 3000);
    }
}

// ── Init ───────────────────────────────────────────────
// Subject-card init removed (STUD-65): Study Material panel is now the primary input.

// Reset stored PDF page count if user manually edits the notes textarea,
// so pasted text is not treated as if it came from the last uploaded PDF.
(function () {
    const ta    = document.getElementById('doc-notes-input');
    const count = document.getElementById('doc-notes-count');
    if (ta) {
        ta.addEventListener('input', () => {
            _pdfPageCount = 0;
            if (count) count.textContent = ta.value.length + ' / 5000';
        });
    }
})();
