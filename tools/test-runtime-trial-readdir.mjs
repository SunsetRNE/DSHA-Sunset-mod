// proroot v1.2.8 的路径钩子不覆盖 glibc 内部的 scandir：Node 的 readdir* 会对
// 试运行 payload（proroot -b 模拟绑定）里的目录报 ENOENT，而同一路径的 stat /
// 直接读写正常。dsh 0.2.2-rc.1 的 profile 解析在鉴权前枚举 node_modules，首次
// proroot 试运行因此以 exit 1 崩溃。本测试用真实 Node 预加载顺序与 lockfile 无关
// 的临时目录模拟该缺陷，验证 runtime-trial-entry.js 中只在 proroot 试运行生效的
// 回退：仅对 payload 内确实存在的目录改用 opendir；不存在、payload 外与递归枚举
// 仍保留原错误；proot 试运行（无 PROROOT_LIB_PATH）不安装回退。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath,pathToFileURL} from 'node:url';

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-trial-readdir-'));
const trial=path.join(owned,'trial'),outside=path.join(owned,'outside');
const home=path.join(trial,'home'),modules=path.join(home,'profiles','trial','node_modules');
const plugin=path.join(trial,'plugin');
const build=folder=>fs.mkdirSync(folder,{recursive:true});
build(plugin);build(path.join(modules,'@deepseek-ai'));build(outside);
fs.writeFileSync(path.join(plugin,'package.json'),'{"name":"dsha-runtime-check"}\n');
fs.symlinkSync(plugin,path.join(modules,'dsha-runtime-check'),'dir');
fs.symlinkSync(plugin,path.join(modules,'@deepseek-ai','dsh'),'dir');
fs.writeFileSync(path.join(outside,'x'),'');

const source=fs.readFileSync(path.join(repository,'app/src/main/assets/runtime-trial-entry.js'),'utf8');
const boundary=source.indexOf('\ntry {');
assert.ok(boundary>0,'试运行预加载必须保持可切分的预检边界');
const prelude=path.join(owned,'preload.mjs');
fs.writeFileSync(prelude,source.slice(0,boundary));
// 模拟 proroot：readdir*（libuv scandir）对任意路径一律 ENOENT，stat / opendir 正常；
// payload 内的真实目录才允许回退。
const simulator=path.join(owned,'proroot-scandir.mjs');
fs.writeFileSync(simulator,`
import fs from 'node:fs';
function failure(value){const error=new Error("ENOENT: no such file or directory, scandir '"+value+"'");error.code='ENOENT';error.errno=-2;error.syscall='scandir';error.path=value;return error}
const sync=fs.readdirSync;
fs.readdirSync=function(value,options){if(typeof value==='string')throw failure(value);return sync.call(this,value,options)};
const callback=fs.readdir;
fs.readdir=function(value,options,fn){if(typeof value==='string'){process.nextTick(typeof options==='function'?options:fn,failure(value));return}return callback.call(this,value,options,fn)};
const promised=fs.promises.readdir;
fs.promises.readdir=async function(value,options){if(typeof value==='string')throw failure(value);return promised.call(this,value,options)};
`);
const probe=path.join(owned,'probe.mjs');
fs.writeFileSync(probe,`
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const modules=${JSON.stringify(modules)};
const home=${JSON.stringify(home)};
const names=list=>list.map(item=>typeof item==='string'?item:item.name).sort();
const enoent=error=>error?.code==='ENOENT';
if(process.env.DSHA_TEST_MODE==='fallback'){
 const dirents=fs.readdirSync(modules,{withFileTypes:true});
 assert.deepEqual(names(dirents),['@deepseek-ai','dsha-runtime-check']);
 const link=dirents.find(item=>item.name==='dsha-runtime-check');
 assert.equal(link.isSymbolicLink(),true);
 assert.equal(link.isDirectory(),false);
 assert.equal(link.parentPath,modules);
 assert.deepEqual(names(fs.readdirSync(modules)),['@deepseek-ai','dsha-runtime-check']);
 const scoped=fs.readdirSync(path.join(modules,'@deepseek-ai'),{withFileTypes:true});
 assert.deepEqual(names(scoped),['dsh']);
 assert.equal(scoped[0].isSymbolicLink(),true);
 assert.deepEqual(names(await fs.promises.readdir(modules)),['@deepseek-ai','dsha-runtime-check']);
 const callback=await new Promise((resolve,reject)=>fs.readdir(modules,(error,value)=>error?reject(error):resolve(value)));
 assert.deepEqual(names(callback),['@deepseek-ai','dsha-runtime-check']);
 assert.throws(()=>fs.readdirSync(path.join(home,'profiles','missing')),enoent,'不存在的目录不能凭空返回');
 assert.throws(()=>fs.readdirSync(${JSON.stringify(outside)}),enoent,'payload 外不安装回退');
 assert.throws(()=>fs.readdirSync(modules,{recursive:true}),enoent,'递归枚举不在回退范围');
 process.stdout.write('FALLBACK_OK\\n');
}else{
 // 缺少 DSH_HOME（不在 payload 内）或缺少 PROROOT_LIB_PATH（proot 试运行）时不得安装回退。
 assert.throws(()=>fs.readdirSync(modules),enoent,'门禁不成立时不得安装回退');
 process.stdout.write('GATED_OK\\n');
}
`);
function run(mode,environment,unset=[]){
 const env={...process.env,NODE_OPTIONS:'--import='+pathToFileURL(simulator).href+' --import='+pathToFileURL(prelude).href,DSHA_TEST_MODE:mode};
 delete env.PROROOT_LIB_PATH;
 delete env.DSH_HOME;
 for(const name of unset)delete env[name];
 Object.assign(env,environment);
 const child=spawnSync(process.execPath,[probe],{encoding:'utf8',timeout:60000,windowsHide:true,env});
 assert.equal(child.error,undefined);
 assert.equal(child.status,0,child.stderr);
 return child.stdout;
}
const libraryPath=path.join(owned,'libproroot-runtime.so');
try{
 assert.equal(run('fallback',{DSH_HOME:home,PROROOT_LIB_PATH:libraryPath}),'FALLBACK_OK\n');
 assert.equal(run('gated',{PROROOT_LIB_PATH:libraryPath}),'GATED_OK\n');
 // 本机（proroot guest）里启动的 Node 会被供应商启动器重新写入 PROROOT_LIB_PATH，
 // 因此“有 DSH_HOME 且无 PROROOT_LIB_PATH”的 proot 门禁只能在普通 Linux 宿主上验证。
 if(!process.env.PROROOT_LIB_PATH)assert.equal(run('gated',{DSH_HOME:home},['PROROOT_LIB_PATH']),'GATED_OK\n');
 process.stdout.write('runtime trial readdir fallback: PASS\n');
}finally{
 fs.rmSync(owned,{recursive:true,force:true});
}
