import createMDX from "@next/mdx";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  // The wiki is written as `page.mdx` under `app/wiki`.
  pageExtensions: ["ts", "tsx", "mdx"],
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  allowedDevOrigins: ["localhost", "172.27.56.94"],
};

// Turbopack takes plugins by name: functions cannot cross into Rust.
const withMDX = createMDX({
  options: {
    remarkPlugins: ["remark-gfm"],
    rehypePlugins: ["rehype-slug"],
  },
});

export default withMDX(nextConfig);
