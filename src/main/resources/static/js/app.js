// ══════════════════════════════════════════
// STATE
// ══════════════════════════════════════════
const state = {
  token: localStorage.getItem('eminbank_token') || null,
  email: localStorage.getItem('eminbank_email') || null,
  role:  localStorage.getItem('eminbank_role')  || null,
  accounts: [],
  pendingCloseAccountId: null,
};

// ── Boot ──────────────────────────────────
if (state.token) {
  showApp();
} else {
  document.getElementById('auth-screen').style.display = 'flex';
}

// ══════════════════════════════════════════
// API HELPERS
// ══════════════════════════════════════════
async function api(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  if (res.status === 204) return null;
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok) {
    const msg = (data && (data.message || data.error)) || `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data;
}

// ══════════════════════════════════════════
// TOASTS
// ══════════════════════════════════════════
function toast(msg, type = 'info') {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  const icons = { success: '✓', error: '✕', info: 'ℹ' };
  el.innerHTML = `<span style="font-size:16px">${icons[type] || 'ℹ'}</span><span>${msg}</span>`;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 3500);
}

// ══════════════════════════════════════════
// AUTH
// ══════════════════════════════════════════
function showAuthTab(tab) {
  document.querySelectorAll('.auth-tab').forEach((t, i) =>
    t.classList.toggle('active', (tab === 'login' && i === 0) || (tab === 'register' && i === 1))
  );
  document.getElementById('login-form').style.display    = tab === 'login'    ? 'block' : 'none';
  document.getElementById('register-form').style.display = tab === 'register' ? 'block' : 'none';
}

async function doLogin(btn) {
  const email    = document.getElementById('login-email').value.trim();
  const password = document.getElementById('login-password').value;
  if (!email || !password) { toast('Please fill in all fields.', 'error'); return; }
  setLoading(btn, true);
  try {
    const data = await api('POST', '/api/auth/login', { email, password });
    state.token = data.token;
    state.email = data.email;
    state.role  = data.role;
    localStorage.setItem('eminbank_token', data.token);
    localStorage.setItem('eminbank_email', data.email);
    localStorage.setItem('eminbank_role',  data.role);
    toast('Welcome back!', 'success');
    showApp();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

async function doRegister(btn) {
  const firstName = document.getElementById('reg-firstName').value.trim();
  const lastName  = document.getElementById('reg-lastName').value.trim();
  const email     = document.getElementById('reg-email').value.trim();
  const password  = document.getElementById('reg-password').value;
  if (!firstName || !lastName || !email || !password) { toast('Please fill in all fields.', 'error'); return; }
  setLoading(btn, true);
  try {
    await api('POST', '/api/users/register', { firstName, lastName, email, password });
    toast('Account created! Please sign in.', 'success');
    showAuthTab('login');
    document.getElementById('login-email').value = email;
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

function doLogout() {
  state.token = null; state.email = null; state.role = null; state.accounts = [];
  localStorage.removeItem('eminbank_token');
  localStorage.removeItem('eminbank_email');
  localStorage.removeItem('eminbank_role');
  document.getElementById('app-screen').style.display  = 'none';
  document.getElementById('auth-screen').style.display = 'flex';
  toast('You have been signed out.', 'info');
}

// ══════════════════════════════════════════
// APP SHELL
// ══════════════════════════════════════════
function showApp() {
  document.getElementById('auth-screen').style.display = 'none';
  document.getElementById('app-screen').style.display  = 'block';

  const h = new Date().getHours();
  const greet = h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
  document.getElementById('welcome-msg').textContent = greet + (state.email ? ', ' + state.email.split('@')[0] : '') + ' 👋';

  const initials = state.email ? state.email[0].toUpperCase() : '?';
  document.getElementById('user-avatar').textContent        = initials;
  document.getElementById('user-email-display').textContent = state.email || '';
  document.getElementById('user-name-display').textContent  = state.role  || 'USER';

  loadDashboard();
}

function showPage(page) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.getElementById('page-' + page).classList.add('active');
  document.querySelectorAll('.nav-item').forEach(n => {
    if (n.textContent.toLowerCase().includes(page)) n.classList.add('active');
  });
  if (page === 'notifications') loadNotifications();
  if (page === 'accounts')      renderAccountsPage();
  if (page === 'transactions')  refreshAccountSelects();
}

// ══════════════════════════════════════════
// DASHBOARD
// ══════════════════════════════════════════
async function loadDashboard() {
  try {
    state.accounts = await api('GET', '/api/accounts');
    renderOverviewAccounts();
    await loadRecentTransactions();
    await refreshNotifBadge();
  } catch (e) {
    toast('Failed to load dashboard: ' + e.message, 'error');
  }
}

function renderOverviewAccounts() {
  const total = state.accounts.reduce((s, a) => s + parseFloat(a.balance || 0), 0);
  document.getElementById('total-balance').textContent       = formatMoney(total);
  document.getElementById('total-accounts-label').textContent = `Across ${state.accounts.length} account${state.accounts.length !== 1 ? 's' : ''}`;

  const el = document.getElementById('overview-account-list');
  if (!state.accounts.length) {
    el.innerHTML = `<div class="empty-state" style="padding:24px"><div class="icon">🏦</div><p>No accounts yet</p></div>`;
    return;
  }
  el.innerHTML = state.accounts.map(a => `
    <div style="display:flex;justify-content:space-between;align-items:center;padding:12px 0;border-bottom:1px solid var(--border)">
      <div>
        <div style="font-size:13px;font-weight:600">${a.accountType} <span style="font-family:monospace;font-size:11px;color:var(--muted)">#${a.id}</span></div>
        <div style="font-size:11px;color:var(--muted);margin-top:2px">${a.status}</div>
      </div>
      <div style="font-size:16px;font-weight:700;color:var(--text)">${formatMoney(a.balance)}</div>
    </div>
  `).join('');
}

async function loadRecentTransactions() {
  const tbody = document.getElementById('recent-tx-body');
  if (!state.accounts.length) return;
  try {
    const allTx = [];
    for (const acc of state.accounts.slice(0, 3)) {
      try {
        const txs = await api('GET', `/api/transactions/${acc.id}/history`);
        allTx.push(...txs);
      } catch {}
    }
    allTx.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    const recent = allTx.slice(0, 8);
    if (!recent.length) {
      tbody.innerHTML = `<tr><td colspan="4"><div class="empty-state" style="padding:32px"><div class="icon">📭</div><p>No transactions yet</p></div></td></tr>`;
      return;
    }
    tbody.innerHTML = recent.map(tx => `
      <tr>
        <td><span class="tx-badge ${txTypeBadge(tx.transactionType)}">${tx.transactionType}</span></td>
        <td class="tx-amount ${tx.transactionType === 'DEPOSIT' ? 'credit' : 'debit'}">${tx.transactionType === 'DEPOSIT' ? '+' : '-'}${formatMoney(tx.amount)}</td>
        <td><span class="tx-badge ${statusBadge(tx.status)}">${tx.status}</span></td>
        <td style="color:var(--muted);font-size:12px">${formatDate(tx.createdAt)}</td>
      </tr>
    `).join('');
  } catch {}
}

// ══════════════════════════════════════════
// ACCOUNTS
// ══════════════════════════════════════════
function renderAccountsPage() {
  const grid   = document.getElementById('accounts-grid');
  const colors = ['', 'green-card', 'purple-card', '', 'green-card'];
  let html = state.accounts.map((a, i) => `
    <div class="bank-card ${colors[i % colors.length]}">
      <div class="bank-card-actions">
        <button onclick="showCloseModal(${a.id})" class="close-btn">Close</button>
      </div>
      <div class="bank-card-type">${a.accountType} Account</div>
      <div class="bank-card-balance">${formatMoney(a.balance)}</div>
      <div class="bank-card-currency">USD · ${a.accountNumber || 'EminBank'}</div>
      <div class="bank-card-footer">
        <div class="bank-card-id">${a.firstName ? a.firstName + ' ' + a.lastName : 'Account #' + a.id}</div>
        <div class="bank-card-status ${a.status === 'ACTIVE' ? 'active' : 'closed'}">${a.status}</div>
      </div>
    </div>
  `).join('');
  html += `<div class="new-account-card" onclick="openModal('modal-create-account')"><div class="plus">+</div><span>Open New Account</span></div>`;
  grid.innerHTML = html;
}

async function doCreateAccount(btn) {
  const accountType = document.getElementById('new-account-type').value;
  setLoading(btn, true);
  try {
    await api('POST', '/api/accounts', { accountType });
    state.accounts = await api('GET', '/api/accounts');
    closeModal('modal-create-account');
    renderAccountsPage();
    renderOverviewAccounts();
    refreshAccountSelects();
    toast(`${accountType} account opened successfully!`, 'success');
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

function showCloseModal(id) {
  state.pendingCloseAccountId = id;
  document.getElementById('close-account-id-label').textContent = '#' + String(id).padStart(8, '0');
  openModal('modal-close-account');
}

async function doCloseAccount(btn) {
  if (!state.pendingCloseAccountId) return;
  setLoading(btn, true);
  try {
    await api('DELETE', `/api/accounts/${state.pendingCloseAccountId}`);
    state.accounts = await api('GET', '/api/accounts');
    closeModal('modal-close-account');
    renderAccountsPage();
    renderOverviewAccounts();
    refreshAccountSelects();
    toast('Account closed.', 'success');
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
    state.pendingCloseAccountId = null;
  }
}

// ══════════════════════════════════════════
// TRANSACTIONS
// ══════════════════════════════════════════
function setTxTab(tab) {
  ['deposit', 'withdraw', 'transfer', 'history'].forEach(t => {
    document.getElementById('tab-' + t).classList.toggle('active', t === tab);
    document.getElementById('tx-' + t).style.display = t === tab ? 'block' : 'none';
  });
  if (tab === 'history') refreshHistorySelect();
  refreshAccountSelects();
}

function refreshAccountSelects() {
  const activeAccounts = state.accounts.filter(a => a.status === 'ACTIVE');
  const opts = activeAccounts.length
    ? activeAccounts.map(a => `<option value="${a.id}">${a.accountType} #${a.id} — ${formatMoney(a.balance)}</option>`).join('')
    : '<option value="">No active accounts</option>';
  ['dep-account', 'wd-account', 'tr-from'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.innerHTML = opts;
  });
}

