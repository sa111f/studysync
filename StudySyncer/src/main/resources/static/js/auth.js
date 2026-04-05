'use strict';

// ── Auth state ─────────────────────────────────────────
// window.currentUser is read by subjects.js and timer.js
window.currentUser = null;

let authModalMode = 'login'; // 'login' | 'register'

// ── Init ───────────────────────────────────────────────
async function initAuth() {
    attachKeyboardListeners();
    handleUrlParams();

    try {
        const res  = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            window.currentUser = data.username;
            showLoggedIn(data.username);
            await loadSavedPlan();
            await loadSavedTimer();
        } else {
            showLoggedOut();
        }
    } catch (e) {
        showLoggedOut();
    }
}

// ── URL param handling ─────────────────────────────────
function handleUrlParams() {
    // Reserved for future redirect-based flows (OAuth2, email verification, etc.)
}

function cleanUrl() {
    window.history.replaceState({}, '', '/');
}

// ── Banner ─────────────────────────────────────────────
function showBanner(type, msg) {
    const banner = document.getElementById('auth-banner');
    banner.className = 'auth-banner ' + type;
    document.getElementById('auth-banner-msg').textContent = msg;
}

function dismissBanner() {
    document.getElementById('auth-banner').className = 'auth-banner hidden';
}

// ── Auth UI state ──────────────────────────────────────
function showLoggedIn(username) {
    document.getElementById('auth-guest').classList.add('hidden');
    document.getElementById('auth-user').classList.remove('hidden');
    document.getElementById('auth-username').textContent = `Hi, ${username}`;
}

function showLoggedOut() {
    document.getElementById('auth-guest').classList.remove('hidden');
    document.getElementById('auth-user').classList.add('hidden');
}

// ── Modal open / close ─────────────────────────────────
function openModal(mode) {
    authModalMode = mode || 'login';
    refreshModalPanels();
    document.getElementById('auth-modal').classList.remove('hidden');
    // Focus first field
    const firstField = authModalMode === 'login'
        ? document.getElementById('login-identifier')
        : document.getElementById('reg-username');
    firstField.focus();
}

function closeModal() {
    document.getElementById('auth-modal').classList.add('hidden');
    clearAllErrors();
}

/**
 * BUG FIX: Text-selection drag across the overlay used to trigger closeModal()
 * because the mouseup landed on the overlay (making e.target the overlay).
 *
 * Fix: only close if *both* the mousedown AND the click/mouseup originated on
 * the overlay — not when the user just dragged text that happened to end there.
 */
let _overlayMousedownTarget = null;

function _initOverlayClose() {
    const overlay = document.getElementById('auth-modal');
    if (!overlay) return;

    overlay.addEventListener('mousedown', e => {
        _overlayMousedownTarget = e.target;
    });

    overlay.addEventListener('click', e => {
        // Close only when both mousedown AND click landed directly on the overlay
        if (e.target === overlay && _overlayMousedownTarget === overlay) {
            closeModal();
        }
        _overlayMousedownTarget = null;
    });
}

// Legacy handler kept for any inline onclick still present in HTML
function closeModalOnOverlay(e) {
    // Intentional no-op — overlay close is now handled by _initOverlayClose()
}

function switchModal() {
    authModalMode = authModalMode === 'login' ? 'register' : 'login';
    refreshModalPanels();
    clearAllErrors();
}

function refreshModalPanels() {
    const isLogin = authModalMode === 'login';
    document.getElementById('auth-modal-title').textContent = isLogin ? 'Log in' : 'Sign up';
    document.getElementById('login-panel').classList.toggle('hidden', !isLogin);
    document.getElementById('register-panel').classList.toggle('hidden', isLogin);
    document.getElementById('auth-modal-box').classList.toggle('wide', !isLogin);
}

