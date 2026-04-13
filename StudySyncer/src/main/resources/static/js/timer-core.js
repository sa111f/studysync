'use strict';
/**
 * TimerCore — the ONE shared timer engine for StudySync.
 *
 * SINGLE SOURCE OF TRUTH for all timer state. Both the dashboard
 * (timer.js) and Focus Mode (focus.js) subscribe to this module.
 * Neither page owns an interval; all display refresh logic lives here.
 *
 * Model
 * ─────
 *   The authoritative live state is just:
 *     runEndAtMs (epoch ms)   — when the current phase's timer will hit 0
 *     isRunning (bool)        — whether we're currently counting down
 *     remainingSeconds (int)  — ONLY meaningful when NOT running
 *
 *   While running, `remaining` is a PURE FUNCTION of
 *     max(0, round((runEndAtMs - Date.now()) / 1000))
 *
 *   Nothing in this file decrements a stored counter while running.
 *   The setInterval ticks every 250 ms purely to refresh displays.
 *
 * Why the rewrite
 * ───────────────
 *   The previous version stored a `remaining` counter and decremented
 *   it on every interval tick.  On page navigation, init() would read
 *   the already-decremented counter AND subtract wall-clock elapsed time
 *   AGAIN, double-counting and losing ~15 minutes per navigation cycle.
 *   It also triggered a spurious auto-start() after landing remaining at
 *   zero, which produced a hidden auto-started break.
 *
 * Persistence
 *   localStorage           — cross-page navigation in the same browser
 *   BroadcastChannel       — cross-tab live sync of state transitions
 *   Backend /api/timer/*   — cross-device truth for logged-in users
 *
 * Idempotency
 *   Phase-end firing is guarded by _endedMarker = `${phase}:${runEndAtMs}`.
 *   Two tabs racing to finalize the same phase-instance will only advance
 *   the state once; the second call becomes a no-op.
 */
