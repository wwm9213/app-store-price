#!/usr/bin/env python3
"""Refresh Apple's actual storefront inventory; optionally verify currencies using public pages.
Requires Python 3 and Node (Intl.DisplayNames). No Apple account or ISO-country expansion.
"""
import argparse, concurrent.futures, datetime, html, json, pathlib, re, subprocess, urllib.request, urllib.parse, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = 'https://apps.apple.com/us/app/floating-clock/id1546947240'

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.url, r.read().decode()
        except Exception:
            if attempt == 2: raise
            time.sleep(2 ** attempt)

def bootstrap():
    ap = argparse.ArgumentParser(); ap.add_argument('--verify-currencies', action='store_true'); ap.add_argument('--only-missing', action='store_true'); args = ap.parse_args()
    _, page = fetch(SOURCE)
    codes = sorted(set(re.findall(r'href="/([a-z]{2})/iphone/today"[^>]*data-testid="region-list-link"', page)))
    if len(codes) < 150: raise RuntimeError('Apple region picker missing or changed; refusing to replace registry')
    names = json.loads(subprocess.check_output(['node', '-e', "const codes=JSON.parse(process.argv[1]);const zh=new Intl.DisplayNames(['zh-CN'],{type:'region'}),en=new Intl.DisplayNames(['en'],{type:'region'});console.log(JSON.stringify(Object.fromEntries(codes.map(c=>[c,[zh.of(c.toUpperCase()),en.of(c.toUpperCase())]]))))", json.dumps(codes)]))
    groups = {
      '北美': 'ag ai bb bm bs bz ca cr dm do gd gt hn jm kn ky lc ms mx ni pa sv tc tt us vc vg',
      '南美': 'ar bo br cl co ec gy pe py sr uy ve',
      '欧洲': 'al at ba be bg by ch cy cz de dk ee es fi fr gb gr hr hu ie is it lt lu lv md me mk mt nl no pl pt ro rs ru se si sk ua xk',
      '中东': 'ae bh il iq jo kw lb om qa sa tr ye',
      '非洲': 'ao bf bj bw cd cg ci cm cv dz eg ga gh gm gw ke lr ly ma mg ml mr mu mw mz na ne ng rw sc sl sn st sz td tn tz ug za zm zw',
      '大洋洲': 'au fj fm nr nz pg pw sb to vu',
      '亚洲': 'af am az bn bt cn ge hk id in jp kg kh kr kz la lk mm mn mo mv my np ph pk sg th tj tm tw uz vn'
    }
    regions = {c:g for g,cs in groups.items() for c in cs.split()}
    if set(codes) - regions.keys(): raise RuntimeError('Unclassified regions: '+str(set(codes)-regions.keys()))
    dest=ROOT/'src/main/resources/storefronts.json'
    old={s['code']:s for s in json.loads(dest.read_text())['storefronts']} if dest.exists() else {}
    def make(code):
      record={'code':code,'nameZh':{'cn':'中国大陆','hk':'香港','mo':'澳门'}.get(code,names[code][0]),'nameEn':names[code][1],'region':regions[code], 'currencyCode':old.get(code,{}).get('currencyCode'), 'currencySymbol':old.get(code,{}).get('currencySymbol'), 'locale':old.get(code,{}).get('locale','en-'+code.upper())}
      if args.verify_currencies and not (args.only_missing and record['currencyCode']):
       try:
        url,s=fetch(f'https://apps.apple.com/{code}/app/id1546947240')
        record['locale']=re.search(r'<html[^>]*lang="([^"]+)"',s).group(1)
        if urllib.parse.urlparse(url).path.split('/')[1]!=code: raise RuntimeError('Cross-store redirect')
        for raw in re.findall(r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>',s,re.S):
         d=json.loads(raw)
         if d.get('@type')=='SoftwareApplication':record['currencyCode']=d['offers']['priceCurrency']
        if code in ['us','cn','hk','tw','jp','kr','tr','au','br','id']:
         m=re.search(r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>',s,re.S)
         d=json.loads(m.group(1)); p=d['data'][0]['data']
         minimal={k:p[k] for k in ['title','developerAction','lockup','pageMetrics']}
         minimal['shelfMapping']={'information':p['shelfMapping']['information']}
         # Drop unrelated recommendations, artwork and metrics but retain actual parser input.
         minimal['lockup']={k:p['lockup'].get(k) for k in ['subtitle','offerDisplayProperties']}
         minimal['developerAction']={'title':p['developerAction']['title']}
         minimal['pageMetrics']={'pageFields':{'storeFront':code}}
         lang=re.search(r'<html[^>]*lang="([^"]+)"',s).group(1)
         ld=[json.loads(x) for x in re.findall(r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>',s,re.S) if json.loads(x).get('@type')=='SoftwareApplication'][0]
         ld={k:ld.get(k) for k in ['@type','offers','image']}
         fixture=f'<html lang="{lang}"><script id="serialized-server-data" type="application/json">'+json.dumps({'data':[{'data':minimal}]},ensure_ascii=False)+'</script><script type="application/ld+json">'+json.dumps(ld)+'</script></html>'
         (ROOT/f'src/test/resources/fixtures/{code}.html').write_text(fixture)
        print(code,record['currencyCode'],flush=True)
       except Exception as e: print(code,'FAILED',str(e),flush=True)
      return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex: records=list(ex.map(make,codes))
    for r in records:
      if r['currencyCode']:
       r['currencySymbol']=subprocess.check_output(['node','-e',"console.log(new Intl.NumberFormat('en',{style:'currency',currency:process.argv[1],currencyDisplay:'narrowSymbol'}).formatToParts(0).find(x=>x.type==='currency').value)",r['currencyCode']],text=True).strip()
    data={'source':SOURCE,'verifiedAt':datetime.date.today().isoformat(),'storefronts':records}
    dest.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    print('Loaded',len(records),'storefronts; currencies verified',sum(bool(r['currencyCode']) for r in records))
if __name__=='__main__':bootstrap()
