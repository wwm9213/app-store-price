const {test} = require('node:test');
const assert = require('node:assert/strict');
const {appStore,cacheSnapshot,cachedSnapshot,snapshotFresh,retainPrices,exportCsv,FRESH_TTL} = require('../../main/resources/static/app.js');
function setup() {
    const values=new Map();
    global.localStorage={getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v)};
    global.EventSource=class {
        static instances=[];
        constructor(url){this.url=url;this.listeners={};this.constructor.instances.push(this);}
        addEventListener(name,fn){this.listeners[name]=fn;}
        close(){this.closed=true;}
        emit(name,data){this.listeners[name]({data:JSON.stringify(data)});}
    };
    const state=appStore();state.mainstream=['us']; return state;
}
function snapshot(age=0, amount=4.99) {
    const fetchedAt=new Date(Date.now()-age).toISOString();
    const local={amount,currency:'USD',formatted:'$'+amount};
    const app={appId:'1',name:'Test',developer:'Dev'};
    return {appId:'1',app,areas:['us'],queryId:'query-1',startedAt:fetchedAt,
        regions:[{area:'us',status:'AVAILABLE',app,price:local,items:[],fetchedAt,sourceUrl:'https://apps.apple.com/us/app/id1'}],retainedRegions:[],
        products:[{productKey:'app',name:'软件本体',matchStatus:'EXACT',names:{},prices:[{area:'us',areaName:'美国',name:'软件本体',local,cny:35,usd:amount,fetchedAt,sourceUrl:'url'}]}],
        progress:{complete:true,total:1,completed:1,available:1,failed:0,unavailable:0},exchangeRates:{asOf:'2026-09-16'}};
}
function pending(id='query-new') {
    return {...snapshot(),regions:[],products:[],retainedRegions:[],queryId:id,progress:{complete:false,total:1,completed:0,available:0,failed:0,unavailable:0}};
}
test('fresh cache uses observation age; a new saved time cannot extend 15 minutes', async()=>{
    const state=setup(), data=snapshot(FRESH_TTL-10000);cacheSnapshot(data);
    global.axios={get:()=>assert.fail('fresh cache should not call backend')};
    await state.query('1');assert.equal(state.fromCache,true);assert.equal(state.loading,false);
    assert.equal(snapshotFresh(data),true);assert.equal(snapshotFresh(snapshot(FRESH_TTL+1)),false);
});
test('stale cache remains visible while background query obtains a stable SSE query id',async()=>{
    const state=setup(),old=snapshot(FRESH_TTL+1000);cacheSnapshot(old);
    let resolve;global.axios={get:(url)=>{assert.match(url,/refresh=false/);return new Promise(r=>resolve=r);}};
    const request=state.query('1');
    assert.equal(state.loading,true);assert.equal(state.pageLoading,false);assert.equal(state.rows[0].local.amount,4.99);
    assert.equal(state.retained('us'),true);assert.equal(state.lowest,null);
    assert.match(state.queryStatus,/缓存价格仍可查看/);
    resolve({data:{code:0,data:pending()}});await request;
    const stream=EventSource.instances[0];assert.match(stream.url,/queryId=query-new/);assert.match(stream.url,/refresh=false/);
    const result=snapshot(0,5.99);
    stream.emit('region',{...result,progress:{...result.progress,complete:false}});
    assert.equal(state.queryStatus,'正在获取所选地区价格','new observations must not be labelled cached');
    stream.emit('complete',result);
    assert.equal(state.loading,false);assert.equal(state.rows[0].local.amount,5.99);assert.equal(state.retained('us'),false);
});
test('force refresh bypasses even fresh browser cache and only refreshes on initial HTTP request',async()=>{
    const state=setup();cacheSnapshot(snapshot());let url;
    global.axios={get:async u=>{url=u;return {data:{code:0,data:pending('forced')}};}};
    await state.query('1',{refresh:true});assert.match(url,/refresh=true/);
    assert.equal(state.rows.length,1,'previous prices stay visible');
    const stream=EventSource.instances[0];assert.match(stream.url,/refresh=false/);assert.match(stream.url,/queryId=forced/);
    stream.emit('complete',snapshot(0,6.99));assert.equal(cachedSnapshot('1',['us']).products[0].prices[0].local.amount,6.99);
});
test('failed server refresh preserves local price and timestamp without claiming success or ranking it',async()=>{
    const state=setup(),old=snapshot(FRESH_TTL+1000);cacheSnapshot(old);
    const failure={...pending(),regions:[{area:'us',status:'RATE_LIMITED',message:'429',fetchedAt:new Date().toISOString()}],progress:{complete:true,total:1,completed:1,available:0,unavailable:0,failed:1}};
    global.axios={get:async()=>({data:{code:0,data:failure}})};
    await state.query('1');assert.equal(state.rows[0].fetchedAt,old.regions[0].fetchedAt);
    assert.equal(state.snapshot.progress.failed,1);assert.equal(state.lowest,null);assert.equal(state.delta(state.rows[0]),'等待最新价格');
    assert.match(state.priceState(state.rows[0]),/更新失败，显示上次数据/);
    const csv=exportCsv(state.snapshot);assert.match(csv,/RetainedPrice/);assert.match(csv,/true","RATE_LIMITED/);assert.match(csv,/429/);
    assert.equal(snapshotFresh(cachedSnapshot('1',['us'])),false);
});
test('network failure keeps old data and an incomplete export state',async()=>{
    const state=setup();cacheSnapshot(snapshot());
    global.axios={get:async()=>{throw Error('offline');}};
    await state.query('1',{refresh:true});assert.equal(state.rows.length,1);assert.equal(state.loading,false);
    assert.equal(state.snapshot.progress.complete,false);assert.match(state.queryStatus,/更新失败/);
    assert.match(exportCsv(state.snapshot),/CONNECTION_FAILED/);
});
test('successful response removes obsolete items and unavailable region removes old prices',()=>{
    const old=snapshot();
    const unavailable={...pending(),regions:[{area:'us',status:'UNAVAILABLE',fetchedAt:new Date().toISOString()}],progress:{complete:true}};
    assert.equal(retainPrices(unavailable,old).products.length,0);
    const noIap={...snapshot(),products:[]};assert.equal(retainPrices(noIap,old).products.length,0);
    const other={...pending(),appId:'2'};assert.equal(retainPrices(other,old).products.length,0);
});
test('server retained prices survive a cold browser and recover when next update succeeds',()=>{
    const state=setup(),old=snapshot(FRESH_TTL+1000);
    const failed={...old,retainedRegions:old.regions,regions:[{area:'us',status:'FETCH_FAILED',fetchedAt:new Date().toISOString()}],progress:{...old.progress,failed:1,available:0}};
    state.applySnapshot(failed);assert.equal(state.rows.length,1);assert.equal(state.retained('us'),true);
    state.applySnapshot(snapshot(0,7.99));assert.equal(state.retained('us'),false);assert.equal(state.rows[0].local.amount,7.99);
});
test('late SSE completion cannot replace another selected App',async()=>{
    const state=setup();global.axios={get:async()=>({data:{code:0,data:pending()}})};
    await state.query('1');const first=EventSource.instances[0];
    const other={...snapshot(),appId:'2',app:{name:'Other'}};global.axios={get:async()=>({data:{code:0,data:other}})};
    await state.query('2');first.emit('complete',snapshot());assert.equal(state.snapshot.appId,'2');assert.equal(state.loading,false);
});
