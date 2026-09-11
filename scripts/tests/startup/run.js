const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const repo = path.resolve(__dirname, '../../..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-native-startup-'));
const termux = '/data/data/com.termux/files/usr/bin/';
function run(command, args) {
    const result = cp.spawnSync(command, args, { cwd: repo, stdio: 'inherit', timeout: 60_000 });
    if (result.error || result.status !== 0) throw result.error || new Error('Check failed: ' + result.status);
}
run(process.env.JAVAC || (fs.existsSync(termux + 'javac') ? termux + 'javac' : 'javac'), [
    '-d', output, 'app/src/main/java/h/Hchat/loader/utils/NativeLoadCache.java',
    'scripts/tests/startup/NativeLoadCacheRegression.java'
]);
run(process.env.JAVA || (fs.existsSync(termux + 'java') ? termux + 'java' : 'java'), [
    '-cp', output, 'h.Hchat.loader.utils.NativeLoadCacheRegression'
]);
console.log('Test artifacts: ' + output);
