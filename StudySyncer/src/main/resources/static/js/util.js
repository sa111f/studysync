'use strict';
/**
 * util.js — small shared helpers used across tasks.js, dashboard.js, and
 * any page that needs a uniform duration / date rendering.
 *
 * Exposed as globals on `window.SS` (StudySyncer) so plain <script> includes
 * without a build step can reach them from every page.
 */
(function (global) {

    /**
     * Format a non-negative seconds count as a compact human-readable string.
     * Drops leading-zero components so small sessions read naturally:
     *     5      → "5s"
     *     42     → "42s"
     *     60     → "1m"
     *     75     → "1m 15s"
     *     3660   → "1h 1m"        (seconds dropped when hours present)
     *     3600   → "1h"
     *     0      → "0s"            (callers typically avoid this)
     */
    function formatDuration(seconds) {
        const s = Math.max(0, Math.floor(Number(seconds) || 0));
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const r = s % 60;

        if (h > 0) {
            return m > 0 ? h + 'h ' + m + 'm' : h + 'h';
        }
        if (m > 0) {
            return r > 0 ? m + 'm ' + r + 's' : m + 'm';
        }
        return r + 's';
    }

    /** HTML-escape a string for safe innerHTML concatenation. */
    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    global.SS = Object.assign(global.SS || {}, {
        formatDuration: formatDuration,
        escapeHtml:     escapeHtml,
    });

})(window);
