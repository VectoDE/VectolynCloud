// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { AuthProvider } from "@/lib/auth";
import { I18nProvider } from "@/lib/i18n";
import { Shell } from "@/components/Shell";
import "./globals.css";

export const metadata: Metadata = {
  title: "VectolynCloud Panel",
  description: "Web panel of VectolynCloud",
  // The panel belongs to one node and is never meant for search engines.
  robots: { index: false, follow: false },
  icons: { icon: "/mark-on-dark.svg" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  colorScheme: "light dark",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen antialiased">
        <I18nProvider>
          <AuthProvider>
            <Shell>{children}</Shell>
          </AuthProvider>
        </I18nProvider>
      </body>
    </html>
  );
}
