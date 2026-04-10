'use strict';
/**
 * TimerCore — the ONE shared timer engine for StudySync.
 *
 * SINGLE SOURCE OF TRUTH for all timer state. Both the dashboard
 * (timer.js) and Focus Mode (focus.js) subscribe to this module.
 * Neither page owns an interval; all interval logic lives here.
 *
 * Persistence
 *   localStorage  — cross-page navigation in the same browser
 *   BroadcastChannel — best-effort cross-tab display sync
 *   Backend /api/timer/save — cross-device (triggered by UI layers)
 *
 * Auto-resume
 *   If the timer was running when the user navigated away, the
 *   elapsed real time is calculated from the saved timestamp and
 *   the timer resumes automatically on the new page after a 50 ms
 *   grace period (enough for subscribers to register).
 */
(function (global) {

    var LS_KEY  = 'ss_timer_v2';
    var BC_NAME = 'ss_timer_bc';

    // ── Default state ──────────────────────────────────────────
    var DEFAULTS = {
        pomodoroMins:   25,
        shortBreakMins: 5,
        longBreakMins:  15,
        phase:          'pomodoro',  // 'pomodoro'|'shortbreak'|'longbreak'|'countdown'
        sessionCount:   1,
        totalSeconds:   25 * 60,
        remaining:      25 * 60,
        isRunning:      false,
        isPaused:       false,       // true when paused mid-session; false when fresh/reset
        runSince:       null,        // Date.now() snapshot when last started
    };

    var _s    = Object.assign({}, DEFAULTS);
    var _ivl  = null;     // the one setInterval
    var _subs = [];       // subscriber callbacks
    var _bc   = null;     // BroadcastChannel
    var _mute = false;    // suppress re-broadcast while handling incoming msg
    var _pendingResume = false; // auto-resume scheduled

    // ── BroadcastChannel (cross-tab display sync) ──────────────
    try {
        _bc = new BroadcastChannel(BC_NAME);
        _bc.onmessage = function (ev) {
            var d = ev.data;
            _mute = true;
            if (d.type === 'TICK') {
                // Another tab ticked — update display if we have no local interval
                if (!_ivl) {
                    _s.remaining = d.remaining;
                    _s.isRunning = true;
                    _fire('tick');
                }
            } else if (d.type === 'STATE') {
                // Another tab changed phase / paused / etc.
                Object.assign(_s, d.state);
                if (_ivl && !_s.isRunning) {
                    clearInterval(_ivl);
                    _ivl = null;
                }
                // Fire the semantically correct event so UI subscribers
                // render the right visual state (pause blink, running glow, etc.)
                if (_s.isPaused && !_s.isRunning) {
                    _fire('pause');
                } else if (_s.isRunning) {
                    _fire('start');
                } else {
                    _fire('phase');
                }
            }
            _mute = false;
        };
    } catch (_) { /* BroadcastChannel unavailable — graceful fallback */ }

    // ── Helpers ────────────────────────────────────────────────
    function _snap() {
        return Object.assign({}, _s);
    }

    function _fire(event, overrideState) {
        var snap = overrideState || _snap();
        for (var i = 0; i < _subs.length; i++) {
            try { _subs[i](event, snap); } catch (_) {}
        }
    }

    function _broadcast(type, extra) {
        if (_mute || !_bc) return;
        try {
            _bc.postMessage(Object.assign({ type: type, state: _snap() }, extra || {}));
        } catch (_) {}
    }

    function _save() {
        try { localStorage.setItem(LS_KEY, JSON.stringify(_s)); } catch (_) {}
    }

    function _durationFor(phase) {
        if (phase === 'shortbreak') return _s.shortBreakMins * 60;
        if (phase === 'longbreak')  return _s.longBreakMins  * 60;
        return _s.pomodoroMins * 60;  // 'pomodoro' and 'countdown'
    }

    // ── Init: restore from localStorage + schedule auto-resume ─
    (function _init() {
        try {
            var raw = localStorage.getItem(LS_KEY);
            if (!raw) return;
            var saved = JSON.parse(raw);
            Object.assign(_s, saved);

            if (_s.isRunning && _s.runSince) {
                var elapsed = Math.floor((Date.now() - _s.runSince) / 1000);
                _s.remaining = Math.max(0, (_s.remaining || 0) - elapsed);
                _s.isRunning = false;
                _s.runSince  = null;

                if (_s.remaining > 0) {
                    // Schedule auto-resume — 50 ms gives subscribers time to register
                    _pendingResume = true;
                    setTimeout(function () {
                        _pendingResume = false;
                        _api.start();
                    }, 50);
                }
            }
        } catch (_) {}
    })();

    // ── Internal: handle natural session end ───────────────────
    function _onSessionEnd() {
        var preState = _snap();   // captures the phase that just completed

        // Fire 'sessionEnd' — UI layers handle /api/tracker/sessions
        _fire('sessionEnd', preState);

        if (_s.phase === 'pomodoro') {
            var next = (_s.sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
            _s.sessionCount++;
            _api.setPhase(next);
            setTimeout(function () { _api.start(); }, 1100);
        } else if (_s.phase === 'shortbreak' || _s.phase === 'longbreak') {
            if (_s.sessionCount > 4) _s.sessionCount = 1;
            _api.setPhase('pomodoro');
        } else {
            // countdown — reset to full, stay stopped (not paused)
            _s.remaining = _s.totalSeconds;
            _s.isPaused  = false;
            _save();
            _fire('phase');
        }
    }

    // ── Public API ─────────────────────────────────────────────
    var _api = {

        subscribe: function (fn) {
            if (typeof fn === 'function' && _subs.indexOf(fn) < 0) _subs.push(fn);
        },

        unsubscribe: function (fn) {
            _subs = _subs.filter(function (f) { return f !== fn; });
        },

        /** Full state snapshot (safe copy) */
        getState: function () { return _snap(); },

        /** Computed progress 0 → 1 */
        getProgress: function () {
            return _s.totalSeconds > 0 ? _s.remaining / _s.totalSeconds : 0;
        },

        /**
         * Switch phase. Resets remaining to the new duration.
         * Pauses any running timer first.
         */
        setPhase: function (phase) {
            if (_s.isRunning) _api.pause();
            _s.phase        = phase;
            _s.totalSeconds = _durationFor(phase);
            _s.remaining    = _s.totalSeconds;
            _s.isPaused     = false;
            _s.runSince     = null;
            _save();
            _broadcast('STATE');
            _fire('phase');
        },

        /**
         * Update editable durations and refresh current phase duration.
         */
        setDurations: function (pomodoro, shortBreak, longBreak) {
            if (!isNaN(pomodoro)   && pomodoro   >= 1 && pomodoro   <= 999) _s.pomodoroMins   = pomodoro;
            if (!isNaN(shortBreak) && shortBreak >= 1 && shortBreak <= 999) _s.shortBreakMins = shortBreak;
            if (!isNaN(longBreak)  && longBreak  >= 1 && longBreak  <= 999) _s.longBreakMins  = longBreak;
            if (_s.phase !== 'countdown') {
                _s.totalSeconds = _durationFor(_s.phase);
                _s.remaining    = _s.totalSeconds;
            }
            _s.isPaused = false;
            _save();
            _broadcast('STATE');
            _fire('phase');
        },

        /**
         * Apply a custom countdown duration.
         * Called by subjects.js via applyTimerDuration() shim in timer.js.
         */
        applyCountdownDuration: function (mins) {
            if (_s.isRunning) _api.pause();
            _s.phase        = 'countdown';
            _s.totalSeconds = mins * 60;
            _s.remaining    = _s.totalSeconds;
            _s.isPaused     = false;
            _s.runSince     = null;
            _save();
            _broadcast('STATE');
            _fire('phase');
        },

        start: function () {
            if (_s.isRunning) return;
            if (_ivl) { clearInterval(_ivl); _ivl = null; }

            _s.isRunning = true;
            _s.isPaused  = false;
            _s.runSince  = Date.now();
            _save();
            _broadcast('STATE');
            _fire('start');

            _ivl = setInterval(function () {
                if (_s.remaining <= 0) {
                    clearInterval(_ivl);
                    _ivl = null;
                    _s.isRunning = false;
                    _s.runSince  = null;
                    _save();
                    _broadcast('STATE');
                    _fire('done');
                    _onSessionEnd();
                    return;
                }
                _s.remaining--;
                _save();
                _broadcast('TICK', { remaining: _s.remaining });
                _fire('tick');
            }, 1000);
        },

        pause: function () {
            if (!_s.isRunning) return;
            clearInterval(_ivl);
            _ivl = null;
            _s.isRunning = false;
            _s.isPaused  = true;
            _s.runSince  = null;
            _save();
            _broadcast('STATE');
            _fire('pause');
        },

        toggle: function () {
            if (_s.isRunning) _api.pause(); else _api.start();
        },

        /**
         * Skip current phase.
         * Fires 'skip' with pre-skip snapshot so UI can log partial session.
         * Then advances to the next phase and auto-starts it.
         */
        skip: function () {
            var preSkip = _snap();               // capture BEFORE any mutation
            if (_s.isRunning) _api.pause();      // fires 'pause'

            _fire('skip', preSkip);              // UI logs partial minutes from preSkip

            if (_s.phase === 'pomodoro' || _s.phase === 'countdown') {
                var next = (_s.sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
                if (_s.phase === 'pomodoro') _s.sessionCount++;
                _api.setPhase(next);
                setTimeout(function () { _api.start(); }, 700);
            } else {
                if (_s.sessionCount > 4) _s.sessionCount = 1;
                _api.setPhase('pomodoro');
            }
        },

        /**
         * Restore state from backend after login.
         * Only updates sessionCount and countdown duration — does NOT
         * override locally-tracked remaining time or running state,
         * which localStorage already handles correctly.
         */
        restoreFromServer: function (serverState) {
            if (!serverState || _s.isRunning) return;
            if (serverState.sessionCount) _s.sessionCount = serverState.sessionCount;
            if (serverState.mode === 'countdown' && serverState.totalSeconds) {
                _s.phase        = 'countdown';
                _s.totalSeconds = serverState.totalSeconds;
                _s.remaining    = _s.totalSeconds;
            }
            _s.isPaused = false;
            _save();
            _fire('phase');
        },
    };

    global.TimerCore = _api;

})(window);