// ── Keyboard ───────────────────────────────────────────
function attachKeyboardListeners() {
    const enter = e => { if (e.key === 'Enter') { authModalMode === 'login' ? doLogin() : doRegister(); } };
    ['login-identifier', 'login-password',
     'reg-username', 'reg-email', 'reg-password', 'reg-confirm']
        .forEach(id => document.getElementById(id)?.addEventListener('keydown', enter));

    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') closeModal();
    });

    _initOverlayClose();
}

// ── Password show/hide toggle ──────────────────────────
const EYE_OPEN  = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`;
const EYE_SLASH = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>`;

function togglePassword(inputId, btn) {
    const input = document.getElementById(inputId);
    const show  = input.type === 'password';
    input.type  = show ? 'text' : 'password';
    btn.innerHTML = show ? EYE_SLASH : EYE_OPEN;
    btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
}

// ── Password strength ──────────────────────────────────
function scorePassword(pw) {
    if (!pw || pw.length < 8) return 0;
    let score = 1; // baseline: meets minimum length
    if (pw.length >= 12)            score++;
    if (/[A-Z]/.test(pw))           score++;
    if (/[0-9]/.test(pw))           score++;
    if (/[^a-zA-Z0-9]/.test(pw))   score++;
    return Math.min(score, 4);
}

function updateStrengthBar(pw) {
    const bar      = document.getElementById('pw-strength-bar');
    const label    = document.getElementById('pw-strength-label');
    const wrap     = document.getElementById('pw-strength');

    if (!pw) { wrap.classList.add('hidden'); return; }
    wrap.classList.remove('hidden');

    const score = scorePassword(pw);
    const pct   = (score / 4) * 100;
    const colors = ['', '#ff5252', '#ffb300', '#4caf50', '#00c853'];
    const labels = ['', 'Weak', 'Fair', 'Good', 'Strong'];

    bar.style.setProperty('--strength-pct',   pct + '%');
    bar.style.setProperty('--strength-color', colors[score] || colors[1]);
    label.textContent = labels[score] || '';
    label.style.color = colors[score] || colors[1];
}

// ── Inline field validation (register) ────────────────
function fieldError(id, msg) {
    const el = document.getElementById(id);
    el.textContent = msg;
    el.classList.toggle('hidden', !msg);
}

function validateRegUsername() {
    const val = document.getElementById('reg-username').value.trim();
    if (!val)                           { fieldError('reg-username-error', 'Username is required.'); return false; }
    if (val.length < 3)                 { fieldError('reg-username-error', 'At least 3 characters.'); return false; }
    if (!/^[a-zA-Z0-9_]+$/.test(val))  { fieldError('reg-username-error', 'Letters, numbers, and underscores only.'); return false; }
    fieldError('reg-username-error', '');
    return true;
}

function validateRegEmail() {
    const val = document.getElementById('reg-email').value.trim();
    if (!val)                                       { fieldError('reg-email-error', 'Email is required.'); return false; }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(val))  { fieldError('reg-email-error', 'Enter a valid email address.'); return false; }
    fieldError('reg-email-error', '');
    return true;
}

function validateRegPassword() {
    const val = document.getElementById('reg-password').value;
    updateStrengthBar(val);
    if (!val)           { fieldError('reg-password-error', 'Password is required.'); return false; }
    if (val.length < 8) { fieldError('reg-password-error', 'At least 8 characters.'); return false; }
    fieldError('reg-password-error', '');
    // Re-validate confirm if already typed
    if (document.getElementById('reg-confirm').value) validateRegConfirm();
    return true;
}

function validateRegConfirm() {
    const pw  = document.getElementById('reg-password').value;
    const cpw = document.getElementById('reg-confirm').value;
    if (!cpw)        { fieldError('reg-confirm-error', 'Please confirm your password.'); return false; }
    if (pw !== cpw)  { fieldError('reg-confirm-error', 'Passwords do not match.'); return false; }
    fieldError('reg-confirm-error', '');
    return true;
}

function clearAllErrors() {
    ['login-identifier-error', 'login-password-error', 'login-error',
     'reg-username-error', 'reg-email-error', 'reg-password-error',
     'reg-confirm-error', 'register-error']
        .forEach(id => { const el = document.getElementById(id); if (el) { el.textContent = ''; el.classList.add('hidden'); } });
    document.getElementById('login-unverified-notice')?.classList.add('hidden');
}

