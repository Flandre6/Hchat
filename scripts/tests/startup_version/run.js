const fs = require('fs');
const path = require('path');
const os = require('os');
const cp = require('child_process');
const repo = path.resolve(__dirname, '../../..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-startup-version-'));
const bin = '/data/data/com.termux/files/usr/bin/';
function sources(dir) { return fs.readdirSync(dir, {withFileTypes:true}).flatMap(e => e.isDirectory() ? sources(path.join(dir,e.name)) : e.name.endsWith('.java') ? [path.join(dir,e.name)] : []); }
function run(command,args) { const result=cp.spawnSync(command,args,{stdio:'inherit'}); if(result.error) throw result.error; if(result.status!==0) throw new Error(command+' exited '+result.status); }
try {
 const classes=path.join(temp,'classes');
 fs.mkdirSync(classes);
 run(bin+'javac',['-d',classes,...sources(__dirname),path.join(repo,'app/src/main/java/h/Hchat/hooks/api/runtime/WeChatVersionApi.java')]);
 run(bin+'java',['-cp',classes,'StartupVersionRegression',temp]);
} finally { fs.rmSync(temp,{recursive:true,force:true}); }
