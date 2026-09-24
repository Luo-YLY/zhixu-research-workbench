<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string, string> = {
  chat: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-3 3V11.5A8.5 8.5 0 0 1 9.5 3h3a8.5 8.5 0 0 1 8.5 8.5z M7 10h8 M7 14h5',
  quote: 'M10 5H4v7h4c0 3-2 5-4 6 M20 5h-6v7h4c0 3-2 5-4 6',
  send: 'M22 2L9 15 M22 2l-7 20-6-7-7-6 20-7z',
  history: 'M3 11a9 9 0 1 1 2 7 M3 4v7h7 M12 7v5l4 2',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  folder: 'M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z',
  flow: 'M5 5h4v4H5z M15 15h4v4h-4z M7 9v8h8 M9 7h8v8',
  check: 'M5 12l4 4L19 6',
  shield: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3z M8 12l3 3 5-5',
  map: 'M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3V6z M9 3v15 M15 6v15',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  plus: 'M12 5v14 M5 12h14',
  play: 'M8 4l12 8-12 8V4z',
  clock: 'M12 8v5l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6z M14 2v6h6 M8 13h8 M8 17h5',
  chevron: 'M9 5l7 7-7 7',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M5.6 7a8 8 0 0 1 13.2-1L20 9 M4 15l1.2 3A8 8 0 0 0 18.4 17',
  close: 'M6 6l12 12 M6 18L18 6',
  download: 'M12 3v12 M7 10l5 5 5-5 M5 16v4h14v-4',
  book: 'M12 5c-3-2-6-2-10-1v15c4-1 7-1 10 1 3-2 6-2 10-1V4c-4-1-7-1-10 1z M12 5v15',
  alert: 'M12 9v4 M12 17h.01 M10.3 3.7L1.8 18.3A2 2 0 0 0 3.5 21h17a2 2 0 0 0 1.7-2.7L13.7 3.7a2 2 0 0 0-3.4 0z',
  server: 'M4 3h16v7H4z M4 14h16v7H4z M7 6.5h.01 M7 17.5h.01 M11 6.5h6 M11 17.5h6',
  stop: 'M5 5h14v14H5z',
}
</script>
<template><svg :width="size || 20" :height="size || 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.file" /></svg></template>
