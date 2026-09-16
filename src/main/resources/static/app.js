/* Source snapshots stay intact; UI groups only their display entries. */
function readSetting(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
function saveSetting(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} }
function parseAppInput(input) {
    const value = input.trim();
    if (/^(id)?\d{1,20}$/.test(value)) return value.replace(/^id/, '');
    if (/^https?:\/\//i.test(value)) {
        const url = new URL(value);
        const match = url.pathname.match(/(?:^|\/)id(\d{1,20})(?:\/|$)/);
        if (url.protocol !== 'https:' || url.hostname !== 'apps.apple.com' || url.username || url.password || url.port || !match) throw new Error('请输入有效的 App Store 应用链接');
        return match[1];
    }
    return null;
}
function percentage(value, base) { return value == null || base == null || Number(base) <= 0 ? null : (Number(value) - Number(base)) / Number(base) * 100; }
function csvCell(value) {
    let text = value == null ? '' : String(value);
    if (/^[=+\-@\t\r]/.test(text)) text = "'" + text;
    return '"' + text.replaceAll('"', '""') + '"';
}
function exportCsv(snapshot) {
    const columns = ['App','AppID','Product','ProductKey','Country','CountryCode','LocalPrice','Currency','FormattedPrice','CNY','USD','ExchangeRate','RateAsOf','FetchedAt','Status','MatchStatus','SourceURL','QueryComplete','RecordType','MatchEvidence','Type','Period','Issues','Coverage','QueryStartedAt','CompletedRegions','TotalRegions','RetainedPrice','UpdateStatus','LastAttemptAt','UpdateError'];
    const rows = [columns];
    const update = area => {
        const r = snapshot.regions.find(r => r.area === area);
        return [(snapshot.retainedRegions || []).some(r => r.area === area), snapshot.refreshError ? 'CONNECTION_FAILED' : r?.status || 'PENDING', r?.fetchedAt || '', snapshot.refreshError || r?.message || ''];
    };
    for (const product of snapshot.products) for (const p of product.prices) rows.push([
        snapshot.app?.name, snapshot.appId, p.name, product.productKey, p.areaName, p.area, p.local.amount, p.local.currency, p.local.formatted,
        p.cny, p.usd, p.exchangeRate, p.rateAsOf, p.fetchedAt, p.local.amount == null ? 'PARSE_FAILED' : 'AVAILABLE', p.matchStatus, p.sourceUrl, snapshot.progress.complete,
        'PRICE', p.matchEvidence, product.type, product.period, '', '', snapshot.startedAt, snapshot.progress.completed, snapshot.progress.total, ...update(p.area)
    ]);
    for (const region of snapshot.regions) rows.push([
        region.app?.name || snapshot.app?.name, snapshot.appId, '', '', '', region.area, '', '', '', '', '', '', snapshot.exchangeRates?.asOf, region.fetchedAt, region.status, '', region.sourceUrl, snapshot.progress.complete,
        'REGION', '', '', '', [region.message, ...(region.issues || [])].filter(Boolean).join('; '), region.iapCoverage, snapshot.startedAt, snapshot.progress.completed, snapshot.progress.total, ...update(region.area)
    ]);
    return '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n');
}

