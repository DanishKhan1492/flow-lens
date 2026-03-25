import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        mono:    ['"Fira Code"', '"JetBrains Mono"', "ui-monospace", "monospace"],
        display: ["Michroma", "Syncopate", "sans-serif"],
      },
      colors: {
        "neon-cyan":   "#00F3FF",
        "warn-orange": "#FF8C00",
        "deep":        "#0D1117",
        "surface":     "#161B22",
      },
      gridTemplateColumns: {
        dashboard: "18rem 1fr 20rem",
      },
      keyframes: {
        "row-flash": {
          "0%":    { backgroundColor: "rgba(0,243,255,0.12)" },
          "100%":  { backgroundColor: "transparent" },
        },
        "status-pulse": {
          "0%, 100%": { boxShadow: "0 0 0 0 rgba(0,243,255,0.6)" },
          "70%":       { boxShadow: "0 0 0 6px rgba(0,243,255,0)" },
        },
        "scan-h": {
          "0%":   { transform: "translateX(-200%)" },
          "100%": { transform: "translateX(400%)" },
        },
      },
      animation: {
        "row-flash":    "row-flash 1.2s ease-out forwards",
        "status-pulse": "status-pulse 2s ease-in-out infinite",
        "scan-h":       "scan-h 5s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