// ── Global error display helpers ───────────────────────
function showLoginError(msg) {
    const el = document.getElementById('login-error');
    el.textContent = msg;
    el.classList.remove('hidden');
}

function showRegisterError(msg) {
    const el = document.getElementById('register-error');
    el.textContent = msg;
    el.classList.remove('hidden');
}

// ── Login ──────────────────────────────────────────────
async function doLogin() {
    const identifier = document.getElementById('login-identifier').value.trim();
    const password   = document.getElementById('login-password').value;

    // Basic client-side guard
    if (!identifier) { fieldError('login-identifier-error', 'Username or email is required.'); return; }
    if (!password)   { fieldError('login-password-error',   'Password is required.'); return; }

    const btn = document.getElementById('login-submit-btn');
    btn.disabled = true;
    btn.textContent = 'Logging in…';

    try {
        const res  = await fetch('/api/auth/login', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ identifier, password }),
        });
        const data = await res.json();

        if (res.ok) {
            window.currentUser = data.username;
            showLoggedIn(data.username);
            closeModal();
            await loadSavedPlan();
            await loadSavedTimer();
            return;
        }

        showLoginError(data.error || 'Login failed.');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Log in';
    }
}

// ── Register ───────────────────────────────────────────
async function doRegister() {
    // Run all validations
    const ok = validateRegUsername() & validateRegEmail() & validateRegPassword() & validateRegConfirm();
    if (!ok) return;

    const username        = document.getElementById('reg-username').value.trim();
    const email           = document.getElementById('reg-email').value.trim();
    const password        = document.getElementById('reg-password').value;
    const confirmPassword = document.getElementById('reg-confirm').value;

    const btn = document.getElementById('register-submit-btn');
    btn.disabled    = true;
    btn.textContent = 'Creating account…';

    try {
        const res = await fetch('/api/auth/register', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ username, email, password, confirmPassword }),
        });

        // Parse JSON safely — server may return non-JSON on unexpected errors
        let data = {};
        try { data = await res.json(); } catch (_) {}

        if (res.ok) {
            closeModal();
            showLoggedOut();   // guarantee guest state — signup never auto-logs in
            showBanner('success', 'Account created. Please log in.');
            return;
        }

        if (res.status >= 500) {
            // Server error — show a safe friendly message, not Spring's raw "Internal Server Error"
            showRegisterError(data.error || 'Unable to create account right now. Please try again.');
        } else {
            // 400 bad input / 409 conflict — server always sends { error: "..." }
            showRegisterError(data.error || 'Registration failed. Please check your details.');
        }
    } finally {
        btn.disabled    = false;
        btn.textContent = 'Sign up';
    }
}

// ── Logout ─────────────────────────────────────────────
async function logout() {
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (_) {}
    window.currentUser = null;
    showLoggedOut();
    const planCard = document.getElementById('study-plan-card');
    if (planCard) planCard.classList.add('hidden');
}

// ── Load saved data on login ───────────────────────────
async function loadSavedPlan() {
    if (!window.currentUser) return;
    try {
        const res = await fetch('/api/plans/latest');
        if (res.ok) {
            const plan = await res.json();
            if (plan && Array.isArray(plan.subjectPlans) && plan.subjectPlans.length > 0) {
                renderStudyPlan(plan);  // defined in subjects.js
            }
        }
    } catch (e) { /* silent */ }
}

async function loadSavedTimer() {
    if (!window.currentUser) return;
    try {
        const res = await fetch('/api/timer/load');
        if (res.ok) {
            const state = await res.json();
            if (state && state.mode) {
                restoreTimerState(state);  // defined in timer.js
            }
        }
    } catch (e) { /* silent */ }
}

// ── Boot ───────────────────────────────────────────────
// Guard against rare cases where the script executes before DOM is fully parsed
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAuth);
} else {
    initAuth();
}
