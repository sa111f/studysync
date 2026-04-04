'use strict';

// ── State ──────────────────────────────────────────────
let totalSeconds   = 25 * 60;
let remainingSeconds = totalSeconds;
let timerInterval  = null;
let isRunning      = false;
let currentMode    = 'pomodoro';   // 'pomodoro' | 'countdown'
let sessionCount   = 1;
let isBreak        = false;
let sessions       = [];           // completed session log

const POMODORO_WORK  = 25;
const POMODORO_BREAK =  5;

// ── DOM refs ───────────────────────────────────────────
const display       = document.getElementById('timer-display');
const pomodoroInfo  = document.getElementById('pomodoro-info');
const sessionSpan   = document.getElementById('session-count');
const durationInput = document.getElementById('duration');
const resultsEmpty  = document.getElementById('results-empty');
const resultsTable  = document.getElementById('results-table');
const resultsBody   = document.getElementById('results-body');

// ── Helpers ────────────────────────────────────────────
function fmt(secs) {
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

function setDisplay(secs, state) {
    display.textContent = fmt(secs);
    display.className = 'timer-display ' + (state || '');
}

function updatePomodoroInfo() {
    if (currentMode === 'countdown') {
        pomodoroInfo.textContent = '';
        return;
    }
    if (isBreak) {
        pomodoroInfo.textContent = `Break time! (${POMODORO_BREAK} min) — session ${sessionCount - 1} done.`;
    } else {
        sessionSpan.textContent = sessionCount;
        pomodoroInfo.innerHTML =
            `Session <span id="session-count">${sessionCount}</span> of 4 — work for ${POMODORO_WORK} min, then take a ${POMODORO_BREAK} min break.`;
    }
}

// ── Mode ───────────────────────────────────────────────
function setMode(mode) {
    if (isRunning) pauseTimer();
    currentMode = mode;
    isBreak = false;

    document.getElementById('btn-pomodoro').classList.toggle('active', mode === 'pomodoro');
    document.getElementById('btn-countdown').classList.toggle('active', mode === 'countdown');

    if (mode === 'pomodoro') {
        totalSeconds = POMODORO_WORK * 60;
        durationInput.value = POMODORO_WORK;
        pomodoroInfo.style.display = '';
    } else {
        totalSeconds = parseInt(durationInput.value, 10) * 60 || 25 * 60;
        pomodoroInfo.style.display = 'none';
    }

    remainingSeconds = totalSeconds;
    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

/**
 * STUD-74: Apply a study time directly to the countdown timer.
 * Called by subjects.js when a recommended or custom study-time option is selected.
 * Sets state directly — does NOT read back from the duration input, avoiding
 * any browser constraint-validation coercion on the max="180" attribute.
 */
function applyTimerDuration(mins) {
    console.log('[StudySyncer] applyTimerDuration:', mins, 'min →', mins * 60, 'sec');
    if (isRunning) pauseTimer();
    currentMode = 'countdown';
    isBreak     = false;

    document.getElementById('btn-pomodoro').classList.remove('active');
    document.getElementById('btn-countdown').classList.add('active');

    totalSeconds     = mins * 60;
    remainingSeconds = totalSeconds;
    durationInput.value = mins;          // keep input in sync (display only)
    pomodoroInfo.style.display = 'none';

    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// ── Duration ───────────────────────────────────────────
function applyDuration() {
    if (isRunning) pauseTimer();
    const mins = Math.max(1, parseInt(durationInput.value, 10) || 25);
    durationInput.value = mins;
    totalSeconds = mins * 60;
    remainingSeconds = totalSeconds;
    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// ── Timer core ─────────────────────────────────────────
function startTimer() {
    if (isRunning) return;
    isRunning = true;
    setDisplay(remainingSeconds, 'running');

    timerInterval = setInterval(() => {
        if (remainingSeconds <= 0) {
            clearInterval(timerInterval);
            isRunning = false;
            setDisplay(0, 'done');
            handleSessionEnd();
            return;
        }
        remainingSeconds--;
        setDisplay(remainingSeconds, 'running');
    }, 1000);
}

function pauseTimer() {
    if (!isRunning) return;
    isRunning = false;
    clearInterval(timerInterval);
    setDisplay(remainingSeconds, 'paused');
}

/**
 * Skip the current session.
 *
 * Elapsed time  = totalSeconds − remainingSeconds.
 * Rounding rule = Math.round(elapsed / 60) — consistent with how
 *                 handleSessionEnd rounds countdown durations.
 * A skip is only recorded when at least one full rounded minute elapsed;
 * anything under 30 s rounds to 0 and is treated as a false start.
 *
 * Pomodoro work  → record partial minutes, then auto-start the break
 *                  (mirrors natural session completion).
 * Pomodoro break → end break early, return to work — nothing to record.
 * Countdown      → record partial minutes, reset to full duration.
 */
function skipTimer() {
    const elapsedSeconds = totalSeconds - remainingSeconds;

    clearInterval(timerInterval);
    isRunning = false;

    if (currentMode === 'pomodoro') {
        if (isBreak) {
            // Skip rest of break — no study time to record, just return to work
            isBreak      = false;
            totalSeconds = POMODORO_WORK * 60;
            remainingSeconds = totalSeconds;
            setDisplay(remainingSeconds, '');
            updatePomodoroInfo();
        } else {
            // Skip a work block — save partial time, then run the break
            const elapsedMins = Math.round(elapsedSeconds / 60);
            if (elapsedMins > 0) {
                const subject = (typeof getActiveSubjectName === 'function')
                    ? getActiveSubjectName() : 'General';
                logSession(subject, 'Pomodoro (work)', elapsedMins, false);
            }
            isBreak      = true;
            sessionCount++;
            totalSeconds = POMODORO_BREAK * 60;
            remainingSeconds = totalSeconds;
            setDisplay(remainingSeconds, '');
            updatePomodoroInfo();
            setTimeout(startTimer, 1000);   // auto-start break, same as natural end
        }
    } else {
        // Countdown — save partial time, reset to full duration
        const elapsedMins = Math.round(elapsedSeconds / 60);
        if (elapsedMins > 0) {
            const subject = (typeof getActiveSubjectName === 'function')
                ? getActiveSubjectName() : 'General';
            logSession(subject, 'Countdown', elapsedMins, false);
        }
        remainingSeconds = totalSeconds;
        setDisplay(remainingSeconds, '');
    }

    saveTimerState();
}

// ── Session end logic ──────────────────────────────────
function handleSessionEnd() {
    const subject = (typeof getActiveSubjectName === 'function') ? getActiveSubjectName() : 'General';

    if (currentMode === 'pomodoro') {
        if (!isBreak) {
            // Work session just ended — log it
            logSession(subject, 'Pomodoro (work)', POMODORO_WORK);
            isBreak = true;
            sessionCount++;
            totalSeconds = POMODORO_BREAK * 60;
            remainingSeconds = totalSeconds;
            setDisplay(remainingSeconds, '');
            updatePomodoroInfo();
            // Auto-start break
            setTimeout(startTimer, 1000);
        } else {
            // Break just ended
            isBreak = false;
            totalSeconds = POMODORO_WORK * 60;
            remainingSeconds = totalSeconds;
            if (sessionCount > 4) sessionCount = 1;
            setDisplay(remainingSeconds, '');
            updatePomodoroInfo();
        }
    } else {
        const mins = Math.round(totalSeconds / 60);
        logSession(subject, 'Countdown', mins);
    }

    // STUD-34: persist session count after each session
    saveTimerState();
}

// ── Timer persistence (STUD-34 / STUD-35) ─────────────

// Saves mode + duration config + session count for the logged-in user.
// Silent no-op for guests (window.currentUser is null until auth.js runs).
async function saveTimerState() {
    if (!window.currentUser) return;
    try {
        await fetch('/api/timer/save', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({
                mode:         currentMode,
                totalSeconds: totalSeconds,
                sessionCount: sessionCount,
            }),
        });
    } catch (_) { /* silent */ }
}

// Called by auth.js after login/page-load when saved state exists (STUD-35).
function restoreTimerState(state) {
    currentMode   = state.mode         || 'pomodoro';
    totalSeconds  = state.totalSeconds || (25 * 60);
    sessionCount  = state.sessionCount || 1;
    remainingSeconds = totalSeconds;
    isBreak  = false;
    isRunning = false;

    // Sync mode toggle buttons
    document.getElementById('btn-pomodoro').classList.toggle('active', currentMode === 'pomodoro');
    document.getElementById('btn-countdown').classList.toggle('active', currentMode === 'countdown');
    document.getElementById('duration').value = Math.round(totalSeconds / 60);

    const pomInfo = document.getElementById('pomodoro-info');
    pomInfo.style.display = (currentMode === 'pomodoro') ? '' : 'none';

    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// ── Results log ────────────────────────────────────────

/**
 * Record a study session in the local in-page log and (for logged-in users)
 * persist it to the backend.
 *
 * @param {string}  subject   - Subject / material name shown in the results table.
 * @param {string}  mode      - Timer mode label ("Pomodoro (work)" | "Countdown").
 * @param {number}  mins      - Rounded elapsed minutes.
 * @param {boolean} completed - true = ran to natural end; false = skipped early.
 */
function logSession(subject, mode, mins, completed = true) {
    const now  = new Date();
    const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const date = now.toISOString().split('T')[0];   // STUD-38/39: yyyy-mm-dd for daily/weekly sums
    sessions.push({ subject, mode, duration: mins + ' min', durationMins: mins, time, date, completed });
    renderResults();
    // STUD-36/37/38/39: notify progress tracker (defined in subjects.js)
    if (typeof onSessionCompleted === 'function') onSessionCompleted(subject, mins, date);

    // Persist to tracker backend for logged-in users
    if (window.currentUser) {
        // Prefer the doc-subject-name field (Study Material flow) over the session label
        const docSubjectEl = document.getElementById('doc-subject-name');
        const materialName = (docSubjectEl && docSubjectEl.value.trim())
            ? docSubjectEl.value.trim()
            : subject;
        fetch('/api/tracker/sessions', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({
                materialName:    materialName,
                durationMinutes: mins,
                timerMode:       mode,
                completed:       completed,
            }),
        }).catch(() => { /* silent — never break the timer flow */ });
    }
}

function renderResults() {
    if (sessions.length === 0) {
        resultsEmpty.classList.remove('hidden');
        resultsTable.classList.add('hidden');
        return;
    }
    resultsEmpty.classList.add('hidden');
    resultsTable.classList.remove('hidden');

    resultsBody.innerHTML = sessions.map((s, i) => `
        <tr>
            <td>${i + 1}</td>
            <td>${escape(s.subject)}</td>
            <td>${s.mode}</td>
            <td>${s.duration}</td>
            <td>${s.time}</td>
            <td class="${s.completed ? 'tracker-status-done' : 'tracker-status-partial'}">
                ${s.completed ? 'Completed' : 'Skipped'}
            </td>
        </tr>
    `).join('');
}

function escape(str) {
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function clearResults() {
    sessions = [];
    renderResults();
    if (typeof onSessionsCleared === 'function') onSessionsCleared();
}

// ── Reset for new material (called by subjects.js startNewMaterial) ────────
function resetTimerForNewMaterial() {
    // Stop any running timer
    isRunning = false;
    clearInterval(timerInterval);
    timerInterval = null;

    // Reset to Pomodoro defaults
    currentMode      = 'pomodoro';
    isBreak          = false;
    sessionCount     = 1;
    totalSeconds     = POMODORO_WORK * 60;
    remainingSeconds = totalSeconds;

    // Sync UI
    document.getElementById('btn-pomodoro').classList.add('active');
    document.getElementById('btn-countdown').classList.remove('active');
    durationInput.value        = POMODORO_WORK;
    pomodoroInfo.style.display = '';

    setDisplay(remainingSeconds, '');
    updatePomodoroInfo();
}

// ── Init ───────────────────────────────────────────────
setDisplay(remainingSeconds, '');
updatePomodoroInfo();
