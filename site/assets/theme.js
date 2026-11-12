// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Runs before the first paint so a chosen colour mode does not flash. Without a choice the system setting applies.
(function () {
  try {
    var t = localStorage.getItem('vectolyn-theme');
    if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
  } catch (e) { /* storage blocked: system setting */ }
}());
