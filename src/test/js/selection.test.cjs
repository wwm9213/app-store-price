const {test} = require('node:test');
const assert = require('node:assert/strict');
const {appStore, groupProducts, cacheSnapshot, cachedSnapshot, cacheEntries, CACHE_KEY, CACHE_TTL} = require('../../main/resources/static/app.js');
const xProducts = require('./fixtures/x-products.json');

function storage() {
  const data = new Map();
  return {getItem:key => data.get(key) ?? null, setItem:(key,value) => data.set(key,value), removeItem:key => data.delete(key)};
}
function snapshot(id, areas, now) {
  return {appId:id,areas,products:[],progress:{complete:true},regions:areas.map(area => ({area,fetchedAt:new Date(now).toISOString()}))};
}
test('name search always waits for an explicit App choice, even with one candidate', async () => {
  global.localStorage = storage();
  const state = appStore();
  assert.equal(state.input,''); assert.equal(state.currentId,''); assert.equal(state.scope,'mainstream');
  const candidates = [{appId:'1',appName:'Same name'},{appId:'2',appName:'Same name'}];
  global.axios = {post:async () => ({data:{code:0,data:candidates}})};
  const queries = []; state.query = id => queries.push(id);
  state.input = 'Same name'; await state.search();
  assert.deepEqual(queries,[]); assert.deepEqual(state.candidates,candidates);
  state.chooseApp(candidates[1]); assert.deepEqual(queries,['2']);
  candidates.splice(1); await state.search(); assert.deepEqual(queries,['2']);
});
test('X same-name purchases share one entry while differing region quotes remain visible', () => {
  const before = JSON.stringify(xProducts), groups = groupProducts(xProducts);
  const promote = groups.filter(p => p.name === 'Promote Post');
  assert.equal(promote.length,1); assert.equal(promote[0].rows.length,3);
  assert.equal(promote[0].rows.find(p => p.area==='at').quotes.length,2);
  assert.equal(promote[0].comparable,false);
  assert.equal(groups.filter(p => p.creator).length,2);
  assert.equal(groups.find(p => p.name==='X Premium (Monthly)').comparable,true);
  assert.equal(JSON.stringify(xProducts),before,'source and exported identities stay intact');
});
test('display duplicates fold but distinct known product identities are retained', () => {
  const source = xProducts.find(p => p.name==='Promote Post');
  const duplicate = structuredClone(source); duplicate.productKey='duplicate'; duplicate.name='  PROMOTE  POST  ';
  duplicate.prices[0].local.amount=String(duplicate.prices[0].local.amount);
  const group = groupProducts([source,duplicate]);
  assert.equal(group.length,1); assert.equal(group[0].rows[0].quotes.length,1);
  const exact = [source,duplicate].map(p => ({...p,matchStatus:'EXACT'}));
  assert.equal(groupProducts(exact)[0].rows[0].quotes.length,2);
});
test('switching selected App replaces its purchase groups rather than combining Apps', () => {
  global.localStorage=storage(); const state=appStore();
  state.applySnapshot({...snapshot('1',['us'],Date.now()),products:xProducts});
  state.applySnapshot({...snapshot('2',['us'],Date.now()),products:[{...xProducts[0],name:'Another App purchase'}]});
  assert.deepEqual(state.groups.map(p => p.name),['Another App purchase']);
});
test('cache isolates App and exact region set, rejects partials, and expires from observation time', () => {
  const s=storage(), now=Date.now(), data=snapshot('1',['cn','us'],now-1000);
  assert.equal(cacheSnapshot(data,s,now),true);
  assert.deepEqual(cachedSnapshot('1',['us','cn'],s,now),data);
  assert.equal(cachedSnapshot('1',['us'],s,now),null);
  assert.equal(cachedSnapshot('2',['us','cn'],s,now),null);
  assert.equal(cacheSnapshot({...data,progress:{complete:false}},s,now),false);
  assert.equal(cachedSnapshot('1',['cn','us'],s,now+CACHE_TTL-1000),null);
});
test('cache retains five recent snapshots and clearing it preserves region preferences', () => {
  const s=storage(), now=Date.now(); global.localStorage=s;
  s.setItem('myAreas',JSON.stringify(['cn','hk']));
  for(let i=1;i<=6;i++) cacheSnapshot(snapshot(String(i),['us'],now),s,now);
  assert.equal(cacheEntries(s,now).length,5); assert.equal(cachedSnapshot('1',['us'],s,now),null);
  const state=appStore(); state.clearCache();
  assert.equal(s.getItem(CACHE_KEY),null); assert.deepEqual(state.myAreas,['cn','hk']);
  assert.deepEqual(JSON.parse(s.getItem('myAreas')),['cn','hk']);
});
