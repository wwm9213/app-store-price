const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseAppInput, percentage, exportCsv } = require('../../main/resources/static/app.js');
test('URL / ID and search name stay distinct', () => {
  assert.equal(parseAppInput('https://apps.apple.com/hk/app/xxx/id1546947240?l=en'), '1546947240');
  assert.equal(parseAppInput('1546947240'), '1546947240');
  assert.equal(parseAppInput('Floating Clock'), null);
  assert.throws(() => parseAppInput('https://apps.apple.com.evil.test/app/id1'));
});
test('benchmark missing and zero never produce fabricated percentages', () => {
  assert.equal(percentage(5, 0), null); assert.equal(percentage(null, 5), null); assert.equal(percentage(4, 5), -20);
});
test('CSV preserves raw strings, unavailable states, partial snapshots and formula safety', () => {
  const text=exportCsv({app:{name:'=1+1'},appId:'1',progress:{complete:false},exchangeRates:{asOf:'today'},products:[{productKey:'iap',prices:[{name:'A,"B"',area:'tr',areaName:'土耳其',local:{amount:199.99,currency:'TRY',formatted:'₺199,99'},matchStatus:'INFERRED'}]}],regions:[{area:'cn',status:'UNAVAILABLE'}]});
  assert.ok(text.startsWith('\uFEFF'));assert.ok(text.includes("'=1+1"));assert.ok(text.includes('A,""B""'));assert.ok(text.includes('₺199,99'));assert.ok(text.includes('UNAVAILABLE'));assert.ok(text.includes('false'));
});