function normalizeName(name) {
    return name.normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase();
}
function groupProducts(products) {
    const groups = new Map();
    for (const product of products) {
        const key = product.productKey === 'app' ? 'app' : `name:${normalizeName(product.name)}`;
        if (!groups.has(key)) groups.set(key, {key, name:product.name, members:[], creator:product.name.startsWith('@')});
        groups.get(key).members.push(product);
    }
    return [...groups.values()].map(group => {
        const areas = new Map();
        for (const member of group.members) for (const price of member.prices) {
            if (!areas.has(price.area)) areas.set(price.area, new Map());
            const signature = JSON.stringify([price.local.currency, price.local.amount == null ? null : Number(price.local.amount), member.type, member.period,
                member.matchStatus === 'EXACT' ? member.productKey : null]);
            // Repeated identical observations share a row; raw snapshots/export retain every record.
            if (!areas.get(price.area).has(signature)) areas.get(price.area).set(signature, {
                ...price, key:signature, type:member.type, period:member.period
            });
        }
        const rows = [...areas.values()].map(prices => {
            const quotes = [...prices.values()].sort((a,b) => (a.cny ?? Infinity) - (b.cny ?? Infinity));
            return {...quotes[0], quotes};
        });
        const comparable = group.members.length === 1 && group.members[0].matchStatus !== 'MATCH_UNCERTAIN'
            && rows.every(row => row.quotes.length === 1);
        const summarized = group.members.length > 1 || rows.some(row => row.quotes.length > 1);
        return {...group, rows, comparable, summarized, matchStatus:comparable ? group.members[0].matchStatus : 'MATCH_UNCERTAIN'};
    }).sort((a,b) => (b.key === 'app') - (a.key === 'app') || Number(a.creator)-Number(b.creator)
        || b.rows.length-a.rows.length || a.name.localeCompare(b.name));
}

const CACHE_KEY = 'appStorePrice.snapshots.v2';
const CACHE_TTL = 6 * 60 * 60 * 1000;
const FRESH_TTL = 15 * 60 * 1000;
function snapshotFresh(snapshot, now = Date.now()) {
    return !!snapshot?.progress.complete && !snapshot.refreshError && !(snapshot.retainedRegions || []).length
        && snapshot.regions.length === snapshot.areas.length && snapshot.regions.every(r =>
            ['AVAILABLE','UNAVAILABLE'].includes(r.status) && Date.parse(r.fetchedAt) + FRESH_TTL > now);
}
function retainPrices(data, previous, now = Date.now()) {
    const same = previous?.appId === data.appId;
    const retained = new Map();
    for (const r of [...(same ? [...(previous.retainedRegions || []), ...previous.regions] : []), ...(data.retainedRegions || [])]) {
        if (r.status !== 'AVAILABLE' || !data.areas.includes(r.area) || Date.parse(r.fetchedAt) + CACHE_TTL <= now) continue;
        if (!retained.has(r.area) || Date.parse(retained.get(r.area).fetchedAt) < Date.parse(r.fetchedAt)) retained.set(r.area,r);
    }
    for (const r of data.regions) if (['AVAILABLE','UNAVAILABLE'].includes(r.status)) retained.delete(r.area);
    const localAreas = new Set([...retained.values()].filter(r => !(data.retainedRegions || [])
        .some(server => server.area === r.area && Date.parse(server.fetchedAt) >= Date.parse(r.fetchedAt))).map(r => r.area));
    const products = new Map(data.products.map(p => [p.productKey,{...p,prices:p.prices.filter(r => !localAreas.has(r.area))}]));
    if (same) for (const p of previous.products) {
        const old = p.prices.filter(r => localAreas.has(r.area));
        if (!old.length) continue;
        const current = products.get(p.productKey);
        products.set(p.productKey,current ? {...current,prices:[...current.prices,...old]} : {...p,prices:old});
    }
    return {...data,app:data.app || (same ? previous.app : null),retainedRegions:[...retained.values()],
        products:[...products.values()].filter(p => p.prices.length)};
}
function cacheKey(id, areas) { return `${id}:${[...new Set(areas)].sort().join(',')}`; }
function cacheEntries(storage = localStorage, now = Date.now()) {
    try { return (JSON.parse(storage.getItem(CACHE_KEY)) || []).filter(e => e.expiresAt > now && e.snapshot?.progress?.complete); }
    catch { return []; }
}
function cachedSnapshot(id, areas, storage = localStorage, now = Date.now()) {
    return cacheEntries(storage, now).find(e => e.key === cacheKey(id, areas))?.snapshot || null;
}
function cacheSnapshot(snapshot, storage = localStorage, now = Date.now()) {
    if (!snapshot.progress.complete || !snapshot.areas?.length) return false;
    const observed = [...snapshot.regions,...(snapshot.retainedRegions || [])].map(r => Date.parse(r.fetchedAt)).filter(Number.isFinite);
    const entry = {key:cacheKey(snapshot.appId, snapshot.areas), expiresAt:Math.min(now, ...observed) + CACHE_TTL, snapshot};
    if (JSON.stringify(entry).length > 1800000) return false;
    const entries = [entry, ...cacheEntries(storage, now).filter(e => e.key !== entry.key)].slice(0, 5);
    while (JSON.stringify(entries).length > 1800000) entries.pop();
    try { storage.setItem(CACHE_KEY, JSON.stringify(entries)); return true; } catch { return false; }
}

