// Isolated Paper 26.2 public-API migration regression. No protocol clients are emulated.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-26.2.mjs <stopped-paper-26.2-directory>
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
const source=path.resolve(process.argv[2]??'');
if(!process.argv[2]) throw new Error('Supply a stopped Paper 26.2 installation with accepted EULA.');
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
const root=path.resolve('.run','paper-26.2-'+Date.now());
await fs.mkdir(path.join(root,'plugins/BattleRoyale'),{recursive:true});
for(const name of ['paper.jar','libraries','versions','cache','eula.txt'])
  await fs.cp(path.join(source,name),path.join(root,name),{recursive:true});
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.3.jar'),path.join(root,'plugins/battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(root,'plugins/probe.jar'));
await fs.copyFile(path.resolve('src/paperProbe/resources/legacy-item-1.21.8.base64'),path.join(root,'p26-legacy-item.base64'));
const cfg=(await fs.readFile('src/main/resources/config.yml','utf8')).replace('world: world','world: battleroyale_lobby');
await fs.writeFile(path.join(root,'plugins/BattleRoyale/config.yml'),cfg);
await fs.writeFile(path.join(root,'server.properties'),[
 'server-ip=127.0.0.1','server-port=0','online-mode=true','level-name=battleroyale_lobby',
 'level-type=minecraft:normal','generate-structures=true','view-distance=2','simulation-distance=2',
 'max-players=4','enable-query=false','enable-rcon=false','spawn-protection=0'
].join('\n'));
await fs.writeFile(path.join(root,'bukkit.yml'),'settings:\n  allow-end: false\n');
await fs.writeFile(path.join(root,'spigot.yml'),'world-settings:\n  default:\n    verbose: false\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function start(logName){
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let output='',exit=null;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
 const append=b=>{output+=b;};
 child.stdout.on('data',append);child.stderr.on('data',append);
 child.on('exit',c=>{exit=c??-1;});child.on('error',e=>{output+=e.stack;exit=-1;});
 async function waitFor(expected,from=0,timeout=240000){
  const end=Date.now()+timeout;
  while(!output.slice(from).includes(expected)){
   if(/(?:P26|RC3) .*FAILED|PROBE failed=/.test(output.slice(from))||exit!==null||Date.now()>end)
    throw new Error('Waiting for '+expected+'\n'+output.slice(-18000));
   await sleep(100);
  }
  return output.slice(from);
 }
 async function command(cmd,expected){const from=output.length;child.stdin.write(cmd+'\n');return waitFor(expected,from);}
 async function stop(){
  if(exit===null)child.stdin.write('stop\n');
  const end=Date.now()+60000;
  while(exit===null&&Date.now()<end)await sleep(100);
  await fs.writeFile(path.join(root,logName),output);
  if(exit===null){child.kill();throw new Error('Disposable test server failed to stop normally');}
 }
 return{waitFor,command,stop,text:()=>output};
}
console.log('Isolated server: '+root);
const passed=[];let server=start('migration.log');
try{
 await server.waitFor('Done (');
 assert.match(server.text(),/Paper version 26\.2/);
 assert.match(server.text(),/\[BattleRoyale\] Loaded 2 rooms/);
 assert.doesNotMatch(server.text(),/Error occurred while enabling BattleRoyale|Startup failed; disabling BattleRoyale/);
 await server.command('br version','版本 1.0.0-rc.3');
 passed.push('Java 25 / Paper 26.2 build 129 startup, plugin enable and commands');
 for(const map of ['city','desert']){
  await server.command('brprobe p26generate '+map,'P26 GENERATED '+map+' SUCCESS');
  passed.push('Random normal terrain template '+map);
 }
 await server.command('brprobe p26verify','P26 VERIFY SUCCESS');
 passed.push('Seed, generator, block data, separate UUIDs, saved clone recovery, ownership cleanup, maintenance commit/rollback and native item migration');
 for(const map of ['city','desert']) await server.command('brprobe p26export '+map,'P26 EXPORT '+map+' SUCCESS');
 passed.push('Exported random templates with fixture blocks restored to original terrain');
 const reloadAt=server.text().length;
 await server.command('br reload','配置已成功重载。');
 await server.waitFor('BattleRoyale 1.0.0-rc.3 rooms=',reloadAt);
 const report=await server.command('br admin diagnose','runtime-root');
 assert.match(report,/正常 recovery：/);assert.match(report,/正常 maps：available=2 total=2/);
 for(const map of ['city','desert']) {
  const validation=await server.command('br admin map validate '+map,'地图：'+map+'　配置版本：');
  assert.doesNotMatch(validation,/错误：|校验未通过/);
 }
 const deep=await server.command('br admin map validate city --deep','地图：city　配置版本：');
 assert.doesNotMatch(deep,/错误：|校验未通过/);
 await sleep(1500);
 passed.push('Reload and administrative diagnostics');
}finally{await server.stop();}
server=start('restart.log');
try{
 await server.waitFor('Done (');
 assert.doesNotMatch(server.text(),/Error occurred while enabling BattleRoyale|Startup failed; disabling BattleRoyale/);
 await server.waitFor('BattleRoyale 1.0.0-rc.3 rooms=');
 await server.command('br version','版本 1.0.0-rc.3');
 const restartReport=await server.command('br admin diagnose','runtime-root');
 assert.match(restartReport,/正常 recovery：/);assert.match(restartReport,/正常 maps：available=2 total=2/);
 const worlds=await server.command('brprobe worlds','PROBE worlds=');
 assert.doesNotMatch(worlds,/battleroyale_game_|battleroyale_maintenance_|battleroyale_fixture_/);
 const log=await fs.readFile(path.join(root,'logs/latest.log'),'utf8');
 // This Windows host lacks OSHI's English Perflib counters. Keep the raw log;
 // ignore only these identified system-info blocks, never plugin/server failures.
 const blocks=log.split(/(?=^\[\d{2}:\d{2}:\d{2}\])/m);
 const checked=blocks.filter(block=>!/^\[.*\] \[CrashReport preload thread\/(ERROR|WARN)\]: (\[oshi\.|Failed to get system info for Process )/.test(block)).join('');
 assert.doesNotMatch(checked,/ERROR|Exception/);
 passed.push('Clean restart with templates and no stale test dimensions');
}finally{await server.stop();}
await fs.writeFile(path.join(root,'results.json'),JSON.stringify({root,paper:'26.2-129',java:25,passed,fixture:'Locally generated city/desert normal terrain; not the remote full Survival-Main map',limits:['No native 26.2 protocol client test','No economy provider integration','No MySQL service test']},null,2));
console.log(JSON.stringify({root,passed},null,2));
