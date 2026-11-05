/* DATETIME/LocalDateTime은 한국 벽시각, offset/Z가 있는 값은 실제 순간으로 해석한다. */
(function () {
  "use strict";
  const formatter = new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23"
  });

  function parse(value) {
    if (typeof value !== "string" || !value.trim()) return null;
    let input = value.trim().replace(" ", "T");
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(input)) return null;
    if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(input)) input += "+09:00";
    const date = new Date(input);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function parts(value) {
    const date = parse(value);
    if (!date) return null;
    return Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
  }

  function time(value) {
    const p = parts(value);
    return p ? p.hour + ":" + p.minute : "";
  }

  function dateTime(value) {
    const p = parts(value);
    return p ? p.year + "-" + p.month + "-" + p.day + " " + p.hour + ":" + p.minute : "";
  }

  function pickup(value) {
    const p = parts(value);
    return p ? p.month + "월 " + p.day + "일 " + p.hour + ":" + p.minute : "-";
  }

  window.CakeShopTime = Object.freeze({ parse, time, dateTime, pickup });
})();