function refreshHistorySelect() {
  const el   = document.getElementById('hist-account');
  const opts = state.accounts.map(a => `<option value="${a.id}">${a.accountType} #${a.id}</option>`).join('');
  el.innerHTML = '<option value="">Select account…</option>' + opts;
}

async function doDeposit(btn) {
  const accountId   = parseInt(document.getElementById('dep-account').value);
  const amount      = parseFloat(document.getElementById('dep-amount').value);
  const description = document.getElementById('dep-desc').value;
  if (!accountId || !amount) { toast('Fill in all required fields.', 'error'); return; }
  setLoading(btn, true);
  try {
    await api('POST', '/api/transactions/deposit', { accountId, amount, description });
    state.accounts = await api('GET', '/api/accounts');
    renderOverviewAccounts();
    refreshAccountSelects();
    document.getElementById('dep-amount').value = '';
    document.getElementById('dep-desc').value   = '';
    toast(`Deposit of ${formatMoney(amount)} successful!`, 'success');
    await refreshNotifBadge();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

async function doWithdraw(btn) {
  const accountId   = parseInt(document.getElementById('wd-account').value);
  const amount      = parseFloat(document.getElementById('wd-amount').value);
  const description = document.getElementById('wd-desc').value;
  if (!accountId || !amount) { toast('Fill in all required fields.', 'error'); return; }
  setLoading(btn, true);
  try {
    await api('POST', '/api/transactions/withdraw', { accountId, amount, description });
    state.accounts = await api('GET', '/api/accounts');
    renderOverviewAccounts();
    refreshAccountSelects();
    document.getElementById('wd-amount').value = '';
    document.getElementById('wd-desc').value   = '';
    toast(`Withdrawal of ${formatMoney(amount)} successful!`, 'success');
    await refreshNotifBadge();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

async function doTransfer(btn) {
  const sourceAccountId = parseInt(document.getElementById('tr-from').value);
  const targetAccountId = parseInt(document.getElementById('tr-to').value);
  const amount          = parseFloat(document.getElementById('tr-amount').value);
  const description     = document.getElementById('tr-desc').value;
  if (!sourceAccountId || !targetAccountId || !amount) { toast('Fill in all required fields.', 'error'); return; }
  setLoading(btn, true);
  try {
    await api('POST', '/api/transactions/transfer', { sourceAccountId, targetAccountId, amount, description });
    state.accounts = await api('GET', '/api/accounts');
    renderOverviewAccounts();
    refreshAccountSelects();
    document.getElementById('tr-to').value     = '';
    document.getElementById('tr-amount').value = '';
    document.getElementById('tr-desc').value   = '';
    toast(`Transfer of ${formatMoney(amount)} successful!`, 'success');
    await refreshNotifBadge();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    setLoading(btn, false);
  }
}

async function loadHistory() {
  const accountId = document.getElementById('hist-account').value;
  const start     = document.getElementById('hist-start').value;
  const end       = document.getElementById('hist-end').value;
  const tbody     = document.getElementById('history-tbody');
  if (!accountId) {
    tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state" style="padding:32px"><div class="icon">📂</div><p>Select an account to view history</p></div></td></tr>`;
    return;
  }
  tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;padding:32px;color:var(--muted)">Loading…</td></tr>`;
  try {
    let url = `/api/transactions/${accountId}/history`;
    if (start && end) url += `?start=${encodeURIComponent(start + ':00')}&end=${encodeURIComponent(end + ':00')}`;
    const txs = await api('GET', url);
    if (!txs.length) {
      tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state" style="padding:32px"><div class="icon">📭</div><p>No transactions in this range</p></div></td></tr>`;
      return;
    }
    tbody.innerHTML = txs.map((tx, i) => `
      <tr>
        <td style="color:var(--muted);font-size:12px">${i + 1}</td>
        <td><span class="tx-badge ${txTypeBadge(tx.transactionType)}">${tx.transactionType}</span></td>
        <td class="tx-amount ${tx.transactionType === 'DEPOSIT' ? 'credit' : 'debit'}">${tx.transactionType === 'DEPOSIT' ? '+' : '-'}${formatMoney(tx.amount)}</td>
        <td><span class="tx-badge ${statusBadge(tx.status)}">${tx.status}</span></td>
        <td style="color:var(--muted);font-size:13px">${tx.description || '—'}</td>
        <td style="color:var(--muted);font-size:12px">${formatDate(tx.createdAt)}</td>
      </tr>
    `).join('');
  } catch (e) {
    toast(e.message, 'error');
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;padding:24px;color:var(--red)">Error loading history</td></tr>`;
  }
}

function clearHistoryFilter() {
  document.getElementById('hist-start').value = '';
  document.getElementById('hist-end').value   = '';
  loadHistory();
}

// ══════════════════════════════════════════
// NOTIFICATIONS
// ══════════════════════════════════════════
async function loadNotifications() {
  const list = document.getElementById('notif-list');
  list.innerHTML = `<div style="text-align:center;padding:48px;color:var(--muted)">Loading…</div>`;
  try {
    const notifs = await api('GET', '/api/notifications');
    if (!notifs.length) {
      list.innerHTML = `<div class="empty-state"><div class="icon">🔔</div><h3>All caught up!</h3><p>No notifications to show.</p></div>`;
      return;
    }
    const icons     = { TRANSACTION: '💸', SYSTEM: 'ℹ', WARNING: '⚠', INFO: 'ℹ' };
    const iconClass = { TRANSACTION: 'tx', WARNING: 'warn', SYSTEM: 'info', INFO: 'info' };
    list.innerHTML = notifs.map(n => {
      const isRead = n.read || n.isRead;
      return `
        <div class="notif-item ${isRead ? '' : 'unread'}" id="notif-${n.id}">
          <div class="notif-icon ${iconClass[n.type] || 'info'}">${icons[n.type] || '🔔'}</div>
          <div class="notif-body">
            <div class="notif-title">${notifLabel(n.type)}</div>
            <div class="notif-msg">${escHtml(n.message || '')}</div>
            <div class="notif-time">${formatDate(n.createdAt)}</div>
          </div>
          ${!isRead ? `<div class="notif-dot"></div>` : ''}
          ${!isRead ? `<button class="notif-read-btn" onclick="markRead(${n.id})" title="Mark as read">✓</button>` : ''}
        </div>`;
    }).join('');
  } catch (e) {
    list.innerHTML = `<div class="empty-state"><div class="icon">⚠</div><p>${e.message}</p></div>`;
  }
  await refreshNotifBadge();
}

async function markRead(id) {
  try {
    await api('PATCH', `/api/notifications/${id}/read`);
    const el = document.getElementById('notif-' + id);
    if (el) {
      el.classList.remove('unread');
      el.querySelector('.notif-dot')?.remove();
      el.querySelector('.notif-read-btn')?.remove();
    }
    await refreshNotifBadge();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function refreshNotifBadge() {
  try {
    const unread = await api('GET', '/api/notifications/unread');
    const badge  = document.getElementById('notif-badge');
    if (unread.length > 0) {
      badge.textContent = unread.length;
      badge.style.display = 'inline-block';
    } else {
      badge.style.display = 'none';
    }
  } catch {}
}

// ══════════════════════════════════════════
// MODALS
// ══════════════════════════════════════════
function openModal(id)  { document.getElementById(id).classList.add('open'); }
function closeModal(id) { document.getElementById(id).classList.remove('open'); }

document.querySelectorAll('.modal-overlay').forEach(o => {
  o.addEventListener('click', e => { if (e.target === o) closeModal(o.id); });
});

// ══════════════════════════════════════════
// UTILS
// ══════════════════════════════════════════
function formatMoney(v) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0);
}

function formatDate(s) {
  if (!s) return '—';
  return new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(s));
}

function txTypeBadge(t) {
  if (!t) return '';
  return { DEPOSIT: 'badge-deposit', WITHDRAWAL: 'badge-withdraw', TRANSFER: 'badge-transfer' }[t] || '';
}

function statusBadge(s) {
  return { COMPLETED: 'badge-completed', PENDING: 'badge-pending', FAILED: 'badge-failed' }[s] || '';
}

function notifLabel(type) {
  const labels = { TRANSACTION: 'Transaction Alert', SYSTEM: 'System Notice', WARNING: 'Account Warning', INFO: 'Information' };
  return labels[type] || 'Notification';
}

function escHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function setLoading(btn, loading) {
  btn.disabled = loading;
  btn._originalText = btn._originalText || btn.innerHTML;
  btn.innerHTML = loading ? '<span class="spinner"></span> Please wait…' : btn._originalText;
}
