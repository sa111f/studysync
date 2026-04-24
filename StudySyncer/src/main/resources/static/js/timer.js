'use strict';
/**
 * timer.js — Dashboard UI layer for the timer card.
 *
 * Owns no timer state. Subscribes to TimerCore and renders the
 * countdown/overtime display, progress ring, and control buttons.
 * POSTs completed sessions to /api/tracker/sessions on Stop.
 *
 * Phase 3 additions:
 *   - "Studying:" <select> populated from /api/tasks?status=active
 *   - currentTaskId cached on Start so mid-session switches don't leak
 *   - taskId included in the POST body
 *   - no-task hint visible only when logged-in + idle + empty selection
 */

const display        = document.getElementById('timer-display');
const overtimeLabel  = document.getElementById('timer-overtime-label');

// ── Task picker state ──────────────────────────────────────────
// Cached the moment the user hits Start. Read back at sessionEnd so a
// user changing the (locked) <select> via devtools can't re-target the
// session. null => "No task selected" was picked.
let _currentTaskId = null;

function _fmt(secs) {
    secs = Math.max(0, secs | 0);
    const m = String(Math.floor(secs / 60)).padStart(2, '0');
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}`;
}

function _refreshDisplay(state, visState) {
    if (display) {
        if (state.isOvertime) {
            display.textContent = '+' + _fmt(state.overtimeSeconds);
        } else {
            display.textContent = _fmt(state.remainingSeconds);
        }
        let cls = 'timer-display';
        if (visState) cls += ' ' + visState;
        if (state.isOvertime) cls += ' overtime';
        display.className = cls;
    }

    if (overtimeLabel) {
        overtimeLabel.classList.toggle('hidden', !state.isOvertime);
    }

    const ring = document.getElementById('timer-ring-progress');
    if (ring) {
        const circ = 2 * Math.PI * 88;
        let progress = state.targetSeconds > 0
            ? state.elapsedSeconds / state.targetSeconds
            : 0;
        let remainingFrac = Math.max(0, 1 - progress);
        ring.style.strokeDasharray  = circ;
        ring.style.strokeDashoffset = circ * (1 - remainingFrac);
    }

    const wrap = document.getElementById('timer-ring-wrap');
    if (wrap) {
        wrap.classList.toggle('timer-running', visState === 'running');
        wrap.classList.toggle('timer-overtime', !!state.isOvertime);
    }

    const startBtn = document.getElementById('btn-start-pause');
    if (startBtn) {
        const running = visState === 'running';
        startBtn.textContent = running ? 'Pause' : 'Start';
        startBtn.classList.toggle('is-pausing', running);
    }

    const stopBtn = document.getElementById('btn-stop');
    if (stopBtn) {
        const hasActive = state.isRunning || state.isPaused || state.elapsedSeconds > 0;
        stopBtn.classList.toggle('hidden', !hasActive);
    }

    _refreshTaskPickerLockState(state);
    _refreshNoTaskHint(state);
}

// ── Task picker lifecycle ─────────────────────────────────────

/**
 * Fetches active tasks and paints the <select>. Call on init and after
 * each successful session POST (so a dashboard action that marks a task
 * COMPLETED removes it from the list on the next render).
 */
async function _populateTaskPicker() {
    const picker = document.getElementById('timer-task-picker');
    const select = document.getElementById('timer-task-select');
    if (!picker || !select) return;

    // Hide the entire picker for guests — session POSTs are already gated
    // behind window.currentUser, so no taskId ever leaves localStorage land.
    if (!window.currentUser) {
        picker.classList.add('hidden');
        return;
    }

    try {
        const res = await fetch('/api/tasks?status=active');
        if (!res.ok) { picker.classList.add('hidden'); return; }

        const tasks = await res.json();
        // Sort by dueDate ASC (server already orders this way; defensive client-side sort).
        tasks.sort((a, b) => {
            const ad = a.dueDate || '9999-12-31';
            const bd = b.dueDate || '9999-12-31';
            return ad < bd ? -1 : ad > bd ? 1 : 0;
        });

        // Preserve current selection across re-populate so a mid-session
        // refresh doesn't clobber the caller's choice.
        const preserved = select.value;

        const opts = ['<option value="">No task selected</option>']
            .concat(tasks.map(t => {
                const suffix = t.course ? ' · ' + _esc(t.course) : '';
                return `<option value="${t.id}">${_esc(t.title)}${suffix}</option>`;
            }));
        select.innerHTML = opts.join('');

        // Restore previously-selected option if it still exists in the list
        // (e.g. because another tab hasn't completed it yet).
        if (preserved && select.querySelector('option[value="' + preserved + '"]')) {
            select.value = preserved;
        }

        picker.classList.remove('hidden');
    } catch (_) {
        // Network error — hide the picker rather than surface a stale list.
        picker.classList.add('hidden');
    }
}

function _refreshTaskPickerLockState(state) {
    const select = document.getElementById('timer-task-select');
    if (!select) return;
    // Disable during any active session (running or paused mid-way or in overtime).
    const active = state.isRunning || state.isPaused || state.elapsedSeconds > 0;
    select.disabled = active;
}

/**
 * Show the "💡 Select a task above..." hint when all of these are true:
 *   - user is logged in (guests have no task list to pick from)
 *   - the <select> is empty (no task chosen)
 *   - the timer is idle (not running / paused / in progress)
 */
function _refreshNoTaskHint(state) {
    const hint = document.getElementById('timer-no-task-hint');
    if (!hint) return;

    const select  = document.getElementById('timer-task-select');
    const hasSel  = !!(select && select.value);
    const loggedIn = !!window.currentUser;
    const idle    = !state.isRunning && !state.isPaused && state.elapsedSeconds <= 0;

    const show = loggedIn && !hasSel && idle;
    hint.classList.toggle('hidden', !show);
}

// ── TimerCore event subscriber ────────────────────────────────

function _announceTimer(msg) {
    const el = document.getElementById('timer-announcer');
    if (!el) return;
    el.textContent = msg;
}

function _onCoreEvent(event, state) {
    let visState;
    if      (event === 'pause') visState = 'paused';
    else if (state.isRunning)   visState = 'running';
    else if (state.isPaused)    visState = 'paused';
    else                        visState = '';

    if (event !== 'sessionEnd') _refreshDisplay(state, visState);

    if      (event === 'start' && !state.isPaused) _announceTimer('Timer started');
    else if (event === 'pause')                    _announceTimer('Timer paused');
    else if (event === 'milestone')                _announceTimer('Target reached — overtime');

    // Cache the selected task the moment the user hits Start so a locked-
    // but-editable-via-devtools <select> can't re-target the ongoing session.
    if (event === 'start' && !state.isPaused) {
        const select = document.getElementById('timer-task-select');
        const raw    = select ? select.value : '';
        _currentTaskId = raw ? parseInt(raw, 10) : null;
    }

    if (event === 'sessionEnd') {
        const actualMins = Math.max(0, Math.round(state.actualDurationSeconds / 60));
        const plannedMin = Math.max(0, Math.round(state.plannedSeconds       / 60));
        const overtime   = Math.max(0, actualMins - plannedMin);

        if (actualMins > 0 && window.currentUser) {
            fetch('/api/tracker/sessions', {
                method:    'POST',
                headers:   { 'Content-Type': 'application/json' },
                keepalive: true,
                body: JSON.stringify({
                    materialName:    'Study Session',
                    durationMinutes: actualMins,
                    plannedMinutes:  plannedMin,
                    overtimeMinutes: overtime,
                    timerMode:       'study',
                    completed:       true,
                    taskId:          _currentTaskId,
                }),
            })
            .then(() => {
                // Refresh the picker (a completed task may have been marked
                // from another tab) and nudge the dashboard if it's present.
                _populateTaskPicker();
                if (typeof window.refreshDashboard === 'function') window.refreshDashboard();
            })
            .catch(() => {});
        }

        // Reset picker state after the session is sealed.
        _currentTaskId = null;
        _refreshDisplay(state, '');
    }
}

TimerCore.subscribe(_onCoreEvent);

function startTimer()  { TimerCore.start();  }
function pauseTimer()  { TimerCore.pause();  }
function toggleTimer() { TimerCore.toggle(); }
function stopTimer()   { TimerCore.stop();   }
function skipTimer()   { TimerCore.skip();   }

function applyTimerDuration(mins) {
    TimerCore.setTargetMinutes(mins);
}

function restoreTimerState(state) {
    TimerCore.restoreFromServer(state);
}

function saveTimerState() { /* intentionally empty */ }

function toggleTimerSettings() {
    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (!panel) return;
    const nowHidden = panel.classList.toggle('hidden');
    if (toggle) toggle.classList.toggle('open', !nowHidden);
}

function applyTimerSettings() {
    const t = parseInt(document.getElementById('dur-target')?.value, 10);
    if (!isNaN(t)) TimerCore.setTargetMinutes(t);

    const panel  = document.getElementById('timer-settings-panel');
    const toggle = document.getElementById('timer-settings-toggle');
    if (panel)  panel.classList.add('hidden');
    if (toggle) toggle.classList.remove('open');
}

document.addEventListener('keydown', e => {
    if (e.key === ' ' && e.target === document.body) {
        e.preventDefault();
        TimerCore.toggle();
    }
});

// Selecting a task should instantly hide the no-task hint without waiting
// for the next TimerCore tick.
document.addEventListener('DOMContentLoaded', () => {
    const select = document.getElementById('timer-task-select');
    if (select) {
        select.addEventListener('change', () => _refreshNoTaskHint(TimerCore.getState()));
    }
});

// Exposed for the dashboard (Phase 4) to refresh the picker after it flips
// a task to COMPLETED or IN_PROGRESS, without the dashboard knowing the
// picker internals.
window.refreshTimerTaskPicker = _populateTaskPicker;

(function () {
    const state = TimerCore.getState();
    const _initVis = state.isRunning ? 'running' : (state.isPaused ? 'paused' : '');
    _refreshDisplay(state, _initVis);

    const tgt = document.getElementById('dur-target');
    if (tgt) tgt.value = state.targetMins;

    // Populate the task picker after auth resolves — the /api/tasks call
    // 401s for guests, so waiting avoids a flash of an empty picker.
    (async function () {
        try { await window.authReady; } catch (_) {}
        await _populateTaskPicker();
        _refreshNoTaskHint(TimerCore.getState());
    })();
})();

function _esc(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
