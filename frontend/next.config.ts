import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export — output is copied into the starter JAR and served by Spring Boot.
  // assetPrefix "./" generates relative asset URLs so the page works correctly
  // regardless of any Spring Boot context path (/app-name/flow-lens/, /flow-lens/, etc.)
  output: "export",
  assetPrefix: "./",
  trailingSlash: true,
  images: { unoptimized: true },
};

export default nextConfig;
