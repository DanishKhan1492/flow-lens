import type { Metadata } from "next";
import { Fira_Code, Michroma } from "next/font/google";
import "./globals.css";

const firaCode = Fira_Code({
  subsets:  ["latin"],
  variable: "--font-fira-code",
  display:  "swap",
  weight:   ["300", "400", "500", "600"],
});

const michroma = Michroma({
  subsets:  ["latin"],
  variable: "--font-michroma",
  display:  "swap",
  weight:   "400",
});

export const metadata: Metadata = {
  title:       "FlowLens — Spring Flow Visualizer",
  description: "Real-time method-level execution tracer for any JVM application",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${firaCode.variable} ${michroma.variable}`}>
      <body>{children}</body>
    </html>
  );
}