function filterOptions(options, query) {
    const terms = normalizeName(query).split(' ').filter(Boolean);
    const filtered = options.filter(option => {
        const text = normalizeName([option.label, option.value, option.keywords || ''].join(' '));
        return terms.every(term => text.includes(term));
    });
    return filtered.sort((a,b) => Number(normalizeName(b.value) === normalizeName(query)) - Number(normalizeName(a.value) === normalizeName(query)));
}

function searchableSelect(id, label, source, disabled = () => false) {
    return {
        id, label, value:'', open:false, filter:'', activeIndex:0,
        get options() { return source(); },
        get disabled() { return disabled(); },
        get selectedLabel() { return this.options.find(o => o.value === this.value)?.label || '请选择'; },
        get filteredOptions() { return filterOptions(this.options,this.filter); },
        get activeId() { return this.filteredOptions[this.activeIndex] ? `${this.id}-option-${this.activeIndex}` : null; },
        show(direction = 1) {
            if (this.disabled) return;
            this.filter = ''; this.open = true;
            const selected = this.options.findIndex(o => o.value === this.value);
            this.activeIndex = selected >= 0 ? selected : direction < 0 ? this.options.length - 1 : 0;
            this.$nextTick(() => { this.$refs.filter.focus(); this.scrollActive(); });
        },
        close(restoreFocus = false) {
            this.open = false;
            if (restoreFocus) this.$refs.trigger.focus();
        },
        toggle() { this.open ? this.close() : this.show(); },
        scrollActive() { this.$refs.list.querySelector('[data-active="true"]')?.scrollIntoView({block:'nearest'}); },
        move(direction) {
            const count = this.filteredOptions.length;
            if (!count) return;
            this.activeIndex = (this.activeIndex + direction + count) % count;
            this.$nextTick(() => this.scrollActive());
        },
        choose(option) { if (!option || this.disabled) return; this.value = option.value; this.close(true); },
        chooseActive() { this.choose(this.filteredOptions[this.activeIndex]); }
    };
}

