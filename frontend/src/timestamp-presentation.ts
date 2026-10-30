const japanTime = new Intl.DateTimeFormat('ja-JP', {
  timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

// Recorded instants only. Business dates remain the server's YYYY-MM-DD strings.
export function formatJstTimestamp(value: string) {
  return `${japanTime.format(new Date(value))}（日本時間 / JST）`;
}
