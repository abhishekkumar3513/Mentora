/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: { teal: { erp: '#7fcfcf' }, hdr: '#d9d9d9', row: '#ebebeb' },
      fontFamily: { sans: ['Open Sans', 'Segoe UI', 'system-ui', 'sans-serif'] },
    },
  },
  plugins: [],
}
