// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Runs before the first paint so a chosen colour mode does not flash. Without a choice the system setting applies.
(function () {
  try {
    var t = localStorage.getItem('vectolyn-theme');
    if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
    // paused motion is known before the first paint, so the page transition and the load sequence stay off
    if (localStorage.getItem('vectolyn-motion') === 'paused') document.documentElement.classList.add('paused');
  } catch (e) { /* storage blocked: system setting */ }
}());
