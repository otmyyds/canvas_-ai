import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#172026",
        mist: "#f4f7f6",
        spruce: "#1e6b5c",
        coral: "#d95d4f",
        amber: "#d6a43a",
        violet: "#7b5ea7"
      },
      boxShadow: {
        soft: "0 14px 40px rgba(23, 32, 38, 0.10)"
      }
    }
  },
  plugins: []
};

export default config;
