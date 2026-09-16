const {test} = require('node:test');
const assert = require('node:assert/strict');
const {appStore, filterOptions, searchableSelect} = require('../../main/resources/static/app.js');
const options = [
  {value:'au',label:'澳大利亚',keywords:'Australia AU'},
  {value:'us',label:'美国',keywords:'United States US'},
  {value:'hk',label:'香港',keywords:'Hong Kong SAR China HK'}
];
test('dropdown filtering matches partial Chinese, English, codes and multiple terms without changing the selection', () => {
  assert.deepEqual(filterOptions(options,'港').map(o=>o.value),['hk']);
  assert.deepEqual(filterOptions(options,'  hOnG k  ').map(o=>o.value),['hk']);
  assert.deepEqual(filterOptions(options,'ＨＫ').map(o=>o.value),['hk']);
  assert.equal(filterOptions(options,'us')[0].value,'us');
  assert.deepEqual(filterOptions(options,'not-a-country'),[]);
  const select=searchableSelect('test','地区',()=>options);
  select.value='us'; select.filter='香港';
  assert.equal(select.selectedLabel,'美国'); assert.equal(select.filteredOptions[0].value,'hk');
});
test('keyboard selection works on filtered options, empty results and disabled controls', () => {
  let disabled=false, focused=0;
  const select=searchableSelect('test','地区',()=>options,()=>disabled);
  select.$refs={trigger:{focus:()=>focused++},list:{querySelector:()=>null},filter:{focus:()=>{}}};
  select.$nextTick=fn=>fn();
  select.value='us'; select.show(); select.move(1); select.chooseActive();
  assert.equal(select.value,'hk'); assert.equal(select.open,false); assert.equal(focused,1);
  select.filter='absent'; select.chooseActive(); assert.equal(select.value,'hk');
  disabled=true; select.show(); select.choose(options[0]);
  assert.equal(select.open,false); assert.equal(select.value,'hk');
});
test('all four dropdown sources have searchable labels and aliases', () => {
  const state=appStore(); state.storefronts=[{code:'hk',nameZh:'香港',nameEn:'Hong Kong'}];
  assert.equal(filterOptions(state.storefrontOptions,'hong')[0].value,'hk');
  assert.equal(filterOptions(state.regionOptions,'euro')[0].value,'欧洲');
  assert.equal(filterOptions(state.sortOptions,'降序')[0].value,'cnyDesc');
});
test('search loading clears after failures and empty responses', async () => {
  const state=appStore(); state.input='Notes';
  global.axios={post:async()=>{assert.equal(state.pageLoading,true); throw new Error('network failed');}};
  await state.search(); assert.equal(state.pageLoading,false); assert.equal(state.error,'network failed');
  global.axios={post:async()=>({data:{code:0,data:[]}})};
  await state.search(); assert.equal(state.pageLoading,false); assert.match(state.notice,/未找到/);
});
test('cancelled search cannot replace a newer search or clear its loading state', async () => {
  const requests=[];
  global.axios={post:(_url,_data,config)=>new Promise(resolve=>requests.push({resolve,signal:config.signal}))};
  const state=appStore(); state.input='first'; const first=state.search();
  assert.equal(state.pageLoading,true); state.cancelLoading(); assert.equal(state.pageLoading,false);
  assert.equal(requests[0].signal.aborted,true);
  state.input='second'; const second=state.search();
  requests[0].resolve({data:{code:0,data:[{appId:'old'}]}}); await first;
  assert.equal(state.pageLoading,true); assert.deepEqual(state.candidates,[]);
  requests[1].resolve({data:{code:0,data:[{appId:'new'}]}}); await second;
  assert.equal(state.pageLoading,false); assert.deepEqual(state.candidates,[{appId:'new'}]);
});
test('price loading hands over to progressive results and closes for completed failures', () => {
  const state=appStore(); state.loading=true;
  assert.equal(state.pageLoading,true);
  state.snapshot={app:{name:'Selected App'},progress:{complete:false}};
  assert.equal(state.pageLoading,false); assert.equal(state.loading,true);
  state.snapshot={app:null,progress:{complete:true}}; assert.equal(state.pageLoading,false);
});