(function (global) {

    var LS_KEY  = 'ss_timer_v3';   // bump: v3 invalidates the old decrement-based format
    var BC_NAME = 'ss_timer_bc_v3';

    // ── Default state ──────────────────────────────────────────
    var DEFAULTS = {
        pomodoroMins:     25,
        shortBreakMins:   5,
        longBreakMins:    15,
        phase:            'pomodoro',  // 'pomodoro'|'shortbreak'|'longbreak'|'countdown'
        sessionCount:     1,
        totalSeconds:     25 * 60,
        remainingSeconds: 25 * 60,     // authoritative only when !isRunning
        isRunning:        false,
        isPaused:         false,
        runEndAtMs:       null,        // epoch ms when the running phase will hit 0
    };

    var _s              = Object.assign({}, DEFAULTS);
    var _ivl            = null;
    var _subs           = [];
    var _bc             = null;
    var _mute           = false;
    var _endedMarker    = null;  // prevents double-firing sessionEnd across tabs/reloads

    // ── Helpers ────────────────────────────────────────────────
    function _computeRemaining() {
        if (_s.isRunning && _s.runEndAtMs) {
            return Math.max(0, Math.round((_s.runEndAtMs - Date.now()) / 1000));
        }
        return Math.max(0, _s.remainingSeconds | 0);
    }

    function _snap() {
        var remaining = _computeRemaining();
        return {
            phase:            _s.phase,
            pomodoroMins:     _s.pomodoroMins,
            shortBreakMins:   _s.shortBreakMins,
            longBreakMins:    _s.longBreakMins,
            sessionCount:     _s.sessionCount,
            totalSeconds:     _s.totalSeconds,
            remaining:        remaining,        // canonical live value for UIs
            remainingSeconds: _s.remainingSeconds,
            isRunning:        _s.isRunning,
            isPaused:         _s.isPaused,
            runEndAtMs:       _s.runEndAtMs,
        };
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
            _bc.postMessage(Object.assign({ type: type, state: _persistable() }, extra || {}));
        } catch (_) {}
    }

    /** Persistable form: raw state fields only, no computed values. */
    function _persistable() {
        return {
            phase:            _s.phase,
            pomodoroMins:     _s.pomodoroMins,
            shortBreakMins:   _s.shortBreakMins,
            longBreakMins:    _s.longBreakMins,
            sessionCount:     _s.sessionCount,
            totalSeconds:     _s.totalSeconds,
            remainingSeconds: _s.remainingSeconds,
            isRunning:        _s.isRunning,
            isPaused:         _s.isPaused,
            runEndAtMs:       _s.runEndAtMs,
            endedMarker:      _endedMarker,
        };
    }

    function _save() {
        try { localStorage.setItem(LS_KEY, JSON.stringify(_persistable())); } catch (_) {}
    }

    function _durationFor(phase) {
        if (phase === 'shortbreak') return _s.shortBreakMins * 60;
        if (phase === 'longbreak')  return _s.longBreakMins  * 60;
        if (phase === 'countdown')  return _s.totalSeconds || (_s.pomodoroMins * 60);
        return _s.pomodoroMins * 60;  // 'pomodoro'
    }

    function _markerFor(phase, runEndAtMs) {
        return phase + ':' + (runEndAtMs || 0);
    }

    // ── BroadcastChannel (cross-tab live sync) ─────────────────
    try {
        _bc = new BroadcastChannel(BC_NAME);
        _bc.onmessage = function (ev) {
            var d = ev.data;
            if (!d || d.type !== 'STATE' || !d.state) return;
            _mute = true;
            Object.assign(_s, d.state);
            if (d.state.endedMarker != null) _endedMarker = d.state.endedMarker;
            _save();
            _fire(_s.isRunning ? 'start' : (_s.isPaused ? 'pause' : 'phase'));
            _mute = false;
        };
    } catch (_) { /* BroadcastChannel unavailable — graceful fallback */ }

    // ── Init: restore from localStorage ────────────────────────
    (function _init() {
        try {
            var raw = localStorage.getItem(LS_KEY);
            if (raw) {
                var saved = JSON.parse(raw);
                Object.assign(_s, saved);
                if (saved.endedMarker) _endedMarker = saved.endedMarker;
            }
        } catch (_) {}

        // If the saved state says "running" but runEndAtMs has already passed,
        // we'll handle it naturally in the first _tick() below.  No destructive
        // rewrite of remaining here — that was the old bug.
        _startDisplayInterval();
    })();

    // ── Display interval ───────────────────────────────────────
    function _startDisplayInterval() {
        if (_ivl) return;
        _ivl = setInterval(_tick, 250);
    }

    function _stopDisplayInterval() {
        if (_ivl) { clearInterval(_ivl); _ivl = null; }
    }

    function _tick() {
        if (_s.isRunning && _s.runEndAtMs) {
            var rem = _computeRemaining();
            if (rem <= 0) {
                _onSessionEnd();
                return;
            }
            _fire('tick');
        } else if (_s.isRunning && !_s.runEndAtMs) {
            // Shouldn't happen, but heal gracefully
            _s.isRunning = false;
            _save();
            _fire('phase');
        }
    }

    // ── Phase end (idempotent, guarded by _endedMarker) ────────
    function _onSessionEnd() {
        var endedPhase     = _s.phase;
        var endedRunEndAt  = _s.runEndAtMs;
        var marker         = _markerFor(endedPhase, endedRunEndAt);

        if (_endedMarker === marker) {
            // Another tab or this tab's earlier tick already handled it.
            return;
        }
        _endedMarker = marker;

        // Freeze the "just finished" snapshot so subscribers can log it.
        var preState = {
            phase:            endedPhase,
            pomodoroMins:     _s.pomodoroMins,
            shortBreakMins:   _s.shortBreakMins,
            longBreakMins:    _s.longBreakMins,
            sessionCount:     _s.sessionCount,
            totalSeconds:     _s.totalSeconds,
            remaining:        0,
            remainingSeconds: 0,
            isRunning:        false,
            isPaused:         false,
            runEndAtMs:       null,
        };

        // Stop the running clock locally.
        _s.isRunning  = false;
        _s.isPaused   = false;
        _s.runEndAtMs = null;
        _s.remainingSeconds = 0;

        _fire('done', preState);
        _fire('sessionEnd', preState);
        _save();
        _broadcast('STATE');

        // Advance to the next phase locally (frontend optimistic update).
        if (endedPhase === 'pomodoro') {
            var nextBreak = (_s.sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
            _s.sessionCount++;
            _setPhaseLocal(nextBreak);
            // Auto-start the break after a short grace period so subscribers
            // can finish rendering their "done" flash.
            setTimeout(function () { _api.start(); }, 1100);
        } else if (endedPhase === 'shortbreak' || endedPhase === 'longbreak') {
            if (_s.sessionCount > 4) _s.sessionCount = 1;
            _setPhaseLocal('pomodoro');
        } else {
            // Countdown — reset to full, stay stopped.
            _s.remainingSeconds = _s.totalSeconds;
            _save();
            _fire('phase');
            _broadcast('STATE');
        }

        // Backend reconciliation — fire-and-forget.  The idempotency guard
        // on the server means multiple tabs racing here all resolve safely.
        if (global.currentUser) {
            fetch('/api/timer/complete', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                keepalive: true,
                body: JSON.stringify({ phase: endedPhase, runEndAtMs: endedRunEndAt })
            }).catch(function () {});
        }
    }

    // Internal: switch phase locally without firing a backend call
    // (used during auto-advance after session end).
    function _setPhaseLocal(phase) {
        _s.phase            = phase;
        _s.totalSeconds     = _durationFor(phase);
        _s.remainingSeconds = _s.totalSeconds;
        _s.isRunning        = false;
        _s.isPaused         = false;
        _s.runEndAtMs       = null;
        _endedMarker        = null;
        _save();
        _broadcast('STATE');
        _fire('phase');
    }

    // ── Backend sync helpers ───────────────────────────────────
    function _postBackend(path, body) {
        if (!global.currentUser) return Promise.resolve(null);
        return fetch(path, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    body ? JSON.stringify(body) : '{}',
        }).then(function (r) { return r.ok ? r.json() : null; })
          .catch(function () { return null; });
    }

    /**
     * Adopt an authoritative state from the backend.  Used when:
     *   - another device started the timer and this tab just logged in
     *   - the server's /start response arrived with a refined runEndAtMs
     */
    function _adoptServerState(serverDto) {
        if (!serverDto || typeof serverDto !== 'object') return;
        _mute = true;
        if (serverDto.phase)             _s.phase            = serverDto.phase;
        if (serverDto.pomodoroMinutes)   _s.pomodoroMins     = serverDto.pomodoroMinutes;
        if (serverDto.shortBreakMinutes) _s.shortBreakMins   = serverDto.shortBreakMinutes;
        if (serverDto.longBreakMinutes)  _s.longBreakMins    = serverDto.longBreakMinutes;
        if (serverDto.sessionCount)      _s.sessionCount     = serverDto.sessionCount;
        if (serverDto.totalSeconds)      _s.totalSeconds     = serverDto.totalSeconds;
        if (typeof serverDto.remainingSeconds === 'number') _s.remainingSeconds = serverDto.remainingSeconds;
        _s.isRunning  = !!serverDto.running;
        _s.isPaused   = !!serverDto.paused;

        // Correct for clock skew between the server and this client.
        if (_s.isRunning && serverDto.runEndAtMs) {
            var skew = Date.now() - (serverDto.serverNowMs || Date.now());
            _s.runEndAtMs = serverDto.runEndAtMs + skew;
        } else {
            _s.runEndAtMs = null;
        }

        _endedMarker = null;
        _save();
        _broadcast('STATE');
        _fire(_s.isRunning ? 'start' : (_s.isPaused ? 'pause' : 'phase'));
        _mute = false;
    }

    // ── Public API ─────────────────────────────────────────────
    var _api = {

        subscribe: function (fn) {
            if (typeof fn === 'function' && _subs.indexOf(fn) < 0) _subs.push(fn);
        },

        unsubscribe: function (fn) {
            _subs = _subs.filter(function (f) { return f !== fn; });
        },

        /** Full live snapshot. `.remaining` is the canonical display value. */
        getState: function () { return _snap(); },

        getProgress: function () {
            var rem = _computeRemaining();
            return _s.totalSeconds > 0 ? rem / _s.totalSeconds : 0;
        },

        start: function () {
            if (_s.isRunning) return;

            // If remaining has drained to zero or below, reset to full phase
            // duration so the user can actually begin again.  This also
            // handles the "phase just ended and we're about to auto-start"
            // flow from _onSessionEnd.
            if (_s.remainingSeconds <= 0) {
                _s.remainingSeconds = _durationFor(_s.phase);
                _s.totalSeconds     = _s.remainingSeconds;
            }

            _s.isRunning  = true;
            _s.isPaused   = false;
            _s.runEndAtMs = Date.now() + _s.remainingSeconds * 1000;
            _endedMarker  = null;
            _save();
            _broadcast('STATE');
            _fire('start');

            _postBackend('/api/timer/start').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        pause: function () {
            if (!_s.isRunning) return;

            _s.remainingSeconds = _computeRemaining();
            _s.isRunning        = false;
            _s.isPaused         = true;
            _s.runEndAtMs       = null;
            _save();
            _broadcast('STATE');
            _fire('pause');

            _postBackend('/api/timer/pause').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        toggle: function () {
            if (_s.isRunning) _api.pause(); else _api.start();
        },

        /**
         * Skip the current phase.  Fires 'skip' with the pre-skip snapshot
         * so the UI can log a partial session, then advances.
         */
        skip: function () {
            var pre = _snap();
            pre.remaining = _computeRemaining();

            _fire('skip', pre);

            if (pre.phase === 'pomodoro' || pre.phase === 'countdown') {
                var nextBreak = (_s.sessionCount % 4 === 0) ? 'longbreak' : 'shortbreak';
                if (pre.phase === 'pomodoro') _s.sessionCount++;
                _setPhaseLocal(nextBreak);
                setTimeout(function () { _api.start(); }, 700);
            } else {
                if (_s.sessionCount > 4) _s.sessionCount = 1;
                _setPhaseLocal('pomodoro');
            }

            _postBackend('/api/timer/skip').then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        /**
         * Switch phase.  Pauses first if running.  Resets remaining to full.
         */
        setPhase: function (phase) {
            if (_s.isRunning) _api.pause();
            _setPhaseLocal(phase);
            _postBackend('/api/timer/phase', { phase: phase }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        setDurations: function (pomodoro, shortBreak, longBreak) {
            if (!isNaN(pomodoro)   && pomodoro   >= 1 && pomodoro   <= 999) _s.pomodoroMins   = pomodoro;
            if (!isNaN(shortBreak) && shortBreak >= 1 && shortBreak <= 999) _s.shortBreakMins = shortBreak;
            if (!isNaN(longBreak)  && longBreak  >= 1 && longBreak  <= 999) _s.longBreakMins  = longBreak;

            if (_s.phase !== 'countdown') {
                _s.totalSeconds     = _durationFor(_s.phase);
                _s.remainingSeconds = _s.totalSeconds;
                _s.isRunning        = false;
                _s.isPaused         = false;
                _s.runEndAtMs       = null;
            }
            _save();
            _broadcast('STATE');
            _fire('phase');

            _postBackend('/api/timer/durations', {
                pomodoroMinutes:   _s.pomodoroMins,
                shortBreakMinutes: _s.shortBreakMins,
                longBreakMinutes:  _s.longBreakMins,
            }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        applyCountdownDuration: function (mins) {
            var m = Math.max(1, Math.min(999, mins | 0 || 25));
            if (_s.isRunning) _api.pause();
            _s.phase            = 'countdown';
            _s.totalSeconds     = m * 60;
            _s.remainingSeconds = _s.totalSeconds;
            _s.isRunning        = false;
            _s.isPaused         = false;
            _s.runEndAtMs       = null;
            _endedMarker        = null;
            _save();
            _broadcast('STATE');
            _fire('phase');

            _postBackend('/api/timer/countdown', { minutes: m }).then(function (dto) {
                if (dto) _adoptServerState(dto);
            });
        },

        /**
         * Called by auth.js after login with the authoritative backend state.
         * Always overrides local state when present — the server wins on cross-
         * device sync.
         */
        restoreFromServer: function (serverState) {
            if (!serverState) return;
            _adoptServerState(serverState);
        },

        /** Legacy no-op kept for callers in focus.js.  Backend sync is now
         *  gated purely on window.currentUser. */
        enableBackendSync: function () { /* no-op */ },
    };

    global.TimerCore = _api;

})(window);
