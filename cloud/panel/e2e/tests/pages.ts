// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later

/** Every page of the panel with the heading it must show once its data is there. */
export const PAGES: { path: string; heading: RegExp; ready?: string }[] = [
  { path: "/", heading: /^Panel$/ },
  { path: "/services/", heading: /^Services$/, ready: "lobby-1" },
  { path: "/services/console/?id=lobby-1", heading: /Console/ },
  { path: "/services/files/?id=lobby-1", heading: /Files/, ready: "server.properties" },
  { path: "/players/", heading: /^Players$/ },
  { path: "/config/", heading: /^Configuration$/ },
  { path: "/activity/", heading: /^Activity$/, ready: "bedwars" },
  { path: "/users/", heading: /^Users and roles$/, ready: "owner" },
  { path: "/account/", heading: /^My account$/ },
];