function appStore() {
    return {
        input:'', searchArea:'us', storefronts:[], mainstream:[], scope:readSetting('queryScope','mainstream'),
        myAreas:readSetting('myAreas',[]), managing:false, draftAreas:[], areaSearch:'', cacheCount:0,
        candidates:[], searching:false, error:'', notice:'', snapshot:null, groups:[], stream:null, requestController:null,
        currentId:'', selectedKey:'app', queryAreas:[], sharedAreas:[], generation:0, loading:false, fromCache:false, requestStartedAt:0,
        productSearch:'', category:'main', benchmark:'us', sort:'cnyAsc', regionFilter:'', countrySearch:'',
        showUnavailable:false, detailMode:false, colorMode:readSetting('colorMode','system'),
        regions:['亚洲','欧洲','北美','南美','中东','非洲','大洋洲'],
        get storefrontOptions() { return this.storefronts.map(s => ({value:s.code,label:s.nameZh,keywords:`${s.nameEn} ${s.code.toUpperCase()}`})); },
        get regionOptions() { return [{value:'',label:'全部分区',keywords:'all'}, ...this.regions.map((r,i) => ({value:r,label:r,keywords:['Asia','Europe','North America','South America','Middle East','Africa','Oceania'][i]}))]; },
        sortOptions:[{value:'cnyAsc',label:'人民币 ↑',keywords:'升序 从低到高 CNY ascending'}, {value:'cnyDesc',label:'人民币 ↓',keywords:'降序 从高到低 CNY descending'}, {value:'local',label:'当地价格 · 同币种',keywords:'本币 local currency'}, {value:'area',label:'国家 / 地区',keywords:'country region'}],
        get pageLoading() { return this.searching || (this.loading && !this.snapshot?.app && !this.snapshot?.progress.complete); },
        async init() {
            this.applyTheme();
            matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => this.applyTheme());
            window.addEventListener('pagehide', () => this.disconnect());
            try {
                const data = (await axios.get('/api/v2/storefronts')).data.data;
                this.storefronts = data.storefronts; this.mainstream = data.mainstream;
                this.myAreas = [...new Set(this.myAreas)].filter(c => this.storefronts.some(s => s.code === c));
                if (!['mainstream','custom','all'].includes(this.scope) || (this.scope === 'custom' && !this.myAreas.length)) this.scope = 'mainstream';
                this.cacheCount = cacheEntries().length;
                // Only an explicit share URL supplies an app. Home never restores a previous input.
                const url = new URL(location.href), id = url.searchParams.get('appId');
                if (id) {
                    const parsed = parseAppInput(id);
                    if (!parsed) throw new Error('分享链接中的 App ID 无效');
                    const codes = (url.searchParams.get('areas') || '').split(',').filter(Boolean);
                    if (codes.length) {
                        if (codes.some(code => !this.storefronts.some(s => s.code === code))) throw new Error('分享链接中包含不支持的地区');
                        this.sharedAreas = [...new Set(codes)]; this.scope = 'shared';
                        this.input = parsed; this.query(parsed);
                    } else { this.input = parsed; this.query(parsed); }
                }
            } catch (e) { this.error = e.response?.data?.message || e.message; }
        },
        applyTheme() { document.documentElement.classList.toggle('dark', this.colorMode === 'dark' || (this.colorMode === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)); },
        toggleTheme() { this.colorMode = document.documentElement.classList.contains('dark') ? 'light' : 'dark'; saveSetting('colorMode',this.colorMode); this.applyTheme(); },
        get activeAreas() { return this.scope === 'shared' ? this.sharedAreas : this.scope === 'all' ? this.storefronts.map(s => s.code) : this.scope === 'custom' ? this.myAreas : this.mainstream; },
        setScope(value) {
            if (value === 'custom' && !this.myAreas.length) { this.openAreas(); return; }
            this.scope = value; saveSetting('queryScope',value);
            if (this.currentId) this.query(this.currentId);
        },
        openAreas() { this.draftAreas = [...this.myAreas]; this.areaSearch = ''; this.managing = true; this.cacheCount = cacheEntries().length; },
        toggleDraft(code) { this.draftAreas = this.draftAreas.includes(code) ? this.draftAreas.filter(c => c !== code) : [...this.draftAreas,code]; },
        get selectableAreas() {
            const q = this.areaSearch.trim().toLowerCase();
            return this.storefronts.filter(s => !q || [s.nameZh,s.nameEn,s.code].some(v => v.toLowerCase().includes(q)));
        },
        saveAreas() {
            this.myAreas = [...this.draftAreas]; saveSetting('myAreas',this.myAreas); this.managing = false;
            this.setScope(this.myAreas.length ? 'custom' : 'mainstream');
        },
        clearCache() {
            try { localStorage.removeItem(CACHE_KEY); this.cacheCount = 0; this.notice = '已清除浏览器查询缓存，地区偏好已保留'; }
            catch { this.error = '浏览器不允许访问本地存储'; }
        },
        area(code) { return this.storefronts.find(s => s.code === code) || {code,nameZh:code,nameEn:code}; },
        flag(code) { return code.toUpperCase().replace(/./g,c => String.fromCodePoint(c.charCodeAt(0)+127397)); },
        matches(code) {
            const s = this.area(code), q = this.countrySearch.trim().toLowerCase();
            return (!this.regionFilter || s.region === this.regionFilter) && (!q || [s.nameZh,s.nameEn,s.code].some(v => v.toLowerCase().includes(q)));
        },
        get product() { return this.groups.find(p => p.key === this.selectedKey); },
        get mainCount() { return this.groups.filter(p => !p.creator).length; },
        get creatorCount() { return this.groups.filter(p => p.creator).length; },
        get visibleProducts() {
            const q = this.productSearch.trim().toLowerCase();
            return this.groups.filter(p => (this.category === 'creators' ? p.creator : !p.creator)
                && (!q || p.name.toLowerCase().includes(q) || p.members.some(m => Object.values(m.names || {}).some(name => name.toLowerCase().includes(q)))));
        },
        selectProduct(p) { this.selectedKey = p.key; this.detailMode = false; },
        chooseApp(app) { this.input = app.appName; this.query(app.appId); },
        get rows() {
            const rows = [...(this.product?.rows || [])].filter(p => this.matches(p.area));
            const n = v => v == null ? Infinity : Number(v);
            rows.sort((a,b) => !this.product.comparable || this.sort === 'area' ? a.areaName.localeCompare(b.areaName,'zh-CN')
                : this.sort === 'local' ? a.local.currency.localeCompare(b.local.currency) || n(a.local.amount)-n(b.local.amount)
                : this.sort === 'cnyDesc' ? (a.cny == null)-(b.cny == null) || n(b.cny)-n(a.cny) : n(a.cny)-n(b.cny));
            return rows;
        },
        retained(area) { return (this.snapshot?.retainedRegions || []).some(r => r.area === area); },
        get queryStatus() {
            if (this.loading) return this.snapshot?.retainedRegions?.length ? '正在更新 · 缓存价格仍可查看' : '正在获取所选地区价格';
            if (this.snapshot?.refreshError) return '更新失败 · 已保留上次数据';
            if (this.snapshot?.progress.complete) return this.snapshot.progress.failed ? '更新完成 · 部分地区失败' : this.fromCache ? '缓存数据（15 分钟内）' : '更新完成';
            return '等待连接';
        },
        timeText(value) { return value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '未知'; },
        priceState(p) {
            if (!this.retained(p.area)) return '抓取于 ' + this.timeText(p.fetchedAt);
            const result = this.snapshot?.regions.find(r => r.area === p.area);
            return (this.snapshot?.refreshError || result ? '更新失败，显示上次数据' : '正在更新，显示缓存') + ' · ' + this.timeText(p.fetchedAt);
        },
        get validPrices() { return this.product?.comparable ? this.product.rows.filter(p => !this.retained(p.area) && p.cny != null && p.local.amount != null) : []; },
        get lowest() { return this.validPrices.length ? Math.min(...this.validPrices.map(p => Number(p.cny))) : null; },
        get highest() { return this.validPrices.length ? Math.max(...this.validPrices.map(p => Number(p.cny))) : null; },
        get failedRegions() { return (this.snapshot?.regions || []).filter(r => r.status !== 'AVAILABLE' && (this.showUnavailable || r.status !== 'UNAVAILABLE') && this.matches(r.area)); },
        get localDetails() { return [...(this.snapshot?.regions || []),...(this.snapshot?.retainedRegions || [])].filter(r => r.status === 'AVAILABLE' && this.matches(r.area)); },
        get favorites() { return this.myAreas.filter(code => this.queryAreas.includes(code)); },
        myPrice(code) { return this.product?.rows.find(p => p.area === code); },
        state(code) {
            const r = this.snapshot?.regions.find(r => r.area === code);
            return !r ? '等待查询' : r.status === 'AVAILABLE' ? '未公开此项目' : this.statusName(r.status);
        },
        statusName(status) { return {AVAILABLE:'可用',UNAVAILABLE:'该区不可用',FETCH_FAILED:'抓取失败',PARSE_FAILED:'解析失败',RATE_LIMITED:'请求受限'}[status] || status; },
        money(value,currency='CNY') { return value == null ? '—' : new Intl.NumberFormat('zh-CN',{style:'currency',currency,maximumFractionDigits:2}).format(Number(value)); },
        quoteRange(p, field='cny') {
            if (!p) return '—';
            const values = p.quotes.map(q => q[field]).filter(v => v != null).map(Number);
            if (!values.length) return '—';
            const min = Math.min(...values), max = Math.max(...values), currency = field === 'usd' ? 'USD' : 'CNY';
            return min === max ? this.money(min,currency) : `${this.money(min,currency)} – ${this.money(max,currency)}`;
        },
        localText(p) { return !p ? '' : p.quotes.length === 1 ? p.local.formatted || '解析失败' : `${p.quotes.length} 档公开报价`; },
        delta(p) {
            if (this.retained(p.area) || this.retained(this.benchmark)) return '等待最新价格';
            if (!this.product?.comparable) return '多档 / 身份待确认';
            if (p.area === this.benchmark) return '基准地区';
            const value = percentage(p.cny,this.product.rows.find(p => p.area === this.benchmark)?.cny);
            return value == null ? '暂无基准价' : Math.abs(value) < .05 ? '与基准相同' : `${value < 0 ? '便宜' : '贵'} ${Math.abs(value).toFixed(1)}%`;
        },
        rank(p) { return this.retained(p.area) || !this.product?.comparable || p.cny == null ? '—' : this.validPrices.filter(x => Number(x.cny) < Number(p.cny)).length+1; },
        disconnect() {
            this.requestController?.abort(); this.requestController = null;
            if (this.stream) this.stream.close();
            this.stream = null; this.loading = false; this.searching = false;
        },
        cancelLoading() {
            ++this.generation; this.disconnect(); this.currentId = ''; this.snapshot = null; this.groups = [];
            this.notice = '已取消本次等待';
        },
        applySnapshot(data) {
            data = retainPrices(data, this.snapshot);
            this.snapshot = data; this.groups = groupProducts(data.products); this.queryAreas = data.areas;
            this.fromCache = snapshotFresh(data) && data.regions.length > 0
                && data.regions.every(r => Date.parse(r.fetchedAt) < this.requestStartedAt);
            if (data.progress.complete && !this.groups.some(p => p.key === this.selectedKey)) this.selectedKey = this.groups[0]?.key || 'app';
        },
        async search() {
            this.error = ''; this.notice = '';
            let generation;
            try {
                if (!this.input.trim()) throw new Error('请输入 App Store 链接、App ID 或名称');
                const id = parseAppInput(this.input);
                if (id) { this.query(id); return; }
                this.disconnect(); generation = ++this.generation;
                this.currentId = ''; this.snapshot = null; this.groups = []; this.candidates = [];
                this.searching = true;
                this.requestController = new AbortController();
                const res = await axios.post('/app/getAppList',{appName:this.input.trim(),areaCode:this.searchArea},{signal:this.requestController.signal});
                if (generation !== this.generation) return;
                if (res.data.code !== 0) throw new Error(res.data.message);
                this.candidates = res.data.data;
                if (!this.candidates.length) this.notice = '未找到应用，可更换关键词或搜索地区。';
            } catch (e) { if (generation == null || generation === this.generation) this.error = e.response?.data?.message || e.message; }
            finally { if (generation === this.generation) { this.searching = false; this.requestController = null; } }
        },
        async query(id, {retryFailed=false, refresh=false, areas=null} = {}) {
            this.disconnect(); const generation = ++this.generation;
            this.error = ''; this.notice = ''; this.candidates = []; this.fromCache = false;
            const selected = [...(areas || this.activeAreas)];
            if (!selected.length) { this.error = '请至少选择一个地区'; return; }
            if (this.currentId !== id) { this.selectedKey = 'app'; this.category = 'main'; this.productSearch = ''; }
            const previous = this.snapshot?.appId === id && cacheKey(id,this.snapshot.areas) === cacheKey(id,selected) ? this.snapshot : null;
            this.currentId = id; this.queryAreas = selected; this.requestStartedAt = Date.now();
            this.snapshot = null; this.groups = [];
            const cached = previous || cachedSnapshot(id,selected);
            if (cached) {
                this.applySnapshot(cached); this.fromCache = true;
                if (!retryFailed && !refresh && snapshotFresh(cached)) return;
                this.applySnapshot({...cached, regions:[], products:[], retainedRegions:[], refreshError:null,
                    startedAt:new Date(this.requestStartedAt).toISOString(),queryId:null,
                    progress:{total:selected.length,completed:0,available:0,unavailable:0,failed:0,complete:false}});
            }
            this.loading = true;
            const params = new URLSearchParams({scope:'custom',areas:selected.join(','),priority:this.myAreas.join(','),retryFailed:String(retryFailed),refresh:String(refresh)});
            try {
                this.requestController = new AbortController();
                const response = await axios.get(`/api/v2/apps/${encodeURIComponent(id)}/prices?${params}`,{signal:this.requestController.signal});
                if (generation !== this.generation) return;
                if (response.data.code !== 0) throw new Error(response.data.message);
                this.applySnapshot(response.data.data);
                if (this.snapshot.progress.complete) { this.loading = false; cacheSnapshot(this.snapshot); return; }
            } catch (e) {
                if (generation === this.generation) this.queryFailed(e.response?.data?.message || e.message);
                return;
            } finally {
                if (generation === this.generation) this.requestController = null;
            }
            params.set('retryFailed','false');
            params.set('refresh','false');
            params.set('queryId',this.snapshot.queryId);
            const stream = new EventSource(`/api/v2/apps/${encodeURIComponent(id)}/prices/stream?${params}`); this.stream = stream;
            let interruptions = 0;
            const update = e => {
                if (generation !== this.generation) return;
                this.applySnapshot(JSON.parse(e.data)); this.notice = ''; interruptions = 0;
            };
            stream.addEventListener('snapshot',update); stream.addEventListener('region',update);
            stream.addEventListener('complete', e => {
                update(e); stream.close();
                if (generation === this.generation) { this.stream = null; this.loading = false; cacheSnapshot(this.snapshot); }
            });
            stream.onerror = () => {
                if (generation !== this.generation || this.snapshot?.progress.complete) return;
                if (++interruptions >= 3) { this.disconnect(); this.queryFailed('连接暂不可用，请获取最新价格重试；已获取结果仍可查看。'); }
                else this.notice = '连接中断，正在重连…';
            };
        },
        queryFailed(message) {
            this.error = message; this.loading = false;
            if (this.snapshot) this.snapshot = {...this.snapshot, refreshError:message, progress:{...this.snapshot.progress,complete:false}};
        },
        async share() {
            const url = new URL(location.pathname,location.origin);
            url.searchParams.set('appId',this.currentId); url.searchParams.set('areas',this.queryAreas.join(','));
            try { await navigator.clipboard.writeText(url.toString()); this.notice = '已复制查询链接，包含本次地区范围'; }
            catch { this.notice = url.toString(); }
        },
        download(format) {
            if (!this.snapshot) return;
            const text = format === 'csv' ? exportCsv(this.snapshot) : JSON.stringify(this.snapshot,null,2);
            const url = URL.createObjectURL(new Blob([text],{type:format === 'csv' ? 'text/csv;charset=utf-8' : 'application/json'}));
            const a = document.createElement('a'); a.href = url;
            a.download = `app-${this.currentId}-${this.snapshot.progress.complete ? 'complete' : 'partial'}.${format}`;
            a.click(); URL.revokeObjectURL(url);
        }
    };
}
if (typeof module !== 'undefined') module.exports = {appStore,searchableSelect,filterOptions,parseAppInput,percentage,csvCell,exportCsv,groupProducts,cacheKey,cacheEntries,cachedSnapshot,cacheSnapshot,snapshotFresh,retainPrices,CACHE_KEY,CACHE_TTL,FRESH_TTL};
