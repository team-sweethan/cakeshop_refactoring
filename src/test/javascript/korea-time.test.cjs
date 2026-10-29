const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');

const source = path.resolve(__dirname, '../../main/resources/static/js/korea-time.js');
for (const timezone of ['UTC', 'Asia/Seoul', 'America/Los_Angeles']) {
  test('한국 표시가 브라우저 시간대에 영향받지 않는다: ' + timezone, () => {
    const probe = `
      const fs = require('node:fs');
      const vm = require('node:vm');
      const context = {window: {}, Intl, Date};
      vm.runInNewContext(fs.readFileSync(process.argv[1], 'utf8'), context);
      const t = context.window.CakeShopTime;
      process.stdout.write(JSON.stringify([
        t.time('2026-10-29T00:05:00'),
        t.time('2026-10-28T15:05:00Z'),
        t.time('2026-10-29T00:05:00+09:00'),
        t.dateTime('2026-10-28T15:05:00Z'),
        t.pickup('2026-10-29T00:05:00.123456'),
        t.time('invalid'), t.time(null)
      ]));
    `;
    const result = spawnSync(process.execPath, ['-e', probe, source],
      {encoding: 'utf8', env: {...process.env, TZ: timezone}});
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), ['00:05', '00:05', '00:05',
      '2026-10-29 00:05', '10월 29일 00:05', '', '']);
  });
}
