"""Measure source screenshot pixels against the same buttons before the keyboard opened."""
from pathlib import Path
import json,sys,hashlib
from PIL import Image
e=Path(__file__).resolve().parent
name=sys.argv[1];d=e/name
data=json.loads((d/'result.json').read_text(encoding='utf8'))
def ink_stats(img,box):
 if not box:return {'ink':0,'rows':0,'maskSha256':None}
 x0,y0,x1,y1=box
 crop=img.crop(box).convert('RGB')
 mask=Image.new('1',crop.size)
 mask.putdata([r<125 and g<155 and b>170 and b-r>100 for r,g,b in crop.getdata()])
 b=mask.getbbox()
 if not b:return {'ink':0,'rows':0,'maskSha256':None}
 return {'ink':sum(1 for v in mask.getdata() if v),'rows':b[3]-b[1],'maskSha256':hashlib.sha256(mask.crop(b).tobytes()).hexdigest(),'inkTop':b[1]+y0,'inkBottom':b[3]+y0}
def area(box):return 0 if not box else max(box[2]-box[0],0)*max(box[3]-box[1],0)
def intersect(a,b):return [max(a[0],b[0]),max(a[1],b[1]),min(a[2],b[2]),min(a[3],b[3])]
stats=[]
for row in data['rows']:
 label=row['label'];f=d/(label+'.png')
 if not f.is_file():continue
 im=Image.open(f).convert('RGB')
 result={'label':label,'seconds':row['time'],'ime':row['imeFrame'],'screenshotSha256':hashlib.sha256(f.read_bytes()).hexdigest()}
 for k in ('choose','cancel'):
  box=row[k]; full=row['layout'][k]['full']
  result[k]=dict(ink_stats(im,box),full=full,visible=box,fullHeight=full[3]-full[1],visibleHeight=box[3]-box[1] if box else 0)
 if row['ime'] and row['imeFrame']:
  focuses=[i for i in row['layout']['inputs'] if i['focused']]
  if focuses:
   field=focuses[0];full=field['full']
   vis=intersect(full,field['visible'])
   vis=intersect(vis,[0,0,im.width,row['imeFrame'][1]])
   result['focus']={'hint':field['hint'],'length':field['length'],'full':full,'visible':vis,'visibleArea':area(vis),'area':area(full),'baseline':field['baseline']}
   if area(vis):
    x,y,rr,bb=vis;top=max(y,field['baseline']-field['lineHeight']+5);bottom=min(bb,field['baseline']+2)
    pixels=[]
    if top<bottom:
     for yy in range(top,bottom):
      for xx in range(x+4,rr-3):
       r,g,b=im.getpixel((xx,yy))
       if max(r,g,b)<150:pixels.append((xx,yy))
    result['focus']['textInkPixels']=len(pixels)
    cols=sorted(set(xx for xx,yy in pixels));result['focus']['visibleGlyphRuns']=sum(1 for i,xx in enumerate(cols) if i==0 or xx!=cols[i-1]+1)
    if pixels: result['focus']['textInkBounds']=[min(p[0] for p in pixels),min(p[1] for p in pixels),max(p[0] for p in pixels)+1,max(p[1] for p in pixels)+1]
 stats.append(result)
ref=next((r for r in stats if r['label']=='hidden-reference'),stats[0])
for row in stats:
 for k in ('choose','cancel'):
  row[k]['sameInk']=row[k]['maskSha256']==ref[k]['maskSha256']
  row[k]['percentInk']=round(100*row[k]['ink']/ref[k]['ink'],2) if ref[k]['ink'] else None
(d/'pixels.json').write_text(json.dumps(stats,indent=2),encoding='utf8')
for row in stats:
 print(row['label'],*[f"{row[k]['ink']}/{row[k]['rows']}/{row[k]['percentInk']}%/same={row[k]['sameInk']}" for k in ('choose','cancel')],row.get('focus',{}))
