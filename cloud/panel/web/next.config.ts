// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import type { NextConfig } from "next";

// Static export: `next build` writes plain files to out/, which the Gradle build copies into the
// cloud jar. The panel server (cloud/panel, Netty) serves them; there is no Node.js at runtime.
// trailingSlash makes every page a folder with index.html, so the server maps /services/ to
// services/index.html without rewrite rules.
const config: NextConfig = {
  output: "export",
  trailingSlash: true,
  reactStrictMode: true,
  poweredByHeader: false,
  images: { unoptimized: true },
  // The panel never loads anything from another origin; source maps stay out of the jar.
  productionBrowserSourceMaps: false,
};

export default config;
