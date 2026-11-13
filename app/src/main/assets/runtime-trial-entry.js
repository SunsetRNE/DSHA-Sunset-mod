// 在同一个 Node 中完成原生模块检查并启动真实 DSH，避免先启动多轮 Node 再重复加载。
import fs,{readFileSync,openSync,closeSync,unlinkSync,lstatSync} from 'node:fs';
import path from 'node:path';
import {createRequire,Module,syncBuiltinESMExports} from 'node:module';
import {Script} from 'node:vm';
import {pathToFileURL,fileURLToPath} from 'node:url';
// Node has already selected this preload. Consume only its exact first option,
// so fresh storage/MCP children keep all ordinary preloads without rerunning it.
const nonce=process.env.DSHA_RUNTIME_TRIAL_NONCE;
const ownOptions=['--import='+fileURLToPath(import.meta.url)+' ','--import='+import.meta.url+' '];
if(/^[a-f0-9]{32}$/.test(nonce||''))ownOptions.push('--import=/root/.dsha-runtime-trial-'+nonce+'/runtime-entry.mjs ');
const ownOption=ownOptions.find(option=>process.env.NODE_OPTIONS?.startsWith(option));
if(ownOption){
 const rest=process.env.NODE_OPTIONS.slice(ownOption.length);
 if(rest)process.env.NODE_OPTIONS=rest;else delete process.env.NODE_OPTIONS;
}
const stage=value=>{
 process.stdout.write('DSHA_TRIAL_NATIVE_STAGE '+value+'\n');
 process.stdout.write('DSHA_TRIAL_TIME '+value+' '+Math.floor(performance.now())+'\n');
};
// proroot v1.2.8 的路径钩子没有覆盖 glibc 内部的 scandir：Node 的 readdir*
// 对本轮 payload（proroot -b 模拟绑定、宿主刚物化）里的目录报 ENOENT，而同一
// 路径的 stat / 直接读写正常。dsh 0.2.2-rc.1 的 profile 解析在鉴权前枚举
// node_modules（dsh-app-boot 的 linkedProfileRoots -> symlinksUnder），首次
// proroot 试运行因此以 exit 1 崩溃。仅在 proroot 试运行、路径属于本次 payload
// 且目录确实存在时，改用受路径钩子翻译的 opendir 重列；不改变试运行的任何
// 接受条件，也不影响 proot 或非试运行的 Node 进程。
const trialRoot=(()=>{const home=process.env.DSH_HOME;return typeof home==='string'&&path.isAbsolute(home)?path.dirname(home):''})();
function readdirFallback(error,value,options){
 if(!error||error.code!=='ENOENT'||typeof value!=='string'||!trialRoot)throw error;
 const target=path.resolve(value);
 if(target!==trialRoot&&!target.startsWith(trialRoot+path.sep))throw error;
 const settings=typeof options==='string'?{encoding:options}:options&&typeof options==='object'?options:{};
 if(settings.recursive)throw error;
 let info;
 try{info=fs.statSync(value)}catch{throw error}
 if(!info.isDirectory())throw error;
 const encoding=settings.encoding||'utf8';
 const directory=fs.opendirSync(value,{encoding});
 const entries=[];
 let entry;
 try{while((entry=directory.readSync({encoding}))!==null)entries.push(entry)}
 finally{directory.closeSync()}
 return settings.withFileTypes?entries:entries.map(item=>item.name);
}
function installReaddirFallback(){
 if(!trialRoot||!process.env.PROROOT_LIB_PATH)return;
 const readdirSync=fs.readdirSync,readdir=fs.readdir,readdirPromises=fs.promises.readdir;
 fs.readdirSync=function(value,options){
  try{return readdirSync.call(this,value,options)}catch(error){return readdirFallback(error,value,options)}
 };
 fs.readdir=function(value,options,callback){
  if(typeof options==='function'){callback=options;options=undefined}
  return readdir.call(this,value,options,(error,entries)=>{
   if(!error||error.code!=='ENOENT'){callback(error,entries);return}
   let recovered;
   try{recovered=readdirFallback(error,value,options)}catch(failed){callback(failed);return}
   callback(null,recovered);
  });
 };
 fs.promises.readdir=async function(value,options){
  try{return await readdirPromises.call(this,value,options)}catch(error){return readdirFallback(error,value,options)}
 };
 syncBuiltinESMExports();
}
installReaddirFallback();
try {
 stage('pnpm');
 // The installer owns these signed files. Validate the actual locked manager
 // entry here, without letting a second CLI parse DSH argv or start another Node.
 const pnpmRoot='/usr/local/lib/dsha-pnpm';
 if(!lstatSync(pnpmRoot+'/package.json').isFile())throw Error('Bundled pnpm manifest invalid');
 const manager=JSON.parse(readFileSync(pnpmRoot+'/package.json','utf8'));
 if(manager.name!=='pnpm'||manager.version!==process.env.DSHA_TRIAL_PNPM_VERSION||manager.bin?.pnpm!=='bin/pnpm.cjs')throw Error('Bundled pnpm version or entry mismatch');
 for(const file of [pnpmRoot+'/bin/pnpm.cjs',pnpmRoot+'/dist/pnpm.cjs']){
  const info=lstatSync(file);if(!info.isFile()||info.size<=0||info.size>64*1024*1024)throw Error('Bundled pnpm entry invalid');
  new Script(Module.wrap(readFileSync(file,'utf8').replace(/^#![^\n]*\n/,'')),{filename:file});
 }
 stage('modules');
 const packagePath='/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json';
 const pkg=JSON.parse(readFileSync(packagePath,'utf8'));
 if(pkg.version!==process.env.DSHA_TRIAL_DSH_VERSION||process.arch!=='arm64'||!process.versions.node.startsWith('24.'))throw Error('Runtime version mismatch');
 const require=createRequire(packagePath);
 for(const name of ['sharp','koffi','node-pty','@deepseek-ai/node-addon-system/landlock-run'])require(name);
 stage('loader');
 const loader=require('@deepseek-ai/cordis-plugin-loader').ModuleLoader.fromInternal();
 if(!loader||typeof loader.import!=='function')throw Error('Native plugin loader unavailable');
 const paths=JSON.parse(process.env.DSHA_TRIAL_BUILTIN_ENTRIES||'{}').entries;
 if(!Array.isArray(paths)||paths.length===0)throw Error('Builtin entry list missing');
 for(let index=0;index<paths.length;index++){stage('builtin-'+index);await import(pathToFileURL(paths[index]).href)}
 stage('flock');
 const file=process.env.HOME+'/.native-check-'+process.pid,fd=openSync(file,'wx',384);
 try{await require('@deepseek-ai/node-addon-system/flock').tryLockExclusive(fd)}finally{closeSync(fd);unlinkSync(file)}
 stage('native-ready');
 process.stdout.write('DSHA_TRIAL_NATIVE_CHECK_READY\n');
}catch(error){console.error('DSHA_TRIAL_NATIVE_CHECK_FAILED',error);process.exit(78)}
