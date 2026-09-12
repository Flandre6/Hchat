// Compile the production host profiles without Gradle or Android dependencies.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const cache = process.argv[2] || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function artifact(group, name, version) {
    const directory = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(directory)) {
        const candidate = path.join(directory, hash, name + '-' + version + '.jar');
        if (fs.existsSync(candidate)) return candidate;
    }
    throw new Error('Missing dependency: ' + directory);
}
const stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const compiler = [
    artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    artifact('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    artifact('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-special-message-tests-'));
const jar = path.join(output, 'tests.jar');
const java = process.env.JAVA || 'java';
function run(args) {
    const result = spawnSync(java, args, { cwd: root, encoding: 'utf8', timeout: 60_000 });
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
run(['-Xmx256m', '-cp', compiler.join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', stdlib, '-d', jar,
    'app/src/main/java/h/Hchat/hooks/items/specialmessage/SafetyMessageHostProfile.kt',
    'app/src/main/java/h/Hchat/hooks/items/securemessage/SecureEmojiHostProfile.kt',
    'scripts/tests/safety_message/SafetyMessageCompatRegression.kt',
    'scripts/tests/safety_message/SecureEmojiHostProfileRegression.kt']);
run(['-cp', [jar, stdlib].join(path.delimiter),
    'h.Hchat.hooks.items.specialmessage.SafetyMessageCompatRegressionKt']);
run(['-cp', [jar, stdlib].join(path.delimiter),
    'h.Hchat.hooks.items.securemessage.SecureEmojiHostProfileRegressionKt']);
console.log('Test artifacts: ' + output);
